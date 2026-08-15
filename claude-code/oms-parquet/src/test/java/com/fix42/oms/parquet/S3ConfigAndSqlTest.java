package com.fix42.oms.parquet;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The S3 destination is the one part of the archive that cannot be exercised end to end in a
 * unit test, so the statements it generates are asserted directly. A wrong {@code URL_STYLE}
 * or a missing {@code PROVIDER} shows up here rather than at 3am against a real endpoint.
 */
class S3ConfigAndSqlTest {

    @Test
    void keysAreBuiltUnderTheConfiguredPrefix() {
        S3Config s3 = S3Config.of("oms-archive", "prod/eu", "eu-west-1");
        assertEquals("s3://oms-archive/prod/eu/fix_messages/2026/08/04/ACC1/IBM/f.parquet",
                s3.urlFor("fix_messages/2026/08/04/ACC1/IBM/f.parquet"));
    }

    @Test
    void prefixSlashesAreNormalised() {
        assertEquals("s3://b/p/k", new S3Config("b", "/p/", null, null, true, false, null, null, null).urlFor("/k"));
        assertEquals("s3://b/k", S3Config.of("b", "", null).urlFor("k"));
        assertEquals("s3://b/", S3Config.of("b", "", null).urlFor(""));
    }

    @Test
    void bucketIsRequired() {
        assertThrows(IllegalArgumentException.class, () -> S3Config.of(" ", "p", "us-east-1"));
    }

    @Test
    void halfSpecifiedCredentialsAreRejected() {
        // A key id with no secret silently falls back to the credential chain otherwise —
        // which then fails with an authentication error nobody can trace to the config.
        assertThrows(IllegalArgumentException.class,
                () -> S3Config.of("b", "p", "us-east-1").withCredentials("AKIA", null, null));
    }

    @Test
    void defaultIsTheAmbientCredentialChain() {
        S3Config s3 = S3Config.of("oms-archive", "prod", "us-east-1");
        assertTrue(s3.usesCredentialChain());

        String sql = DuckDbS3ObjectStore.createSecretSql(s3, DuckDbS3ObjectStore.SECRET_NAME);
        assertEquals("CREATE OR REPLACE SECRET \"oms_parquet_s3\" "
                + "(TYPE S3, PROVIDER credential_chain, REGION 'us-east-1')", sql);
    }

    @Test
    void explicitCredentialsProduceAConfigSecret() {
        S3Config s3 = S3Config.of("oms-archive", "prod", "us-east-1")
                .withCredentials("AKIAEXAMPLE", "s3cr3t", "token-123");
        assertFalse(s3.usesCredentialChain());

        String sql = DuckDbS3ObjectStore.createSecretSql(s3, DuckDbS3ObjectStore.SECRET_NAME);
        assertTrue(sql.contains("KEY_ID 'AKIAEXAMPLE'"), sql);
        assertTrue(sql.contains("SECRET 's3cr3t'"), sql);
        assertTrue(sql.contains("SESSION_TOKEN 'token-123'"), sql);
        assertFalse(sql.contains("credential_chain"), sql);
    }

    @Test
    void minioStyleEndpointGetsPathStyleAndSslFlags() {
        S3Config s3 = S3Config.of("oms-archive", "", "us-east-1")
                .withEndpoint("minio.internal:9000", false, true)
                .withCredentials("minio", "minio123", null);

        String sql = DuckDbS3ObjectStore.createSecretSql(s3, "s");
        assertTrue(sql.contains("ENDPOINT 'minio.internal:9000'"), sql);
        assertTrue(sql.contains("USE_SSL false"), sql);
        assertTrue(sql.contains("URL_STYLE 'path'"), sql);
        assertFalse(sql.contains("SESSION_TOKEN"), sql);
    }

    @Test
    void uploadRewritesTheFileThroughDuckDb() {
        String sql = DuckDbS3ObjectStore.copySql(Path.of("/data/f.parquet"),
                "s3://bucket/prod/f.parquet", "FORMAT PARQUET, COMPRESSION 'zstd', ROW_GROUP_SIZE 100000");
        assertEquals("COPY (SELECT * FROM read_parquet('/data/f.parquet')) TO 's3://bucket/prod/f.parquet' "
                + "(FORMAT PARQUET, COMPRESSION 'zstd', ROW_GROUP_SIZE 100000)", sql);
    }

    @Test
    void quotesInPathsCannotTerminateTheSqlLiteral() {
        // Paths come from configuration; an embedded quote must be escaped, not injected.
        String sql = DuckDbS3ObjectStore.copySql(Path.of("/data/o'brien.parquet"), "s3://b/k'x", "FORMAT PARQUET");
        assertTrue(sql.contains("read_parquet('/data/o''brien.parquet')"), sql);
        assertTrue(sql.contains("TO 's3://b/k''x'"), sql);
    }
}
