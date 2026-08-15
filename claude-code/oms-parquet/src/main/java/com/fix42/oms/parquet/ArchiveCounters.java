package com.fix42.oms.parquet;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Mutable counters behind {@link ArchiveStats}, shared by every dataset sink of one archive.
 *
 * <p>{@link LongAdder} for the write-heavy totals (contended by ingest and writer threads
 * alike) and an {@link AtomicLong} for {@code rowsBuffered}, which has to go both up and
 * down and be read exactly.
 */
final class ArchiveCounters {

    private final LongAdder rowsAccepted = new LongAdder();
    private final LongAdder rowsWritten = new LongAdder();
    private final LongAdder rowsDropped = new LongAdder();
    private final LongAdder filesWritten = new LongAdder();
    private final LongAdder bytesWritten = new LongAdder();
    private final LongAdder flushFailures = new LongAdder();
    private final AtomicLong rowsBuffered = new AtomicLong();

    void rowAccepted() {
        rowsAccepted.increment();
        rowsBuffered.incrementAndGet();
    }

    void batchWritten(long rows, long bytes) {
        rowsWritten.add(rows);
        filesWritten.increment();
        bytesWritten.add(bytes);
        rowsBuffered.addAndGet(-rows);
    }

    void batchFailed(long rows) {
        flushFailures.increment();
        rowsDropped.add(rows);
        rowsBuffered.addAndGet(-rows);
    }

    long rowsBuffered() {
        return rowsBuffered.get();
    }

    ArchiveStats snapshot() {
        return new ArchiveStats(rowsAccepted.sum(), rowsWritten.sum(), rowsDropped.sum(),
                filesWritten.sum(), bytesWritten.sum(), flushFailures.sum(), rowsBuffered.get());
    }
}
