package com.fix42.oms.parquet;

/**
 * Unchecked failure from the Parquet archive — a DuckDB error, a filesystem error, or an
 * object-store upload failure.
 *
 * <p>Thrown from the explicitly-invoked operations ({@code flush()}, compaction, upload) so
 * a caller that asked for the work learns it failed. It is <b>not</b> thrown from the
 * capture path: {@code writeRawMessage} and the state listener route background flush
 * failures to {@link ArchiveErrorHandler} instead, because failing an ingest thread over an
 * analytics sink would trade a lost Parquet row for a stalled order feed.
 */
public class ArchiveException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ArchiveException(String message) {
        super(message);
    }

    public ArchiveException(String message, Throwable cause) {
        super(message, cause);
    }
}
