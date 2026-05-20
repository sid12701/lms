package com.bhawana.lms.service;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;

final class R2LoanDocumentStorageService {

    private R2LoanDocumentStorageService() {
    }

    static List<LoanDocumentStorageService.StorageEntry> listAll(DocumentStorageProperties.R2 properties, String prefix) {
        List<LoanDocumentStorageService.StorageEntry> entries = new ArrayList<>();
        try (S3Client s3Client = buildClient(properties)) {
            String continuationToken = null;
            do {
                ListObjectsV2Request.Builder requestBuilder = ListObjectsV2Request.builder()
                        .bucket(properties.getBucket())
                        .prefix(prefix);
                if (continuationToken != null) {
                    requestBuilder.continuationToken(continuationToken);
                }
                ListObjectsV2Response response = s3Client.listObjectsV2(requestBuilder.build());
                for (S3Object s3Object : response.contents()) {
                    ResponseBytes<GetObjectResponse> bytes = s3Client.getObjectAsBytes(
                            GetObjectRequest.builder()
                                    .bucket(properties.getBucket())
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

    static byte[] retrieve(DocumentStorageProperties.R2 properties, String storageKey) {
        try (S3Client s3Client = buildClient(properties)) {
            ResponseBytes<GetObjectResponse> responseBytes = s3Client.getObjectAsBytes(
                    GetObjectRequest.builder()
                            .bucket(properties.getBucket())
                            .key(storageKey)
                            .build()
            );
            return responseBytes.asByteArray();
        }
    }

    static StoredDocument store(
            DocumentStorageProperties.R2 properties,
            DocumentStorageDescriptor descriptor,
            byte[] content
    ) {
        try (S3Client s3Client = buildClient(properties)) {
            s3Client.putObject(
                    PutObjectRequest.builder()
                            .bucket(properties.getBucket())
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
                "r2://" + properties.getBucket() + "/" + descriptor.storageKey()
        );
    }

    private static S3Client buildClient(DocumentStorageProperties.R2 properties) {
        return S3Client.builder()
                .endpointOverride(URI.create(properties.getEndpoint()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(properties.getAccessKey(), properties.getSecretKey())
                ))
                .region(Region.of(properties.getRegion()))
                .forcePathStyle(true)
                .build();
    }
}
