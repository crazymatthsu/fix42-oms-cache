package com.fix42.oms.parquet;

import java.nio.file.Path;

/**
 * How the embedded DuckDB engine that writes and compacts the Parquet files is configured.
 *
 * <p>Every writer thread gets its <em>own</em> in-memory DuckDB instance (see
 * {@code DuckDbSessionPool}), so these limits are <b>per session</b>: a two-thread archive
 * with {@code memoryLimit="1GB"} can use 2 GiB. They are set deliberately low because this
 * engine is a co-tenant inside an OMS process, not the process's reason for existing.
 *
 * @param memoryLimit   DuckDB {@code memory_limit}, e.g. {@code "1GB"}; {@code null} keeps
 *                      DuckDB's own default (80% of system RAM — almost never what an
 *                      embedded writer wants)
 * @param threads       DuckDB {@code threads} per session; {@code 0} keeps DuckDB's default
 * @param tempDirectory spill directory for operations that exceed {@code memoryLimit}
 *                      (compaction of a large partition, mainly); {@code null} = DuckDB default
 * @param compression   Parquet codec for written and compacted files
 * @param rowGroupSize  rows per Parquet row group. Row groups are the unit of statistics and
 *                      of parallel scan: too small wastes footer space and starves readers of
 *                      pushdown, too large forces a reader to materialise more than it needs.
 *                      100k suits the batch sizes here (a streamed file is usually one row group).
 */
public record DuckDbConfig(String memoryLimit,
                           int threads,
                           Path tempDirectory,
                           Compression compression,
                           long rowGroupSize) {

    public DuckDbConfig {
        if (threads < 0) {
            throw new IllegalArgumentException("threads must be >= 0");
        }
        if (rowGroupSize < 1024) {
            throw new IllegalArgumentException("rowGroupSize must be >= 1024");
        }
        if (compression == null) {
            compression = Compression.ZSTD;
        }
        if (memoryLimit != null && memoryLimit.isBlank()) {
            memoryLimit = null;
        }
    }

    /** Defaults: 1 GiB per session, 2 threads, ZSTD, 100k-row groups. */
    public static DuckDbConfig defaults() {
        return new DuckDbConfig("1GB", 2, null, Compression.ZSTD, 100_000L);
    }

    public DuckDbConfig withMemoryLimit(String limit) {
        return new DuckDbConfig(limit, threads, tempDirectory, compression, rowGroupSize);
    }

    public DuckDbConfig withThreads(int n) {
        return new DuckDbConfig(memoryLimit, n, tempDirectory, compression, rowGroupSize);
    }

    public DuckDbConfig withTempDirectory(Path dir) {
        return new DuckDbConfig(memoryLimit, threads, dir, compression, rowGroupSize);
    }

    public DuckDbConfig withCompression(Compression c) {
        return new DuckDbConfig(memoryLimit, threads, tempDirectory, c, rowGroupSize);
    }

    public DuckDbConfig withRowGroupSize(long rows) {
        return new DuckDbConfig(memoryLimit, threads, tempDirectory, compression, rows);
    }
}
