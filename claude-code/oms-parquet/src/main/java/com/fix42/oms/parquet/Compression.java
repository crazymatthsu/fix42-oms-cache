package com.fix42.oms.parquet;

/**
 * Parquet compression codec.
 *
 * <p>{@link #ZSTD} is the default: it compresses FIX data (highly repetitive symbols,
 * accounts and identifiers) substantially better than Snappy at a decompression speed that
 * still saturates a scan, which matters most for the historical queries that read whole
 * days off S3. Choose {@link #SNAPPY} if a reader in the estate predates Zstd support, or
 * {@link #UNCOMPRESSED} when the archive lands on a compressing filesystem.
 */
public enum Compression {

    ZSTD("zstd"),
    SNAPPY("snappy"),
    GZIP("gzip"),
    LZ4("lz4"),
    UNCOMPRESSED("uncompressed");

    private final String duckDbName;

    Compression(String duckDbName) {
        this.duckDbName = duckDbName;
    }

    /** The codec name DuckDB's {@code COPY ... (COMPRESSION ...)} expects. */
    public String duckDbName() {
        return duckDbName;
    }
}
