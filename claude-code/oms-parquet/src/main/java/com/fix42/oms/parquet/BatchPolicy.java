package com.fix42.oms.parquet;

/**
 * When an in-memory batch becomes a Parquet file.
 *
 * <p>This is the one knob that trades <b>intraday freshness</b> against <b>query
 * performance</b>, and both directions have a real cost. Flush often and an intraday reader
 * sees an order within seconds, but the day accumulates thousands of tiny files, each with
 * its own footer and none with useful row-group statistics — the classic small-file problem,
 * and the reason {@link ParquetCompactor} exists. Flush rarely and every file is a healthy
 * row group, but a fill can sit in memory for minutes and is lost if the process dies
 * (this archive is an analytics sink, not a durability mechanism — {@code :oms-persist}'s
 * journal is what makes a message recoverable).
 *
 * <p>Defaults target "a Deephaven intraday table lags by at most a minute": 50k rows or
 * 60 seconds per partition, whichever comes first.
 *
 * @param maxRowsPerFile        flush a partition's batch once it reaches this many rows
 * @param maxBatchAgeMillis     flush a partition's batch this long after its first row;
 *                              {@code 0} disables age-based flushing (size only)
 * @param flushCheckIntervalMillis how often the background thread looks for aged batches
 * @param maxBufferedRows       total rows held across all partitions before the largest
 *                              batch is force-flushed — the memory bound. A drop-copy feed
 *                              spread over many (account, symbol) pairs can otherwise hold
 *                              far more than {@code maxRowsPerFile} rows in aggregate.
 * @param writerThreads         threads that turn batches into files. Each owns a private
 *                              DuckDB instance, so more threads means more memory; 1 is
 *                              enough for typical drop-copy rates.
 * @param maxQueuedFlushes      pending flushes allowed before the submitting thread runs the
 *                              flush itself. That inline run is the backpressure: ingest
 *                              slows to writer speed instead of the queue growing without
 *                              bound.
 */
public record BatchPolicy(int maxRowsPerFile,
                          long maxBatchAgeMillis,
                          long flushCheckIntervalMillis,
                          int maxBufferedRows,
                          int writerThreads,
                          int maxQueuedFlushes) {

    public BatchPolicy {
        if (maxRowsPerFile < 1) {
            throw new IllegalArgumentException("maxRowsPerFile must be >= 1");
        }
        if (maxBatchAgeMillis < 0) {
            throw new IllegalArgumentException("maxBatchAgeMillis must be >= 0");
        }
        if (flushCheckIntervalMillis < 1) {
            throw new IllegalArgumentException("flushCheckIntervalMillis must be >= 1");
        }
        if (maxBufferedRows < maxRowsPerFile) {
            throw new IllegalArgumentException("maxBufferedRows (" + maxBufferedRows
                    + ") must be >= maxRowsPerFile (" + maxRowsPerFile + ")");
        }
        if (writerThreads < 1) {
            throw new IllegalArgumentException("writerThreads must be >= 1");
        }
        if (maxQueuedFlushes < 1) {
            throw new IllegalArgumentException("maxQueuedFlushes must be >= 1");
        }
    }

    /** Defaults: 50k rows or 60s per partition, 1M rows buffered, one writer thread. */
    public static BatchPolicy defaults() {
        return new BatchPolicy(50_000, 60_000L, 1_000L, 1_000_000, 1, 64);
    }

    /**
     * Low-latency preset: 5k rows or 5s. Produces many more files — pair it with
     * end-of-day (or hourly) compaction.
     */
    public static BatchPolicy lowLatency() {
        return new BatchPolicy(5_000, 5_000L, 500L, 500_000, 1, 128);
    }

    /** Bulk-load preset: 500k rows, no age flush — for replaying history, not live capture. */
    public static BatchPolicy bulkLoad() {
        return new BatchPolicy(500_000, 0L, 5_000L, 2_000_000, 2, 16);
    }

    public BatchPolicy withMaxRowsPerFile(int rows) {
        return new BatchPolicy(rows, maxBatchAgeMillis, flushCheckIntervalMillis, maxBufferedRows, writerThreads, maxQueuedFlushes);
    }

    public BatchPolicy withMaxBatchAgeMillis(long millis) {
        return new BatchPolicy(maxRowsPerFile, millis, flushCheckIntervalMillis, maxBufferedRows, writerThreads, maxQueuedFlushes);
    }

    public BatchPolicy withFlushCheckIntervalMillis(long millis) {
        return new BatchPolicy(maxRowsPerFile, maxBatchAgeMillis, millis, maxBufferedRows, writerThreads, maxQueuedFlushes);
    }

    public BatchPolicy withMaxBufferedRows(int rows) {
        return new BatchPolicy(maxRowsPerFile, maxBatchAgeMillis, flushCheckIntervalMillis, rows, writerThreads, maxQueuedFlushes);
    }

    public BatchPolicy withWriterThreads(int n) {
        return new BatchPolicy(maxRowsPerFile, maxBatchAgeMillis, flushCheckIntervalMillis, maxBufferedRows, n, maxQueuedFlushes);
    }

    public BatchPolicy withMaxQueuedFlushes(int n) {
        return new BatchPolicy(maxRowsPerFile, maxBatchAgeMillis, flushCheckIntervalMillis, maxBufferedRows, writerThreads, n);
    }
}
