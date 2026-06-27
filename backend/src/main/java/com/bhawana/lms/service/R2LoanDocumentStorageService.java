package com.bhawana.lms.service;

import com.bhawana.lms.common.api.error.DocumentNotFoundException;
import com.bhawana.lms.common.api.error.DocumentStorageUnavailableException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

@Service
public class R2LoanDocumentStorageService {

    private final DocumentStorageProperties properties;

    public R2LoanDocumentStorageService(DocumentStorageProperties properties) {
        this.properties = properties;
    }

    public List<LoanDocumentStorageService.StorageEntry> listAll(String prefix) {
        DocumentStorageProperties.R2 r2 = properties.getR2();
        List<LoanDocumentStorageService.StorageEntry> entries = new ArrayList<>();
        try (S3Client s3Client = buildClient(r2)) {
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
        }
        return entries;
    }

    public byte[] retrieve(String storageKey) {
        DocumentStorageProperties.R2 r2 = properties.getR2();
        try (S3Client s3Client = buildClient(r2)) {
            ResponseBytes<GetObjectResponse> responseBytes = s3Client.getObjectAsBytes(
                    GetObjectRequest.builder()
                            .bucket(r2.getBucket())
                            .key(storageKey)
                            .build()
            );
            return responseBytes.asByteArray();
        } catch (NoSuchKeyException exception) {
            throw new DocumentNotFoundException("Document not found in R2 storage: " + storageKey);
        } catch (S3Exception | SdkClientException exception) {
            throw new DocumentStorageUnavailableException(
                    storageKey,
                    DocumentStorageProperties.DocumentStorageProvider.R2.name(),
                    "Unable to retrieve document from R2 storage: " + storageKey,
                    exception
            );
        }
    }

    public LoanDocumentStorageService.RetrievedDocumentStream openStream(String storageKey) {
        DocumentStorageProperties.R2 r2 = properties.getR2();
        // The S3Client must outlive this method: it is closed only when the
        // returned stream is closed (after the response body has been written),
        // so the object body is streamed straight to the client without buffering
        // the whole document into heap.
        S3Client s3Client = buildClient(r2);
        try {
            ResponseInputStream<GetObjectResponse> responseStream = s3Client.getObject(
                    GetObjectRequest.builder()
                            .bucket(r2.getBucket())
                            .key(storageKey)
                            .build()
            );
            long contentLength = responseStream.response().contentLength();
            return new LoanDocumentStorageService.RetrievedDocumentStream(
                    new ClientClosingInputStream(responseStream, s3Client),
                    contentLength
            );
        } catch (NoSuchKeyException exception) {
            s3Client.close();
            throw new DocumentNotFoundException("Document not found in R2 storage: " + storageKey);
        } catch (S3Exception | SdkClientException exception) {
            s3Client.close();
            throw new DocumentStorageUnavailableException(
                    storageKey,
                    DocumentStorageProperties.DocumentStorageProvider.R2.name(),
                    "Unable to retrieve document from R2 storage: " + storageKey,
                    exception
            );
        } catch (RuntimeException exception) {
            s3Client.close();
            throw exception;
        }
    }

    public StoredDocument store(DocumentStorageDescriptor descriptor, byte[] content) {
        DocumentStorageProperties.R2 r2 = properties.getR2();
        try (S3Client s3Client = buildClient(r2)) {
            s3Client.putObject(
                    PutObjectRequest.builder()
                            .bucket(r2.getBucket())
                            .key(descriptor.storageKey())
                            .contentType(descriptor.contentType())
                            .build(),
                    RequestBody.fromBytes(content)
            );
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

    private static S3Client buildClient(DocumentStorageProperties.R2 r2) {
        return S3Client.builder()
                .endpointOverride(URI.create(r2.getEndpoint()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(r2.getAccessKey(), r2.getSecretKey())
                ))
                .region(Region.of(r2.getRegion()))
                .forcePathStyle(true)
                .build();
    }

    /**
     * Wraps the S3 object stream so that closing it also closes the owning
     * {@link S3Client}, preventing the per-request client (and its connection
     * pool) from leaking once the response body has been streamed.
     */
    private static final class ClientClosingInputStream extends FilterInputStream {

        private final S3Client client;

        private ClientClosingInputStream(InputStream delegate, S3Client client) {
            super(delegate);
            this.client = client;
        }

        @Override
        public void close() throws IOException {
            try {
                super.close();
            } finally {
                client.close();
            }
        }
    }
}
