package com.fix42.oms.parquet;

import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Merges a partition's many small Parquet files into few large ones.
 *
 * <p>Streaming capture optimises for freshness and produces a file per batch per partition —
 * by the close of a busy day, thousands of them. Every one costs a reader an open, a footer
 * parse and a schema reconcile before a single row is read, and none of them is large enough
 * for row-group statistics to skip anything. Compaction pays that cost once, offline, and is
 * what makes the same dataset usable for historical queries and cheap to hold on S3.
 *
 * <p><b>Ordering, and the one window it leaves.</b> For each partition directory the merged
 * output is written first, verified, moved into place, and only then are the source files
 * deleted. Between the move and the deletes a reader can see both — a brief window of
 * duplicate rows. The alternative ordering (delete first) risks losing data outright, so this
 * one is chosen deliberately: run compaction after the trading day closes, or accept that an
 * intraday query straddling the swap may double-count. Nothing is deleted unless the merged
 * row count matches the sources exactly (see {@link CompactionPolicy#verifyRowCounts()}).
 *
 * <p>Compaction is <b>idempotent and incremental</b>: an already-compacted file is just
 * another input to the next run, so re-running is safe and a partition that gained files
 * after the last run is merged again.
 *
 * <p>Single-writer: run one compactor per archive root at a time. Two concurrent compactors
 * over the same directory can both read a file that one of them then deletes.
 */
public final class ParquetCompactor implements Closeable {

    /** Staging table holding one row-number assignment when an output has to be split. */
    private static final String NUMBERED_TABLE = "compact_numbered";

    private final ParquetArchiveConfig config;
    private final CompactionPolicy policy;
    private final ArchiveErrorHandler errorHandler;
    private final DuckDbSessionPool sessionPool;
    private final DuckDbParquetWriter writer;

    /** What compaction did to one partition directory. */
    public record PartitionResult(Path directory,
                                  boolean compacted,
                                  int filesBefore,
                                  int filesAfter,
                                  long rows,
                                  long bytesBefore,
                                  long bytesAfter) {

        /** {@code true} if the partition was left untouched (too few files to be worth merging). */
        public boolean skipped() {
            return !compacted;
        }
    }

    /** What compaction did to one dataset for one trading day. */
    public record CompactionResult(Dataset dataset, LocalDate date, List<PartitionResult> partitions) {

        public CompactionResult {
            partitions = List.copyOf(partitions);
        }

        public int partitionsCompacted() {
            return (int) partitions.stream().filter(PartitionResult::compacted).count();
        }

        public int filesBefore() {
            return partitions.stream().mapToInt(PartitionResult::filesBefore).sum();
        }

        public int filesAfter() {
            return partitions.stream().mapToInt(PartitionResult::filesAfter).sum();
        }

        public long rows() {
            return partitions.stream().mapToLong(PartitionResult::rows).sum();
        }

        public long bytesBefore() {
            return partitions.stream().mapToLong(PartitionResult::bytesBefore).sum();
        }

        public long bytesAfter() {
            return partitions.stream().mapToLong(PartitionResult::bytesAfter).sum();
        }
    }

    public ParquetCompactor(ParquetArchiveConfig config) {
        this(config, CompactionPolicy.defaults(), ArchiveErrorHandler.LOGGING);
    }

    public ParquetCompactor(ParquetArchiveConfig config, CompactionPolicy policy, ArchiveErrorHandler errorHandler) {
        this.config = config;
        this.policy = (policy != null) ? policy : CompactionPolicy.defaults();
        this.errorHandler = (errorHandler != null) ? errorHandler : ArchiveErrorHandler.LOGGING;
        // One session: compaction is a housekeeping job, not a latency path, and a single
        // DuckDB instance already parallelises the scan and the write internally.
        this.sessionPool = new DuckDbSessionPool(config.duckDb(), 1);
        this.writer = new DuckDbParquetWriter(config.duckDb(), sessionPool);
    }

    /** Compact both datasets for {@code date}. */
    public List<CompactionResult> compactAll(LocalDate date) {
        List<CompactionResult> results = new ArrayList<>(Dataset.values().length);
        for (Dataset dataset : Dataset.values()) {
            results.add(compact(dataset, date));
        }
        return results;
    }

    /**
     * Compact every partition of {@code dataset} for {@code date}.
     *
     * <p>Scoped by {@link PartitionScheme#datePrefix(LocalDate)}. A custom scheme that does
     * not start with date segments has no prefix to scope by, so this walks the whole
     * dataset — correct, but it will also touch other days' partitions.
     */
    public CompactionResult compact(Dataset dataset, LocalDate date) {
        Path root = config.datasetRoot(dataset);
        String prefix = config.partitionScheme().datePrefix(date);
        Path scope = prefix.isEmpty() ? root : root.resolve(prefix);

        List<PartitionResult> results = new ArrayList<>();
        for (Path directory : partitionDirectories(scope)) {
            try {
                results.add(compactDirectory(dataset, directory));
            } catch (RuntimeException e) {
                // One unreadable partition must not abandon the rest of the day.
                ArchiveErrorHandler.deliver(errorHandler, "compact", directory, e);
            }
        }
        return new CompactionResult(dataset, date, results);
    }

    /**
     * Compact one partition directory (non-recursive).
     *
     * @return what was done, or a {@link PartitionResult#skipped() skipped} result when the
     *         directory holds fewer than {@link CompactionPolicy#minFilesToCompact()} files
     */
    public PartitionResult compactDirectory(Dataset dataset, Path directory) {
        List<Path> sources = parquetFilesIn(directory);
        long bytesBefore = totalBytes(sources);
        if (sources.size() < policy.minFilesToCompact()) {
            return new PartitionResult(directory, false, sources.size(), sources.size(),
                    0L, bytesBefore, bytesBefore);
        }

        DuckDbSession session = sessionPool.borrow();
        boolean healthy = false;
        List<Path> outputs = new ArrayList<>();
        try {
            String scan = readParquetSql(sources);
            long sourceRows = session.queryLong("SELECT count(*) FROM " + scan);
            int parts = (int) Math.max(1, (sourceRows + policy.maxRowsPerOutputFile() - 1)
                    / policy.maxRowsPerOutputFile());

            java.time.LocalDateTime stamp = Timestamps.utc(config.clock().millis());
            String source = (parts == 1) ? scan : materialiseNumbered(session, scan);
            long bytesAfter = 0L;
            try {
                for (int part = 0; part < parts; part++) {
                    // Never reuse an existing name: the previous run's compacted file is one
                    // of this run's SOURCES, and writing over it would then have it deleted
                    // along with them.
                    Path target = freeTarget(directory, config.datasetDirectory(dataset), stamp, part);
                    bytesAfter += writer.copyToParquet(session, selectSql(source, part, parts), target);
                    outputs.add(target);
                }
            } finally {
                if (parts > 1) {
                    session.execute("DROP TABLE IF EXISTS " + Sql.identifier(NUMBERED_TABLE));
                }
            }

            if (policy.verifyRowCounts()) {
                long writtenRows = session.queryLong("SELECT count(*) FROM " + readParquetSql(outputs));
                if (writtenRows != sourceRows) {
                    throw new ArchiveException("Compaction of " + directory + " produced " + writtenRows
                            + " rows from " + sourceRows + " source rows; sources left untouched");
                }
            }

            deleteAll(sources, outputs);
            healthy = true;
            outputs.clear(); // adopted, not garbage
            return new PartitionResult(directory, true, sources.size(), parts, sourceRows, bytesBefore, bytesAfter);
        } finally {
            // On any failure the half-written outputs go, and the sources — never yet
            // deleted at this point — remain the partition's only copy.
            for (Path orphan : outputs) {
                deleteQuietly(orphan);
            }
            if (healthy) {
                sessionPool.release(session);
            } else {
                sessionPool.discard(session);
            }
        }
    }

    /** Every directory at or under {@code scope} that directly contains Parquet files. */
    List<Path> partitionDirectories(Path scope) {
        if (!Files.isDirectory(scope)) {
            return List.of();
        }
        List<Path> directories = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(scope)) {
            walk.filter(Files::isDirectory)
                    .filter(dir -> !parquetFilesIn(dir).isEmpty())
                    .sorted()
                    .forEach(directories::add);
        } catch (IOException e) {
            throw new ArchiveException("Could not walk " + scope, new UncheckedIOException(e));
        }
        return directories;
    }

    /** Complete Parquet files directly in {@code directory}, in name order. */
    static List<Path> parquetFilesIn(Path directory) {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> list = Files.list(directory)) {
            return list.filter(Files::isRegularFile)
                    .filter(ParquetPaths::isParquetFile)
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        } catch (IOException e) {
            throw new ArchiveException("Could not list " + directory, new UncheckedIOException(e));
        }
    }

    @Override
    public void close() {
        sessionPool.close();
    }

    // ------------------------------------------------------------------
    // SQL
    // ------------------------------------------------------------------

    /**
     * {@code read_parquet([...], union_by_name=true)} over an explicit file list.
     *
     * <p>Explicit files rather than a glob so the set is pinned at the moment it was listed:
     * a batch flushed while compaction runs must not be silently merged in and then deleted.
     * {@code union_by_name} keeps a partition readable across a schema addition.
     */
    static String readParquetSql(List<Path> files) {
        StringBuilder sb = new StringBuilder("read_parquet([");
        for (int i = 0; i < files.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(Sql.literal(files.get(i)));
        }
        return sb.append("], union_by_name=true)").toString();
    }

    /**
     * A <b>total</b> ordering for the merged rows.
     *
     * <p>{@code ts} alone is not enough: a busy millisecond holds many rows, and ties leave
     * the order to the scan. That is merely untidy for a single output file and actively
     * wrong when the output is split, so {@code (writer_id, ingest_seq)} — unique by
     * construction — breaks every tie.
     */
    private String orderBy() {
        return policy.sortByTimestamp()
                ? "ORDER BY ts, writer_id, ingest_seq"
                : "ORDER BY writer_id, ingest_seq";
    }

    /**
     * Materialise the scan with its row numbers assigned <b>once</b>, for a split output.
     *
     * <p>Running the window function separately per part would re-derive the numbering each
     * time; any non-determinism between those runs duplicates some rows across parts and
     * drops others. One assignment, read N times, cannot do that.
     */
    private String materialiseNumbered(DuckDbSession session, String scan) {
        session.execute("CREATE OR REPLACE TABLE " + Sql.identifier(NUMBERED_TABLE)
                + " AS SELECT *, row_number() OVER (" + orderBy() + ") AS __rn FROM " + scan);
        return Sql.identifier(NUMBERED_TABLE);
    }

    private String selectSql(String source, int part, int parts) {
        if (parts == 1) {
            return "SELECT * FROM " + source + ' ' + orderBy();
        }
        long from = (long) part * policy.maxRowsPerOutputFile();
        long to = from + policy.maxRowsPerOutputFile();
        return "SELECT * EXCLUDE (__rn) FROM " + source
                + " WHERE __rn > " + from + " AND __rn <= " + to + " ORDER BY __rn";
    }

    /** The first {@code <dataset>-compact-<stamp>-<part>[-n].parquet} name not already taken. */
    private static Path freeTarget(Path directory, String datasetName, java.time.LocalDateTime stamp, int part) {
        Path candidate = directory.resolve(ParquetPaths.compactedFileName(datasetName, stamp, part));
        int attempt = 1;
        while (Files.exists(candidate)) {
            candidate = directory.resolve(
                    ParquetPaths.compactedFileName(datasetName, stamp, part, attempt++));
        }
        return candidate;
    }

    // ------------------------------------------------------------------
    // filesystem helpers
    // ------------------------------------------------------------------

    private static long totalBytes(List<Path> files) {
        long total = 0L;
        for (Path file : files) {
            try {
                total += Files.size(file);
            } catch (IOException e) {
                // Size is reporting only; a file that vanished contributes nothing.
            }
        }
        return total;
    }

    private void deleteAll(List<Path> files, List<Path> keep) {
        for (Path file : files) {
            if (keep.contains(file)) {
                continue; // never delete something this run just wrote
            }
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                // The merged output is already in place, so this leaves duplicate rows in
                // the partition — loud, because a silent duplicate is a wrong query answer.
                ArchiveErrorHandler.deliver(errorHandler, "compact-delete", file, e);
            }
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            // Best effort cleanup of a failed compaction output.
        }
    }
}
