package com.fix42.oms.parquet;

/**
 * S3 destination for the overnight move.
 *
 * <p>Credentials are optional and default to {@code credential_chain}, which is what a
 * deployment on EC2/EKS should use — the instance or pod role, no secrets in configuration
 * at all. The explicit key/secret fields exist for MinIO, LocalStack and the occasional
 * on-prem gateway; if you set them, keep them out of source control.
 *
 * @param bucket           destination bucket, required
 * @param prefix           key prefix under the bucket, e.g. {@code oms/prod}; may be empty
 * @param region           AWS region, e.g. {@code us-east-1}
 * @param endpoint         S3-compatible endpoint as {@code host[:port]} with <b>no scheme</b>
 *                         (DuckDB's own format); {@code null} for AWS
 * @param useSsl           HTTPS to the endpoint; only turn off for a local MinIO
 * @param pathStyleAccess  {@code bucket/key} paths instead of virtual-host style. Required by
 *                         most S3-compatible servers, wrong for AWS itself.
 * @param accessKeyId      explicit key id, or {@code null} to use the credential chain
 * @param secretAccessKey  explicit secret, or {@code null}
 * @param sessionToken     explicit session token for temporary credentials, or {@code null}
 */
public record S3Config(String bucket,
                       String prefix,
                       String region,
                       String endpoint,
                       boolean useSsl,
                       boolean pathStyleAccess,
                       String accessKeyId,
                       String secretAccessKey,
                       String sessionToken) {

    public S3Config {
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalArgumentException("bucket must not be blank");
        }
        bucket = bucket.trim();
        prefix = normalisePrefix(prefix);
        region = blankToNull(region);
        endpoint = blankToNull(endpoint);
        accessKeyId = blankToNull(accessKeyId);
        secretAccessKey = blankToNull(secretAccessKey);
        sessionToken = blankToNull(sessionToken);
        if ((accessKeyId == null) != (secretAccessKey == null)) {
            throw new IllegalArgumentException(
                    "accessKeyId and secretAccessKey must be set together, or neither");
        }
    }

    /** AWS with the ambient credential chain: {@code s3://bucket/prefix}. */
    public static S3Config of(String bucket, String prefix, String region) {
        return new S3Config(bucket, prefix, region, null, true, false, null, null, null);
    }

    /** {@code true} when no explicit key was configured, so DuckDB should use its credential chain. */
    public boolean usesCredentialChain() {
        return accessKeyId == null;
    }

    /** Full {@code s3://} URL for a key relative to {@link #prefix()}. */
    public String urlFor(String relativeKey) {
        String key = (relativeKey == null) ? "" : relativeKey.replace('\\', '/');
        while (key.startsWith("/")) {
            key = key.substring(1);
        }
        return "s3://" + bucket + '/' + (prefix.isEmpty() ? key : prefix + '/' + key);
    }

    public S3Config withEndpoint(String endpoint, boolean useSsl, boolean pathStyleAccess) {
        return new S3Config(bucket, prefix, region, endpoint, useSsl, pathStyleAccess,
                accessKeyId, secretAccessKey, sessionToken);
    }

    public S3Config withCredentials(String accessKeyId, String secretAccessKey, String sessionToken) {
        return new S3Config(bucket, prefix, region, endpoint, useSsl, pathStyleAccess,
                accessKeyId, secretAccessKey, sessionToken);
    }

    private static String normalisePrefix(String prefix) {
        if (prefix == null) {
            return "";
        }
        String p = prefix.trim().replace('\\', '/');
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        while (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }
}
