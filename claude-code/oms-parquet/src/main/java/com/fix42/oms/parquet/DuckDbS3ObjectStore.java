package com.fix42.oms.parquet;

import java.nio.file.Path;

/**
 * An {@link ObjectStore} that writes to S3 through DuckDB's {@code httpfs} extension —
 * no AWS SDK on the classpath, the same engine that wrote the files moves them.
 *
 * <p><b>It re-encodes rather than copies.</b> DuckDB has no raw object PUT, so an upload is
 * {@code COPY (SELECT * FROM read_parquet('<local>')) TO 's3://...'}. The rows are identical
 * and the file is written with this archive's compression and row-group settings, but it is
 * not byte-identical to the local file, so a checksum comparison between the two will differ.
 * Where byte-for-byte fidelity is required (a WORM/regulatory copy), implement
 * {@link ObjectStore} over the AWS SDK instead — that is exactly why the SPI exists.
 *
 * <p><b>First use needs the extension.</b> {@code INSTALL httpfs} downloads from DuckDB's
 * extension repository unless the extension is already present. In a locked-down network,
 * pre-install it into the DuckDB extension directory and construct with
 * {@code installExtension = false}.
 *
 * <p>Thread-safe by serialising uploads on this instance; it holds one DuckDB session.
 */
public final class DuckDbS3ObjectStore implements ObjectStore {

    /** Name of the DuckDB secret this store creates; scoped so it cannot clash with a user's. */
    static final String SECRET_NAME = "oms_parquet_s3";

    private final S3Config s3;
    private final DuckDbConfig duckDb;
    private final boolean installExtension;

    private DuckDbSession session;
    private boolean closed;

    public DuckDbS3ObjectStore(S3Config s3, DuckDbConfig duckDb) {
        this(s3, duckDb, true);
    }

    public DuckDbS3ObjectStore(S3Config s3, DuckDbConfig duckDb, boolean installExtension) {
        if (s3 == null) {
            throw new IllegalArgumentException("s3 config must not be null");
        }
        this.s3 = s3;
        this.duckDb = (duckDb != null) ? duckDb : DuckDbConfig.defaults();
        this.installExtension = installExtension;
    }

    @Override
    public synchronized void put(Path localFile, String key) {
        if (closed) {
            throw new ArchiveException("Object store is closed");
        }
        DuckDbSession active = session();
        String url = s3.urlFor(key);
        try {
            active.execute(copySql(localFile, url, copyOptions()));
        } catch (ArchiveException e) {
            // A failed statement can leave the session mid-transaction; drop it so the next
            // upload starts from a clean instance (and re-creates the secret).
            active.close();
            session = null;
            throw new ArchiveException("Could not upload " + localFile + " to " + url, e);
        }
    }

    @Override
    public String describe() {
        return s3.urlFor("");
    }

    @Override
    public synchronized void close() {
        closed = true;
        if (session != null) {
            session.close();
            session = null;
        }
    }

    private DuckDbSession session() {
        if (session == null) {
            DuckDbSession fresh = DuckDbSession.open(duckDb);
            try {
                if (installExtension) {
                    fresh.execute("INSTALL httpfs");
                }
                fresh.execute("LOAD httpfs");
                fresh.execute(createSecretSql(s3, SECRET_NAME));
            } catch (RuntimeException e) {
                fresh.close();
                throw new ArchiveException("Could not prepare DuckDB for S3 access"
                        + (installExtension ? " (INSTALL httpfs needs network access on first use;"
                        + " pre-install it and construct with installExtension=false in an offline"
                        + " environment)" : ""), e);
            }
            session = fresh;
        }
        return session;
    }

    private String copyOptions() {
        return "FORMAT PARQUET, COMPRESSION " + Sql.literal(duckDb.compression().duckDbName())
                + ", ROW_GROUP_SIZE " + duckDb.rowGroupSize();
    }

    /** {@code COPY (SELECT * FROM read_parquet('<local>')) TO '<url>' (<options>)}. */
    static String copySql(Path localFile, String url, String options) {
        return "COPY (SELECT * FROM read_parquet(" + Sql.literal(localFile) + ")) TO "
                + Sql.literal(url) + " (" + options + ")";
    }

    /**
     * The {@code CREATE OR REPLACE SECRET} that configures DuckDB's S3 access.
     *
     * <p>Kept as a pure function of the config so it can be asserted in a unit test without a
     * bucket: the shape of this statement is the whole S3 contract, and getting {@code
     * URL_STYLE} or {@code PROVIDER} wrong fails at 3am against a real endpoint otherwise.
     */
    static String createSecretSql(S3Config s3, String secretName) {
        StringBuilder sb = new StringBuilder("CREATE OR REPLACE SECRET ")
                .append(Sql.identifier(secretName)).append(" (TYPE S3");
        if (s3.usesCredentialChain()) {
            sb.append(", PROVIDER credential_chain");
        } else {
            sb.append(", KEY_ID ").append(Sql.literal(s3.accessKeyId()));
            sb.append(", SECRET ").append(Sql.literal(s3.secretAccessKey()));
            if (s3.sessionToken() != null) {
                sb.append(", SESSION_TOKEN ").append(Sql.literal(s3.sessionToken()));
            }
        }
        if (s3.region() != null) {
            sb.append(", REGION ").append(Sql.literal(s3.region()));
        }
        if (s3.endpoint() != null) {
            sb.append(", ENDPOINT ").append(Sql.literal(s3.endpoint()));
            sb.append(", USE_SSL ").append(s3.useSsl());
        }
        if (s3.pathStyleAccess()) {
            sb.append(", URL_STYLE 'path'");
        }
        return sb.append(')').toString();
    }
}
