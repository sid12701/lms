package com.bhawana.lms.service;



import com.bhawana.lms.common.api.error.BusinessRuleViolationException;

import com.bhawana.lms.common.api.error.DocumentStorageMisconfiguredException;

import com.bhawana.lms.domain.LoanApplicationDocumentType;

import java.security.MessageDigest;

import java.security.NoSuchAlgorithmException;

import java.util.HexFormat;

import java.util.List;

import java.util.Map;

import java.util.UUID;

import org.springframework.stereotype.Service;

import org.springframework.web.multipart.MultipartFile;



@Service

public class ConfigurableLoanDocumentStorageService implements LoanDocumentStorageService {



    private final DocumentStorageProperties properties;

    private final FileSystemLoanDocumentStorageService fileSystemStorage;

    private final R2LoanDocumentStorageService r2Storage;



    public ConfigurableLoanDocumentStorageService(

            DocumentStorageProperties properties,

            FileSystemLoanDocumentStorageService fileSystemStorage,

            R2LoanDocumentStorageService r2Storage

    ) {

        this.properties = properties;

        this.fileSystemStorage = fileSystemStorage;

        this.r2Storage = r2Storage;

    }



    @Override

    public byte[] retrieve(String storageKey) {

        if (storageKey == null || storageKey.isBlank()) {

            throw new IllegalArgumentException("Storage key is required for document retrieval.");

        }

        return switch (properties.getProvider()) {

            case R2 -> retrieveFromR2OrFail(storageKey);

            case LOCAL -> fileSystemStorage.retrieve(storageKey);

        };

    }



    private byte[] retrieveFromR2OrFail(String storageKey) {

        if (!properties.getR2().isConfigured()) {

            throw r2Misconfigured();

        }

        return r2Storage.retrieve(storageKey);

    }



    @Override

    public RetrievedDocumentStream openStream(String storageKey) {

        if (storageKey == null || storageKey.isBlank()) {

            throw new IllegalArgumentException("Storage key is required for document retrieval.");

        }

        return switch (properties.getProvider()) {

            case R2 -> openStreamFromR2OrFail(storageKey);

            case LOCAL -> fileSystemStorage.openStream(storageKey);

        };

    }



    private RetrievedDocumentStream openStreamFromR2OrFail(String storageKey) {

        if (!properties.getR2().isConfigured()) {

            throw r2Misconfigured();

        }

        return r2Storage.openStream(storageKey);

    }



    @Override

    public List<StorageEntry> listAll(String prefix) {

        return switch (properties.getProvider()) {

            case R2 -> listAllFromR2OrFail(prefix);

            case LOCAL -> fileSystemStorage.listAll(prefix);

        };

    }



    private List<StorageEntry> listAllFromR2OrFail(String prefix) {

        if (!properties.getR2().isConfigured()) {

            throw r2Misconfigured();

        }

        return r2Storage.listAll(prefix);

    }



    @Override

    public PreparedDocument prepare(

            UUID applicationId,

            LoanApplicationDocumentType documentType,

            MultipartFile file

    ) {

        if (file == null || file.isEmpty()) {

            throw new BusinessRuleViolationException(

                    "DOCUMENT_FILE_EMPTY",

                    "Document file is required.",

                    Map.of("file", "A non-empty file is required.")

            );

        }



        DocumentUploadPolicy.validate(documentType, file);



        try {

            byte[] content = file.getBytes();

            long maxBytes = DocumentUploadPolicy.maxBytesFor(documentType);

            if (content.length > maxBytes) {

                throw fileTooLarge(documentType, content.length, maxBytes);

            }

            String fileName = sanitizeFileName(file.getOriginalFilename());

            String checksum = checksum(content);

            return new PreparedDocument(

                    applicationId,

                    documentType,

                    fileName,

                    resolveContentType(file.getContentType()),

                    checksum,

                    buildStorageKey(applicationId, documentType, checksum, fileName),

                    content

            );

        } catch (java.io.IOException exception) {

            throw new IllegalStateException("Unable to read multipart document content.", exception);

        }

    }



    @Override

    public StoredDocument store(PreparedDocument document) {

        DocumentStorageDescriptor descriptor = new DocumentStorageDescriptor(

                document.fileName(),

                document.contentType(),

                document.checksum(),

                document.storageKey()

        );

        return switch (properties.getProvider()) {

            case R2 -> storeToR2OrFail(descriptor, document.content());

            case LOCAL -> fileSystemStorage.store(descriptor, document.content());

        };

    }



    @Override

    public void delete(String storageKey) {

        if (storageKey == null || storageKey.isBlank()) {

            throw new IllegalArgumentException("Storage key is required for document deletion.");

        }

        switch (properties.getProvider()) {

            case R2 -> {

                if (!properties.getR2().isConfigured()) {

                    throw r2Misconfigured();

                }

                r2Storage.delete(storageKey);

            }

            case LOCAL -> fileSystemStorage.delete(storageKey);

        }

    }



    private static BusinessRuleViolationException fileTooLarge(

            LoanApplicationDocumentType documentType,

            long actualBytes,

            long maxBytes

    ) {

        return new BusinessRuleViolationException(

                "DOCUMENT_FILE_TOO_LARGE",

                "Document exceeds the maximum permitted size of "

                        + maxBytes + " bytes for " + documentType.name()

                        + " (got " + actualBytes + ").",

                Map.of(

                        "documentType", documentType.name(),

                        "fileSizeBytes", String.valueOf(actualBytes),

                        "maxFileSizeBytes", String.valueOf(maxBytes)

                )

        );

    }



    private StoredDocument storeToR2OrFail(DocumentStorageDescriptor descriptor, byte[] content) {

        if (!properties.getR2().isConfigured()) {

            throw r2Misconfigured();

        }

        return r2Storage.store(descriptor, content);

    }



    private DocumentStorageMisconfiguredException r2Misconfigured() {

        return new DocumentStorageMisconfiguredException(

                DocumentStorageProperties.DocumentStorageProvider.R2.name(),

                "endpoint, accessKey, secretKey, bucket",

                "R2 document storage is selected but not fully configured. Set endpoint, access key, secret key, and bucket."

        );

    }



    // Content-addressed: the same bytes and name for the same application and type always map
    // to the same key, so a retry converges on one object instead of leaving a stray copy, and
    // an object at a key never changes content. Keys written before V131 carry a timestamp
    // and random UUID instead and are left exactly where they are.
    private static String buildStorageKey(

            UUID applicationId,

            LoanApplicationDocumentType documentType,

            String checksum,

            String fileName

    ) {

        return "loan/" + applicationId

                + "/" + documentType.name().toLowerCase()

                + "/" + checksum

                + "-" + fileName;

    }



    private static String checksum(byte[] content) {

        try {

            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            return HexFormat.of().formatHex(digest.digest(content));

        } catch (NoSuchAlgorithmException exception) {

            throw new IllegalStateException("SHA-256 digest is not available.", exception);

        }

    }



    private static String sanitizeFileName(String originalFileName) {

        String candidate = originalFileName == null ? "document.bin" : originalFileName.trim();

        if (candidate.isBlank()) {

            candidate = "document.bin";

        }

        return candidate

                .replace("\\", "_")

                .replace("/", "_")

                .replace("\r", "")

                .replace("\n", "")

                .replace("\0", "")

                .replace("\"", "_");

    }



    private static String resolveContentType(String contentType) {

        return contentType == null || contentType.isBlank()

                ? "application/octet-stream"

                : contentType.trim();

    }

}

