package com.fix42.oms.parquet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The overnight move: compact the finished day, copy it to the object store, reclaim the
 * local disk. Uses {@link LocalDirectoryObjectStore} as the destination — the S3
 * implementation differs only in where the bytes go, and its statements are asserted in
 * {@link S3ConfigAndSqlTest}.
 */
class EndOfDayArchiverIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-08-04T13:30:00Z");
    private static final LocalDate DATE = LocalDate.of(2026, 8, 4);

    private ParquetArchiveConfig config(Path root) {
        return ParquetArchiveConfig.defaults(root)
                .withClock(Clock.fixed(NOW, ZoneOffset.UTC))
                .withWriterId("test-writer")
                .withBatchPolicy(BatchPolicy.defaults().withMaxRowsPerFile(1));
    }

    private void captureADay(ParquetArchiveConfig config) {
        try (ParquetArchive archive = ParquetArchive.open(config)) {
            for (String message : SampleFix42.capturedStream()) {
                archive.writeRawMessage(message);
            }
            archive.flush();
        }
    }

    private static List<Path> parquetFilesUnder(Path root) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).filter(ParquetPaths::isParquetFile).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void compactsThenUploadsThenReclaimsLocalDisk(@TempDir Path root, @TempDir Path bucket) {
        ParquetArchiveConfig config = config(root);
        captureADay(config);
        int filesBeforeCompaction = parquetFilesUnder(root).size();

        EndOfDayArchiver.Result result;
        try (EndOfDayArchiver eod = new EndOfDayArchiver(config, EndOfDayPolicy.moveToObjectStore(),
                new LocalDirectoryObjectStore(bucket))) {
            result = eod.run(DATE);
        }

        assertTrue(result.filesMerged() > 0, "compaction should have removed files");
        assertEquals(0, result.upload().failures());
        assertTrue(result.upload().bytesUploaded() > 0);
        assertTrue(result.upload().filesUploaded() < filesBeforeCompaction,
                "uploading after compaction means far fewer objects");
        assertEquals(result.upload().filesUploaded(), result.upload().filesDeleted());

        // The object store mirrors the local layout exactly.
        assertTrue(Files.isDirectory(bucket.resolve("fix_messages/2026/08/04/ACC1/IBM")));
        assertEquals(result.upload().filesUploaded(), parquetFilesUnder(bucket).size());

        // Local disk is reclaimed, empty partition directories and all.
        assertEquals(List.of(), parquetFilesUnder(root.resolve("fix_messages/2026")));
        assertFalse(Files.exists(root.resolve("fix_messages/2026/08/04/ACC1/IBM")),
                "empty partition directories are pruned");
    }

    @Test
    void uploadedFilesAreReadableAndCompleteAtTheDestination(@TempDir Path root, @TempDir Path bucket) {
        ParquetArchiveConfig config = config(root);
        captureADay(config);

        try (EndOfDayArchiver eod = new EndOfDayArchiver(config, EndOfDayPolicy.moveToObjectStore(),
                new LocalDirectoryObjectStore(bucket))) {
            eod.run(DATE);
        }

        // Point a reader at the destination as if it were the archive: same layout, same query.
        ParquetArchiveConfig atBucket = ParquetArchiveConfig.defaults(bucket);
        try (ParquetArchiveReader reader = new ParquetArchiveReader(atBucket)) {
            assertEquals(SampleFix42.capturedStream().size(), reader.count(Dataset.RAW_FIX_MESSAGES));
            assertEquals(SampleFix42.CHILD1_ACK, reader.query(
                    "SELECT raw_fix FROM " + reader.scanSql(Dataset.RAW_FIX_MESSAGES)
                            + " WHERE exec_id = 'E1'").get(0).get("raw_fix"));
        }
    }

    @Test
    void keysMirrorTheLocalPathIncludingTheDatasetDirectory(@TempDir Path root, @TempDir Path bucket) {
        ParquetArchiveConfig config = config(root);
        captureADay(config);
        try (EndOfDayArchiver eod = new EndOfDayArchiver(config, EndOfDayPolicy.defaults(),
                new LocalDirectoryObjectStore(bucket))) {
            eod.run(DATE);
        }

        for (Path uploaded : parquetFilesUnder(bucket)) {
            String key = bucket.relativize(uploaded).toString();
            assertTrue(key.startsWith("fix_messages/2026/08/04/") || key.startsWith("order_state/2026/08/04/"),
                    "unexpected key " + key);
        }
    }

    @Test
    void keepingTheLocalCopyIsTheDefault(@TempDir Path root, @TempDir Path bucket) {
        ParquetArchiveConfig config = config(root);
        captureADay(config);

        try (EndOfDayArchiver eod = new EndOfDayArchiver(config, EndOfDayPolicy.defaults(),
                new LocalDirectoryObjectStore(bucket))) {
            EndOfDayArchiver.Result result = eod.run(DATE);
            assertEquals(0, result.upload().filesDeleted());
        }
        assertFalse(parquetFilesUnder(root).isEmpty(), "local disk still serves intraday queries");
        assertFalse(parquetFilesUnder(bucket).isEmpty());
    }

    @Test
    void compactOnlyTouchesNoObjectStore(@TempDir Path root) {
        ParquetArchiveConfig config = config(root);
        captureADay(config);

        try (EndOfDayArchiver eod = new EndOfDayArchiver(config, EndOfDayPolicy.compactOnly(), null)) {
            EndOfDayArchiver.Result result = eod.run(DATE);
            assertTrue(result.filesMerged() > 0);
            assertEquals(0, result.upload().filesUploaded());
        }
        assertFalse(parquetFilesUnder(root).isEmpty());
    }

    @Test
    void anUploadPolicyWithoutADestinationIsRejectedAtConstruction(@TempDir Path root) {
        assertThrows(IllegalArgumentException.class,
                () -> new EndOfDayArchiver(config(root), EndOfDayPolicy.defaults(), null));
    }

    @Test
    void aFileThatFailsToUploadIsNotDeletedLocally(@TempDir Path root, @TempDir Path bucket) {
        ParquetArchiveConfig config = config(root);
        captureADay(config);

        List<String> reported = new ArrayList<>();
        FailingObjectStore store = new FailingObjectStore(new LocalDirectoryObjectStore(bucket), 2);
        EndOfDayArchiver.Result result;
        try (EndOfDayArchiver eod = new EndOfDayArchiver(config, EndOfDayPolicy.moveToObjectStore(),
                store, (operation, target, error) -> reported.add(operation))) {
            result = eod.run(DATE);
        }

        assertEquals(1, result.upload().failures());
        assertEquals(List.of("upload"), reported);
        assertEquals(result.upload().filesUploaded(), result.upload().filesDeleted());
        // Exactly the failed file is still on local disk, so re-running finishes the move.
        assertEquals(1, parquetFilesUnder(root).size());
    }

    @Test
    void aDayWithNoFilesIsANoOp(@TempDir Path root, @TempDir Path bucket) {
        ParquetArchiveConfig config = config(root);
        captureADay(config);

        try (EndOfDayArchiver eod = new EndOfDayArchiver(config, EndOfDayPolicy.moveToObjectStore(),
                new LocalDirectoryObjectStore(bucket))) {
            EndOfDayArchiver.Result result = eod.run(DATE.minusDays(3));
            assertEquals(0, result.upload().filesUploaded());
            assertEquals(0, result.filesMerged());
        }
        assertFalse(parquetFilesUnder(root).isEmpty(), "the day that does exist is untouched");
        assertEquals(List.of(), parquetFilesUnder(bucket));
    }

    @Test
    void objectStoreRefusesKeysThatEscapeItsRoot(@TempDir Path bucket) throws IOException {
        Path file = Files.writeString(bucket.resolve("source.bin"), "x");
        LocalDirectoryObjectStore store = new LocalDirectoryObjectStore(bucket);
        assertThrows(IllegalArgumentException.class, () -> store.put(file, "../escaped.parquet"));
        assertThrows(IllegalArgumentException.class, () -> store.put(file, "  "));
    }

    /** Wraps a store and fails the n-th put, to prove a partial failure is survivable. */
    private static final class FailingObjectStore implements ObjectStore {

        private final ObjectStore delegate;
        private final int failOnCall;
        private int calls;

        FailingObjectStore(ObjectStore delegate, int failOnCall) {
            this.delegate = delegate;
            this.failOnCall = failOnCall;
        }

        @Override
        public void put(Path localFile, String key) {
            if (++calls == failOnCall) {
                throw new ArchiveException("simulated upload failure for " + key);
            }
            delegate.put(localFile, key);
        }

        @Override
        public String describe() {
            return delegate.describe();
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
