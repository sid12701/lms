package com.bhawana.lms.support;

import java.net.URI;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;

/**
 * Test support that boots a single MinIO container and binds it to
 * {@code app.storage.reports.r2.*}. The bucket is created on first start.
 */
@Testcontainers(disabledWithoutDocker = true)
public abstract class MinioTestSupport {

    private static final String REPORTS_BUCKET = "lms-reports-test";
    private static final String ACCESS_KEY = "lms-test-access";
    private static final String SECRET_KEY = "lms-test-secret";

    @Container
    static final MinIOContainer MINIO = new MinIOContainer("minio/minio:RELEASE.2024-12-18T13-15-44Z")
            .withUserName(ACCESS_KEY)
            .withPassword(SECRET_KEY);

    @DynamicPropertySource
    static void reportStorageProperties(DynamicPropertyRegistry registry) {
        ensureBucketExists();
        registry.add("app.storage.reports.r2.endpoint", MINIO::getS3URL);
        registry.add("app.storage.reports.r2.access-key", () -> ACCESS_KEY);
        registry.add("app.storage.reports.r2.secret-key", () -> SECRET_KEY);
        registry.add("app.storage.reports.r2.bucket", () -> REPORTS_BUCKET);
        registry.add("app.storage.reports.r2.region", () -> "us-east-1");
    }

    static String reportsBucket() {
        return REPORTS_BUCKET;
    }

    private static void ensureBucketExists() {
        try (S3Client client = buildClient()) {
            try {
                client.headBucket(HeadBucketRequest.builder().bucket(REPORTS_BUCKET).build());
            } catch (NoSuchBucketException ex) {
                client.createBucket(CreateBucketRequest.builder().bucket(REPORTS_BUCKET).build());
            }
        }
    }

    private static S3Client buildClient() {
        return S3Client.builder()
                .endpointOverride(URI.create(MINIO.getS3URL()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
                .region(Region.US_EAST_1)
                .forcePathStyle(true)
                .build();
    }
}
