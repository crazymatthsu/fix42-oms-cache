package com.fix42.oms.parquet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-of-day compaction against real files: many one-row files in, few large files out, with
 * every row still present exactly once.
 */
class ParquetCompactorIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-08-04T13:30:00Z");
    private static final LocalDate DATE = LocalDate.of(2026, 8, 4);
    private static final Path IBM_PARTITION = Path.of("fix_messages/2026/08/04/ACC1/IBM");

    /** One row per file, so the sample stream produces the small-file problem to solve. */
    private ParquetArchiveConfig oneRowPerFile(Path root, Clock clock) {
        return ParquetArchiveConfig.defaults(root)
                .withClock(clock)
                .withWriterId("test-writer")
                .withBatchPolicy(BatchPolicy.defaults().withMaxRowsPerFile(1));
    }

    private void captureSampleStream(ParquetArchiveConfig config, TestClock clock) {
        try (ParquetArchive archive = ParquetArchive.open(config)) {
            for (String message : SampleFix42.capturedStream()) {
                archive.writeRawMessage(message);
                if (clock != null) {
                    clock.advance(Duration.ofSeconds(1));
                }
            }
            archive.flush();
        }
    }

    private static List<Path> filesIn(Path directory) {
        return ParquetCompactor.parquetFilesIn(directory);
    }

    private static long countAllParquetFiles(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).filter(ParquetPaths::isParquetFile).count();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void manySmallFilesBecomeOne(@TempDir Path root) {
        ParquetArchiveConfig config = oneRowPerFile(root, Clock.fixed(NOW, ZoneOffset.UTC));
        captureSampleStream(config, null);

        List<Path> before = filesIn(root.resolve(IBM_PARTITION));
        assertTrue(before.size() >= 10, "expected many small files, got " + before.size());
        long totalBefore = countAllParquetFiles(root.resolve("fix_messages"));

        ParquetCompactor.CompactionResult result;
        try (ParquetCompactor compactor = new ParquetCompactor(config)) {
            result = compactor.compact(Dataset.RAW_FIX_MESSAGES, DATE);
        }

        List<Path> after = filesIn(root.resolve(IBM_PARTITION));
        assertEquals(1, after.size(), "the partition should be a single file");
        assertEquals(totalBefore, result.filesBefore(), "the result covers every partition of the day");
        assertEquals(countAllParquetFiles(root.resolve("fix_messages")), result.filesAfter());
        assertTrue(after.get(0).getFileName().toString().contains("-compact-"),
                "compacted files are named so they can be told apart: " + after.get(0));

        // rows() counts only what was actually merged. FIX 4.2's OrderCancelReject carries
        // neither Account(1) nor Symbol(55), so it lands alone in an _unknown partition with
        // nothing to merge — and every row is still readable afterwards.
        assertEquals(SampleFix42.capturedStream().size() - 1, result.rows());
        try (ParquetArchiveReader reader = new ParquetArchiveReader(config)) {
            assertEquals(SampleFix42.capturedStream().size(), reader.count(Dataset.RAW_FIX_MESSAGES));
        }
    }

    @Test
    void everyRowSurvivesCompactionExactlyOnce(@TempDir Path root) {
        ParquetArchiveConfig config = oneRowPerFile(root, Clock.fixed(NOW, ZoneOffset.UTC));
        captureSampleStream(config, null);

        List<Map<String, Object>> before;
        try (ParquetArchiveReader reader = new ParquetArchiveReader(config)) {
            before = reader.readAll(Dataset.RAW_FIX_MESSAGES);
        }
        try (ParquetCompactor compactor = new ParquetCompactor(config)) {
            compactor.compactAll(DATE);
        }
        try (ParquetArchiveReader reader = new ParquetArchiveReader(config)) {
            assertEquals(before, reader.readAll(Dataset.RAW_FIX_MESSAGES),
                    "compaction must be a pure rewrite: same rows, same values, same order by ingest_seq");
        }
    }

    @Test
    void compactionIsIdempotent(@TempDir Path root) {
        ParquetArchiveConfig config = oneRowPerFile(root, Clock.fixed(NOW, ZoneOffset.UTC));
        captureSampleStream(config, null);

        try (ParquetCompactor compactor = new ParquetCompactor(config)) {
            compactor.compact(Dataset.RAW_FIX_MESSAGES, DATE);
            long filesAfterFirst = countAllParquetFiles(root.resolve("fix_messages"));

            ParquetCompactor.CompactionResult second = compactor.compact(Dataset.RAW_FIX_MESSAGES, DATE);
            assertEquals(0, second.partitionsCompacted(), "nothing left to merge");
            assertEquals(filesAfterFirst, countAllParquetFiles(root.resolve("fix_messages")));
        }
    }

    @Test
    void newFilesAfterACompactionAreMergedByTheNextRun(@TempDir Path root) {
        ParquetArchiveConfig config = oneRowPerFile(root, Clock.fixed(NOW, ZoneOffset.UTC));
        captureSampleStream(config, null);
        try (ParquetCompactor compactor = new ParquetCompactor(config)) {
            compactor.compact(Dataset.RAW_FIX_MESSAGES, DATE);
        }

        // The day carries on: more messages arrive into an already-compacted partition.
        try (ParquetArchive archive = ParquetArchive.open(config)) {
            archive.writeRawMessage(SampleFix42.CHILD1_FILL);
            archive.flush();
        }
        assertEquals(2, filesIn(root.resolve(IBM_PARTITION)).size());

        try (ParquetCompactor compactor = new ParquetCompactor(config)) {
            compactor.compact(Dataset.RAW_FIX_MESSAGES, DATE);
        }
        assertEquals(1, filesIn(root.resolve(IBM_PARTITION)).size(),
                "an already-compacted file is just another input");
    }

    @Test
    void oversizedPartitionsAreSplitWithoutLosingOrDuplicatingRows(@TempDir Path root) {
        // A fixed clock puts every row on the same ts, which is the case that breaks a naive
        // split: ordering by ts alone leaves ties, and re-deriving row numbers per output
        // file then duplicates some rows and drops others.
        ParquetArchiveConfig config = oneRowPerFile(root, Clock.fixed(NOW, ZoneOffset.UTC));
        captureSampleStream(config, null);
        int sourceRows = filesIn(root.resolve(IBM_PARTITION)).size();

        CompactionPolicy split = CompactionPolicy.defaults().withMaxRowsPerOutputFile(5);
        try (ParquetCompactor compactor = new ParquetCompactor(config, split, ArchiveErrorHandler.LOGGING)) {
            compactor.compact(Dataset.RAW_FIX_MESSAGES, DATE);
        }

        List<Path> after = filesIn(root.resolve(IBM_PARTITION));
        assertEquals((sourceRows + 4) / 5, after.size(), "one output file per 5 rows");

        try (ParquetArchiveReader reader = new ParquetArchiveReader(config)) {
            Map<String, Object> counts = reader.query(
                    "SELECT count(*) AS n, count(DISTINCT ingest_seq) AS d FROM "
                            + reader.scanSql(Dataset.RAW_FIX_MESSAGES)).get(0);
            assertEquals(counts.get("n"), counts.get("d"),
                    "the split parts must partition the rows, not overlap them");
            assertEquals((long) SampleFix42.capturedStream().size(),
                    ((Number) counts.get("n")).longValue());
        }
    }

    @Test
    void compactedRowsAreOrderedByTimeForRowGroupPruning(@TempDir Path root) {
        TestClock clock = new TestClock(NOW);
        ParquetArchiveConfig config = oneRowPerFile(root, clock);
        captureSampleStream(config, clock);

        try (ParquetCompactor compactor = new ParquetCompactor(config)) {
            compactor.compact(Dataset.RAW_FIX_MESSAGES, DATE);
        }

        Path compacted = filesIn(root.resolve(IBM_PARTITION)).get(0);
        try (ParquetArchiveReader reader = new ParquetArchiveReader(config)) {
            // No ORDER BY: this reads the file in physical order.
            List<Map<String, Object>> rows = reader.query(
                    "SELECT ts FROM read_parquet('" + compacted + "')");
            LocalDateTime previous = null;
            for (Map<String, Object> row : rows) {
                LocalDateTime ts = ((java.sql.Timestamp) row.get("ts")).toLocalDateTime();
                if (previous != null) {
                    assertFalse(ts.isBefore(previous), "rows are not in time order: " + ts + " after " + previous);
                }
                previous = ts;
            }
            assertTrue(rows.size() > 1);
        }
    }

    @Test
    void sortingCanBeTurnedOff(@TempDir Path root) {
        ParquetArchiveConfig config = oneRowPerFile(root, Clock.fixed(NOW, ZoneOffset.UTC));
        captureSampleStream(config, null);

        CompactionPolicy unsorted = CompactionPolicy.defaults().withSortByTimestamp(false);
        try (ParquetCompactor compactor = new ParquetCompactor(config, unsorted, ArchiveErrorHandler.LOGGING)) {
            ParquetCompactor.CompactionResult result = compactor.compact(Dataset.RAW_FIX_MESSAGES, DATE);
            assertTrue(result.partitionsCompacted() > 0);
        }
        assertEquals(1, filesIn(root.resolve(IBM_PARTITION)).size());
    }

    @Test
    void aDayWithNoDataIsANoOpNotAFailure(@TempDir Path root) {
        ParquetArchiveConfig config = oneRowPerFile(root, Clock.fixed(NOW, ZoneOffset.UTC));
        captureSampleStream(config, null);

        try (ParquetCompactor compactor = new ParquetCompactor(config)) {
            ParquetCompactor.CompactionResult result =
                    compactor.compact(Dataset.RAW_FIX_MESSAGES, DATE.minusDays(1));
            assertEquals(0, result.partitions().size());
            assertEquals(0, result.filesBefore());
        }
        // The day that does have data is untouched by compacting a different day.
        assertTrue(filesIn(root.resolve(IBM_PARTITION)).size() > 1);
    }

    @Test
    void aSingleFilePartitionIsLeftAlone(@TempDir Path root) {
        ParquetArchiveConfig config = ParquetArchiveConfig.defaults(root)
                .withClock(Clock.fixed(NOW, ZoneOffset.UTC));
        try (ParquetArchive archive = ParquetArchive.open(config)) {
            archive.writeRawMessage(SampleFix42.CHILD1_ACK);
            archive.flush();
        }
        Path onlyFile = filesIn(root.resolve(IBM_PARTITION)).get(0);

        try (ParquetCompactor compactor = new ParquetCompactor(config)) {
            ParquetCompactor.CompactionResult result = compactor.compact(Dataset.RAW_FIX_MESSAGES, DATE);
            assertEquals(1, result.partitions().size());
            assertTrue(result.partitions().get(0).skipped());
        }
        assertEquals(List.of(onlyFile), filesIn(root.resolve(IBM_PARTITION)),
                "rewriting a one-file partition would churn storage for nothing");
    }

    @Test
    void bothDatasetsAreCompactedTogether(@TempDir Path root) {
        ParquetArchiveConfig config = oneRowPerFile(root, Clock.fixed(NOW, ZoneOffset.UTC));
        try (ParquetArchive archive = ParquetArchive.open(config)) {
            com.fix42.oms.cache.InMemoryOrderCache inner = new com.fix42.oms.cache.InMemoryOrderCache(
                    com.fix42.oms.model.DefaultParentLinkResolver.create(),
                    com.fix42.oms.cache.CacheConfig.defaults().withClock(Clock.fixed(NOW, ZoneOffset.UTC)),
                    archive.orderStateListener());
            com.fix42.oms.api.OmsCache cache = new com.fix42.oms.api.OmsCache(archive.wrap(inner));
            SampleFix42.stream().forEach(cache::process);
            archive.flush();
        }

        try (ParquetCompactor compactor = new ParquetCompactor(config)) {
            List<ParquetCompactor.CompactionResult> results = compactor.compactAll(DATE);
            assertEquals(Dataset.values().length, results.size());
            for (ParquetCompactor.CompactionResult result : results) {
                assertTrue(result.partitionsCompacted() > 0, result.dataset() + " was not compacted");
                assertTrue(result.filesAfter() < result.filesBefore());
            }
        }
    }
}
