package com.fix42.oms.parquet;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * In-memory row batching, one batch per partition directory, for a single {@link Dataset}.
 *
 * <p>This is the piece that decides how big Parquet files are, and therefore how well the
 * archive queries. Rows accumulate per partition and are flushed when the batch hits
 * {@link BatchPolicy#maxRowsPerFile()}, when it ages past
 * {@link BatchPolicy#maxBatchAgeMillis()}, when total buffered rows exceed
 * {@link BatchPolicy#maxBufferedRows()}, or on an explicit {@link #flushAll()}.
 *
 * <p><b>The ingest thread does as little as possible.</b> {@link #add} takes a lock, appends
 * to an {@code ArrayList} and returns; turning a batch into a file happens on a writer
 * thread. That matters because the state-change path runs inside the cache's write critical
 * section — a DuckDB {@code COPY} there would stall every order in the process. Under
 * sustained overload the executor's caller-runs policy pushes the flush back onto the
 * calling thread, which is intentional: slowing ingest to writer speed is the only
 * alternative to unbounded memory growth or silently dropping rows.
 */
final class BatchingRowSink {

    private final Dataset dataset;
    private final ParquetArchiveConfig config;
    private final Path datasetRoot;
    private final DuckDbParquetWriter writer;
    private final ArchiveCounters counters;
    private final ArchiveErrorHandler errorHandler;
    private final Executor flushExecutor;

    private final ReentrantLock lock = new ReentrantLock();
    /** Keyed by partition directory, relative to {@link #datasetRoot}. */
    private final Map<String, Batch> batches = new HashMap<>();

    private final Object flushMonitor = new Object();
    private int pendingFlushes;

    private final AtomicLong fileSequence = new AtomicLong();

    BatchingRowSink(Dataset dataset,
                    ParquetArchiveConfig config,
                    DuckDbParquetWriter writer,
                    ArchiveCounters counters,
                    ArchiveErrorHandler errorHandler,
                    Executor flushExecutor) {
        this.dataset = dataset;
        this.config = config;
        this.datasetRoot = config.datasetRoot(dataset);
        this.writer = writer;
        this.counters = counters;
        this.errorHandler = errorHandler;
        this.flushExecutor = flushExecutor;
    }

    private static final class Batch {
        final String relativeDirectory;
        final long firstRowMillis;
        final List<Object[]> rows = new ArrayList<>();

        Batch(String relativeDirectory, long firstRowMillis) {
            this.relativeDirectory = relativeDirectory;
            this.firstRowMillis = firstRowMillis;
        }
    }

    /** Buffer one row for {@code key}, flushing batches that the policy says are due. */
    void add(PartitionKey key, Object[] row) {
        dataset.checkRow(row);
        String directory = config.partitionScheme().relativeDirectory(key);
        long now = config.clock().millis();

        List<Batch> due = new ArrayList<>(2);
        lock.lock();
        try {
            Batch batch = batches.computeIfAbsent(directory, d -> new Batch(d, now));
            batch.rows.add(row);
            counters.rowAccepted();

            if (batch.rows.size() >= config.batchPolicy().maxRowsPerFile()) {
                batches.remove(directory);
                due.add(batch);
            }
            // Memory guard: many small partitions can hold far more in aggregate than any
            // single batch ever reaches, so evict the biggest until back under the cap.
            while (counters.rowsBuffered() > config.batchPolicy().maxBufferedRows()) {
                Batch largest = removeLargestLocked();
                if (largest == null) {
                    break; // everything is already queued for a writer; backpressure applies there
                }
                due.add(largest);
            }
        } finally {
            lock.unlock();
        }
        for (Batch batch : due) {
            submit(batch);
        }
    }

    /** Flush batches whose first row is older than {@link BatchPolicy#maxBatchAgeMillis()}. */
    void flushAged(long nowMillis) {
        long maxAge = config.batchPolicy().maxBatchAgeMillis();
        if (maxAge <= 0) {
            return;
        }
        List<Batch> due = new ArrayList<>();
        lock.lock();
        try {
            batches.values().removeIf(batch -> {
                if (nowMillis - batch.firstRowMillis >= maxAge) {
                    due.add(batch);
                    return true;
                }
                return false;
            });
        } finally {
            lock.unlock();
        }
        for (Batch batch : due) {
            submit(batch);
        }
    }

    /** Flush every buffered batch and block until all in-flight writes have finished. */
    void flushAll() {
        List<Batch> due;
        lock.lock();
        try {
            due = new ArrayList<>(batches.values());
            batches.clear();
        } finally {
            lock.unlock();
        }
        for (Batch batch : due) {
            submit(batch);
        }
        awaitQuiescence();
    }

    /** Block until no flush is in flight. */
    void awaitQuiescence() {
        synchronized (flushMonitor) {
            while (pendingFlushes > 0) {
                try {
                    flushMonitor.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private Batch removeLargestLocked() {
        String largestKey = null;
        int largestSize = 0;
        for (Map.Entry<String, Batch> e : batches.entrySet()) {
            if (e.getValue().rows.size() > largestSize) {
                largestSize = e.getValue().rows.size();
                largestKey = e.getKey();
            }
        }
        return (largestKey == null) ? null : batches.remove(largestKey);
    }

    private void submit(Batch batch) {
        if (batch.rows.isEmpty()) {
            return;
        }
        synchronized (flushMonitor) {
            pendingFlushes++;
        }
        try {
            flushExecutor.execute(() -> runFlush(batch));
        } catch (RejectedExecutionException e) {
            // The archive is closing. Writing inline is strictly better than dropping rows
            // that the caller already believes were captured.
            runFlush(batch);
        }
    }

    private void runFlush(Batch batch) {
        Path target = null;
        try {
            target = datasetRoot.resolve(batch.relativeDirectory)
                    .resolve(ParquetPaths.batchFileName(config.datasetDirectory(dataset), config.writerId(),
                            Timestamps.utc(config.clock().millis()), fileSequence.incrementAndGet()));
            DuckDbParquetWriter.WriteResult result = writer.write(dataset, batch.rows, target);
            counters.batchWritten(result.rows(), result.bytes());
        } catch (RuntimeException e) {
            // A failed batch is lost, not retried: its rows are already gone from the buffer
            // and re-queueing them behind a persistent failure (a full disk, say) would just
            // trade lost rows for unbounded memory. The handler and the counters are the
            // record that it happened.
            counters.batchFailed(batch.rows.size());
            ArchiveErrorHandler.deliver(errorHandler, "flush", target, e);
        } finally {
            synchronized (flushMonitor) {
                pendingFlushes--;
                flushMonitor.notifyAll();
            }
        }
    }

}
