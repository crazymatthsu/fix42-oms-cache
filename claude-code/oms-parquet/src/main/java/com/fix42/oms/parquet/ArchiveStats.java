package com.fix42.oms.parquet;

/**
 * A point-in-time snapshot of an archive's counters.
 *
 * <p>{@code rowsAccepted - rowsWritten - rowsDropped} is what is still in memory; a
 * {@code rowsDropped} that is not zero means Parquet output is incomplete and the archive
 * no longer matches the cache.
 *
 * @param rowsAccepted   rows handed to the archive
 * @param rowsWritten    rows durably written into a Parquet file
 * @param rowsDropped    rows lost to a failed flush
 * @param filesWritten   Parquet files produced by streaming flushes
 * @param bytesWritten   total size of those files
 * @param flushFailures  batch flushes that threw
 * @param rowsBuffered   rows currently held in memory (batched or queued for a writer)
 */
public record ArchiveStats(long rowsAccepted,
                           long rowsWritten,
                           long rowsDropped,
                           long filesWritten,
                           long bytesWritten,
                           long flushFailures,
                           long rowsBuffered) {

    /** {@code true} when every accepted row has been written and none were lost. */
    public boolean isFullyFlushed() {
        return rowsBuffered == 0 && rowsDropped == 0 && rowsAccepted == rowsWritten;
    }
}
