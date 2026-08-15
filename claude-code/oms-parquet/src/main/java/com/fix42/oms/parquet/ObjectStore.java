package com.fix42.oms.parquet;

import java.io.Closeable;
import java.nio.file.Path;

/**
 * Where the overnight mover puts a local Parquet file.
 *
 * <p>An SPI rather than a hard dependency on one client: this module ships a DuckDB-backed
 * S3 implementation ({@link DuckDbS3ObjectStore}) and a filesystem one
 * ({@link LocalDirectoryObjectStore}), and a deployment that already has an AWS SDK client,
 * a transfer manager, or a different object store entirely implements this in a few lines
 * instead of inheriting ours.
 */
public interface ObjectStore extends Closeable {

    /**
     * Store {@code localFile} under {@code key}, replacing anything already there.
     *
     * @param key {@code /}-separated object key, no leading slash
     * @throws ArchiveException if the upload fails
     */
    void put(Path localFile, String key);

    /** Destination description for logs and results, e.g. {@code s3://bucket/prefix}. */
    String describe();

    @Override
    default void close() {
    }
}
