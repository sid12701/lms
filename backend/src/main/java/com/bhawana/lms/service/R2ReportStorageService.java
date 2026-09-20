package com.bhawana.lms.service;

import com.bhawana.lms.common.api.error.ResourceNotFoundException;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * Report-object storage against Cloudflare R2. The {@link S3Client} is built once, lazily on
 * first use, and shared by every call (M05): its bounded Apache connection pool is reused
 * across requests and API call/attempt deadlines come from {@code app.storage.reports.r2.*}
 * (see {@link ReportStorageProperties.R2}). The client is closed once at bean shutdown;
 * streams handed out by {@link #openStream} release only their pooled connection when the
 * caller closes them.
 */
@Service
public class R2ReportStorageService implements ReportStorageService {

    private final ReportStorageProperties properties;
    private final Object clientLock = new Object();
    private volatile S3Client client;
    private volatile boolean closed;

    @Autowired
    public R2ReportStorageService(ReportStorageProperties properties) {
        this.properties = properties;
    }

    /** Test seam: run against a pre-built client without touching properties. */
    R2ReportStorageService(ReportStorageProperties properties, S3Client client) {
        this.properties = properties;
        this.client = client;
    }

    @Override
    public StoredReport store(ReportStorageDescriptor descriptor, Path contentFile) {
        if (descriptor == null) {
            throw new IllegalArgumentException("Report storage descriptor is required.");
        }
        if (contentFile == null || !Files.isRegularFile(contentFile)) {
            throw new IllegalArgumentException("Report content file is required.");
        }

        String storageKey = buildStorageKey(descriptor);
        long sizeBytes;
        try {
            sizeBytes = Files.size(contentFile);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to size report content file.", exception);
        }
        client().putObject(
                PutObjectRequest.builder()
                        .bucket(properties.getR2().getBucket())
                        .key(storageKey)
                        .contentType(descriptor.mediaType())
                        .contentLength(sizeBytes)
                        .build(),
                RequestBody.fromFile(contentFile)
        );
        return new StoredReport(storageKey, descriptor.fileName(), descriptor.mediaType(), sizeBytes);
    }

    @Override
    public byte[] retrieve(String storageKey) {
        if (storageKey == null || storageKey.isBlank()) {
            throw new IllegalArgumentException("Storage key is required for report retrieval.");
        }

        ResponseBytes<GetObjectResponse> responseBytes = client().getObjectAsBytes(
                GetObjectRequest.builder()
                        .bucket(properties.getR2().getBucket())
                        .key(storageKey)
                        .build()
        );
        return responseBytes.asByteArray();
    }

    @Override
    public ReportStream openStream(String storageKey) {
        if (storageKey == null || storageKey.isBlank()) {
            throw new IllegalArgumentException("Storage key is required for report retrieval.");
        }
        try {
            ResponseInputStream<GetObjectResponse> responseStream = client().getObject(
                    GetObjectRequest.builder()
                            .bucket(properties.getR2().getBucket())
                            .key(storageKey)
                            .build()
            );
            return new ReportStream(responseStream, responseStream.response().contentLength());
        } catch (NoSuchKeyException exception) {
            throw new ResourceNotFoundException("Report not found in storage: " + storageKey);
        }
    }

    /**
     * Returns the shared client, building it once on first use — never per call. Building is
     * lazy so an unconfigured deployment does not materialise a pool it can never use; the
     * misconfiguration error is raised here, at first use, with the same contract as before.
     */
    private S3Client client() {
        S3Client current = client;
        if (current != null) {
            return current;
        }
        synchronized (clientLock) {
            if (closed) {
                throw new IllegalStateException("R2 report storage client is shut down.");
            }
            if (client == null) {
                ReportStorageProperties.R2 r2 = properties.getR2();
                if (!r2.isConfigured()) {
                    throw new IllegalStateException(
                            "R2 report storage is not configured. Set endpoint, access key, secret key, and bucket."
                    );
                }
                client = R2S3ClientFactory.build(r2);
            }
            return client;
        }
    }

    @PreDestroy
    void shutdown() {
        synchronized (clientLock) {
            closed = true;
            if (client != null) {
                client.close();
                client = null;
            }
        }
    }

    /** Test-visible: the lazily-built shared client, or null before first use. */
    S3Client peekClient() {
        return client;
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
