package com.bhawana.lms.service;

import com.bhawana.lms.common.api.error.DocumentNotFoundException;
import com.bhawana.lms.common.api.error.DocumentStorageMisconfiguredException;
import com.bhawana.lms.common.api.error.DocumentStorageUnavailableException;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * Loan-document storage against Cloudflare R2. The {@link S3Client} is built once, lazily on
 * first use, and shared by every call (M05): its bounded Apache connection pool is the whole
 * point of the client, and API call/attempt deadlines come from
 * {@code app.storage.documents.r2.*} (see {@link DocumentStorageProperties.R2}). The client is
 * closed once at bean shutdown — response streams are closed by callers and only ever release
 * their pooled connection back, never the shared client.
 */
@Service
public class R2LoanDocumentStorageService {

    private final DocumentStorageProperties properties;
    private final Object clientLock = new Object();
    private volatile S3Client client;
    private volatile boolean closed;

    @Autowired
    public R2LoanDocumentStorageService(DocumentStorageProperties properties) {
        this.properties = properties;
    }

    /** Test seam: run against a pre-built client without touching properties. */
    R2LoanDocumentStorageService(DocumentStorageProperties properties, S3Client client) {
        this.properties = properties;
        this.client = client;
    }

    public List<LoanDocumentStorageService.StorageEntry> listAll(String prefix) {
        DocumentStorageProperties.R2 r2 = properties.getR2();
        S3Client s3Client = client();
        List<LoanDocumentStorageService.StorageEntry> entries = new ArrayList<>();
        String continuationToken = null;
        do {
            ListObjectsV2Request.Builder requestBuilder = ListObjectsV2Request.builder()
                    .bucket(r2.getBucket())
                    .prefix(prefix);
            if (continuationToken != null) {
                requestBuilder.continuationToken(continuationToken);
            }
            ListObjectsV2Response response = s3Client.listObjectsV2(requestBuilder.build());
            for (S3Object s3Object : response.contents()) {
                ResponseBytes<GetObjectResponse> bytes = s3Client.getObjectAsBytes(
                        GetObjectRequest.builder()
                                .bucket(r2.getBucket())
                                .key(s3Object.key())
                                .build()
                );
                entries.add(new LoanDocumentStorageService.StorageEntry(s3Object.key(), bytes.asByteArray()));
            }
            continuationToken = response.isTruncated() ? response.nextContinuationToken() : null;
        } while (continuationToken != null);
        return entries;
    }

    public byte[] retrieve(String storageKey) {
        DocumentStorageProperties.R2 r2 = properties.getR2();
        try {
            ResponseBytes<GetObjectResponse> responseBytes = client().getObjectAsBytes(
                    GetObjectRequest.builder()
                            .bucket(r2.getBucket())
                            .key(storageKey)
                            .build()
            );
            return responseBytes.asByteArray();
        } catch (NoSuchKeyException exception) {
            throw new DocumentNotFoundException("Document not found in R2 storage: " + storageKey);
        } catch (SdkException exception) {
            throw unavailable(storageKey, "Unable to retrieve document from R2 storage: ", exception);
        }
    }

    public LoanDocumentStorageService.RetrievedDocumentStream openStream(String storageKey) {
        DocumentStorageProperties.R2 r2 = properties.getR2();
        // The shared client outlives the returned stream: closing the stream releases only the
        // pooled connection, so the object body streams straight to the caller without buffering
        // the whole document into heap — and without ending the client's life for other callers.
        S3Client s3Client = client();
        try {
            ResponseInputStream<GetObjectResponse> responseStream = s3Client.getObject(
                    GetObjectRequest.builder()
                            .bucket(r2.getBucket())
                            .key(storageKey)
                            .build()
            );
            long contentLength = responseStream.response().contentLength();
            return new LoanDocumentStorageService.RetrievedDocumentStream(responseStream, contentLength);
        } catch (NoSuchKeyException exception) {
            throw new DocumentNotFoundException("Document not found in R2 storage: " + storageKey);
        } catch (SdkException exception) {
            throw unavailable(storageKey, "Unable to retrieve document from R2 storage: ", exception);
        }
    }

    public void delete(String storageKey) {
        DocumentStorageProperties.R2 r2 = properties.getR2();
        // S3 DeleteObject on a missing key succeeds, so a retried delete is harmless.
        try {
            client().deleteObject(DeleteObjectRequest.builder()
                    .bucket(r2.getBucket())
                    .key(storageKey)
                    .build());
        } catch (SdkException exception) {
            throw unavailable(storageKey, "Unable to delete document from R2 storage: ", exception);
        }
    }

    public StoredDocument store(DocumentStorageDescriptor descriptor, byte[] content) {
        DocumentStorageProperties.R2 r2 = properties.getR2();
        try {
            client().putObject(
                    PutObjectRequest.builder()
                            .bucket(r2.getBucket())
                            .key(descriptor.storageKey())
                            .contentType(descriptor.contentType())
                            .build(),
                    RequestBody.fromBytes(content)
            );
        } catch (SdkException exception) {
            throw unavailable(descriptor.storageKey(), "Unable to store document in R2 storage: ", exception);
        }
        return new StoredDocument(
                descriptor.originalFileName(),
                descriptor.contentType(),
                content.length,
                descriptor.checksum(),
                descriptor.storageKey(),
                "r2://" + r2.getBucket() + "/" + descriptor.storageKey()
        );
    }

    /**
     * Returns the shared client, building it once on first use. Laziness matters: in a
     * LOCAL-provider deployment this bean exists but R2 is never touched, so no pool or
     * credentials should be materialised at all.
     */
    private S3Client client() {
        S3Client current = client;
        if (current != null) {
            return current;
        }
        synchronized (clientLock) {
            if (closed) {
                throw new IllegalStateException("R2 document storage client is shut down.");
            }
            if (client == null) {
                DocumentStorageProperties.R2 r2 = properties.getR2();
                if (!r2.isConfigured()) {
                    throw new DocumentStorageMisconfiguredException(
                            DocumentStorageProperties.DocumentStorageProvider.R2.name(),
                            "endpoint, accessKey, secretKey, bucket",
                            "R2 document storage is not configured. Set endpoint, access key, secret key, and bucket."
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

    private static DocumentStorageUnavailableException unavailable(
            String storageKey,
            String message,
            SdkException cause
    ) {
        return new DocumentStorageUnavailableException(
                storageKey,
                DocumentStorageProperties.DocumentStorageProvider.R2.name(),
                message + storageKey,
                cause
        );
    }
}
