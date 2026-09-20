package com.bhawana.lms.service;

import java.net.URI;
import java.time.Duration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * M05: single construction point for the S3-compatible (Cloudflare R2) clients used by
 * document and report storage. Each storage service builds its own client from its own
 * endpoint/credential block — clients are never shared across different credential sets —
 * but the pool sizing and the explicit API call/attempt deadlines live here so the two
 * adapters cannot drift apart.
 *
 * <p>The returned client is a singleton per service and must be closed once at shutdown
 * ({@code @PreDestroy}), never per call: its Apache connection pool is what makes repeated
 * store/open/delete calls cheap. Response streams returned by {@code getObject} are closed
 * by the caller; closing a stream returns the pooled connection — it must NOT close this
 * client.
 */
final class R2S3ClientFactory {

    private R2S3ClientFactory() {
    }

    /**
     * The configuration an R2 client is built from. Both {@link DocumentStorageProperties.R2}
     * and {@link ReportStorageProperties.R2} implement this so credentials, endpoint and
     * timeouts stay per-storage-area (no accidental global client).
     */
    interface R2ClientConfiguration {

        String getEndpoint();

        String getAccessKey();

        String getSecretKey();

        String getRegion();

        boolean isConfigured();

        /** Total wall-clock budget for one API call including all retries and body transfer. */
        Duration getApiCallTimeout();

        /** Budget for a single attempt, including streaming the request/response body. */
        Duration getApiCallAttemptTimeout();

        /** TCP connect (and pool connection-acquisition) deadline. */
        Duration getConnectionTimeout();

        /** Socket-level stall deadline: no bytes in either direction for this long aborts. */
        Duration getSocketTimeout();

        /** Upper bound on pooled HTTP connections to the endpoint. */
        int getMaxConnections();
    }

    static S3Client build(R2ClientConfiguration r2) {
        Duration apiCallTimeout = r2.getApiCallTimeout();
        Duration apiCallAttemptTimeout = r2.getApiCallAttemptTimeout();
        if (apiCallTimeout != null && apiCallAttemptTimeout != null
                && apiCallAttemptTimeout.compareTo(apiCallTimeout) > 0) {
            throw new IllegalStateException(
                    "R2 storage api-call-attempt-timeout (" + apiCallAttemptTimeout
                            + ") must not exceed api-call-timeout (" + apiCallTimeout + ")."
            );
        }
        return S3Client.builder()
                .endpointOverride(URI.create(r2.getEndpoint()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(r2.getAccessKey(), r2.getSecretKey())))
                .region(Region.of(r2.getRegion()))
                .forcePathStyle(true)
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallTimeout(apiCallTimeout)
                        .apiCallAttemptTimeout(apiCallAttemptTimeout)
                        .build())
                .httpClient(ApacheHttpClient.builder()
                        .maxConnections(r2.getMaxConnections())
                        .connectionTimeout(r2.getConnectionTimeout())
                        .connectionAcquisitionTimeout(r2.getConnectionTimeout())
                        .socketTimeout(r2.getSocketTimeout())
                        .build())
                .build();
    }
}
