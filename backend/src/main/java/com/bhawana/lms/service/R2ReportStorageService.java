package com.bhawana.lms.service;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
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
    public StoredReport store(ReportStorageDescriptor descriptor, Path contentFile) {
        if (descriptor == null) {
            throw new IllegalArgumentException("Report storage descriptor is required.");
        }
        if (contentFile == null || !Files.isRegularFile(contentFile)) {
            throw new IllegalArgumentException("Report content file is required.");
        }
        requireConfigured();

        String storageKey = buildStorageKey(descriptor);
        long sizeBytes;
        try {
            sizeBytes = Files.size(contentFile);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to size report content file.", exception);
        }
        try (S3Client client = buildClient()) {
            client.putObject(
                    PutObjectRequest.builder()
                            .bucket(properties.getR2().getBucket())
                            .key(storageKey)
                            .contentType(descriptor.mediaType())
                            .contentLength(sizeBytes)
                            .build(),
                    RequestBody.fromFile(contentFile)
            );
        }
        return new StoredReport(storageKey, descriptor.fileName(), descriptor.mediaType(), sizeBytes);
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

    /**
     * Deterministic job output identity (H24): the same report request always produces the same
     * key, so a retry after a crash between upload and completion overwrites the earlier object
     * instead of leaving an orphaned duplicate. The download name is recorded on the request
     * row, not in the key.
     */
    private static String buildStorageKey(ReportStorageDescriptor descriptor) {
        return "reports/"
                + descriptor.requestId()
                + "/"
                + descriptor.reportType().name().toLowerCase()
                + "/"
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
