package com.bhawana.lms.service;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@Service
public class R2ReportStorageService implements ReportStorageService {

    private final ReportStorageProperties properties;

    public R2ReportStorageService(ReportStorageProperties properties) {
        this.properties = properties;
    }

    @Override
    public StoredReport store(ReportStorageDescriptor descriptor, byte[] content) {
        if (descriptor == null) {
            throw new IllegalArgumentException("Report storage descriptor is required.");
        }
        if (content == null) {
            throw new IllegalArgumentException("Report content is required.");
        }
        requireConfigured();

        String storageKey = buildStorageKey(descriptor);
        try (S3Client client = buildClient()) {
            client.putObject(
                    PutObjectRequest.builder()
                            .bucket(properties.getR2().getBucket())
                            .key(storageKey)
                            .contentType(descriptor.mediaType())
                            .contentLength((long) content.length)
                            .build(),
                    RequestBody.fromBytes(content)
            );
        }
        return new StoredReport(storageKey, descriptor.fileName(), descriptor.mediaType(), content.length);
    }

    @Override
    public byte[] retrieve(String storageKey) {
        if (storageKey == null || storageKey.isBlank()) {
            throw new IllegalArgumentException("Storage key is required for report retrieval.");
        }
        requireConfigured();

        try (S3Client client = buildClient()) {
            ResponseBytes<GetObjectResponse> responseBytes = client.getObjectAsBytes(
                    GetObjectRequest.builder()
                            .bucket(properties.getR2().getBucket())
                            .key(storageKey)
                            .build()
            );
            return responseBytes.asByteArray();
        }
    }

    private void requireConfigured() {
        if (!properties.getR2().isConfigured()) {
            throw new IllegalStateException(
                    "R2 report storage is not configured. Set endpoint, access key, secret key, and bucket."
            );
        }
    }

    private S3Client buildClient() {
        ReportStorageProperties.R2 r2 = properties.getR2();
        return S3Client.builder()
                .endpointOverride(URI.create(r2.getEndpoint()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(r2.getAccessKey(), r2.getSecretKey())))
                .region(Region.of(r2.getRegion()))
                .forcePathStyle(true)
                .build();
    }

    private static String buildStorageKey(ReportStorageDescriptor descriptor) {
        return "reports/"
                + descriptor.requestId()
                + "/"
                + descriptor.reportType().name().toLowerCase()
                + "/"
                + Instant.now().toEpochMilli()
                + "-"
                + UUID.randomUUID()
                + "-"
                + sanitizeFileName(descriptor.fileName());
    }

    private static String sanitizeFileName(String fileName) {
        String candidate = (fileName == null || fileName.isBlank()) ? "report.bin" : fileName.trim();
        return candidate
                .replace("\\", "_")
                .replace("/", "_")
                .replace("\r", "")
                .replace("\n", "")
                .replace("\0", "")
                .replace("\"", "_");
    }
}
