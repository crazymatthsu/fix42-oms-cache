package com.fix42.oms.parquet;

/**
 * How aggressively {@link ParquetCompactor} merges a partition's files.
 *
 * @param minFilesToCompact     leave a partition alone below this many files. A partition
 *                              that is already one file gains nothing from being rewritten,
 *                              and rewriting it would churn S3 for no reader benefit.
 * @param maxRowsPerOutputFile  split the merged output once it exceeds this many rows, so a
 *                              busy (account, symbol) day does not become one file a reader
 *                              cannot parallelise across.
 * @param sortByTimestamp       order merged rows by {@code ts}. Costs a sort (which may spill
 *                              to {@link DuckDbConfig#tempDirectory()}) and buys tight
 *                              row-group min/max statistics, which is what lets a reader skip
 *                              row groups for a time-range predicate — the single most
 *                              valuable property of a historical Parquet file.
 * @param verifyRowCounts       count the merged output and compare it with the sources before
 *                              deleting anything. One extra scan for the guarantee that
 *                              compaction never silently loses rows; leave it on.
 */
public record CompactionPolicy(int minFilesToCompact,
                               long maxRowsPerOutputFile,
                               boolean sortByTimestamp,
                               boolean verifyRowCounts) {

    public CompactionPolicy {
        if (minFilesToCompact < 2) {
            throw new IllegalArgumentException("minFilesToCompact must be >= 2");
        }
        if (maxRowsPerOutputFile < 1) {
            throw new IllegalArgumentException("maxRowsPerOutputFile must be >= 1");
        }
    }

    /** Defaults: merge from 2 files, split past 5M rows, sort by time, verify counts. */
    public static CompactionPolicy defaults() {
        return new CompactionPolicy(2, 5_000_000L, true, true);
    }

    public CompactionPolicy withMinFilesToCompact(int n) {
        return new CompactionPolicy(n, maxRowsPerOutputFile, sortByTimestamp, verifyRowCounts);
    }

    public CompactionPolicy withMaxRowsPerOutputFile(long rows) {
        return new CompactionPolicy(minFilesToCompact, rows, sortByTimestamp, verifyRowCounts);
    }

    public CompactionPolicy withSortByTimestamp(boolean on) {
        return new CompactionPolicy(minFilesToCompact, maxRowsPerOutputFile, on, verifyRowCounts);
    }

    public CompactionPolicy withVerifyRowCounts(boolean on) {
        return new CompactionPolicy(minFilesToCompact, maxRowsPerOutputFile, sortByTimestamp, on);
    }
}
