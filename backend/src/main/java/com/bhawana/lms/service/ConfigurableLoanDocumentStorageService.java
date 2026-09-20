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



    /** Maximum length of the readability-only file-name segment inside a storage key. */

    private static final int MAX_KEY_FILE_NAME_LENGTH = 128;



    /** Substituted when an upload file name reduces to no usable key segment. */

    private static final String FALLBACK_KEY_FILE_NAME = "document.bin";



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



    /**
     * Content-addressed storage key: the same document bytes and file name under
     * the same application and document type always resolve to the same object.
     * A re-executed idempotent upload after a crash (H17) overwrites its own
     * object instead of stacking an orphan, and the checksum prefix makes a
     * collision between genuinely different content impossible — an object at a
     * key never changes content. The file name is readability-only and is
     * reduced to a safe single path segment before it enters the key. Keys
     * written before V135 carry a timestamp and random UUID instead and are
     * left exactly where they are.
     */
    private static String buildStorageKey(

            UUID applicationId,

            LoanApplicationDocumentType documentType,

            String contentChecksum,

            String fileName

    ) {

        return "loan/" + applicationId

                + "/" + documentType.name().toLowerCase()

                + "/" + contentChecksum

                + "-" + toSafeKeySegment(fileName);

    }



    /**
     * The file-name portion of a storage key exists only for readability —
     * the content checksum is the object's real identity. The segment is
     * reduced to a single safe path element: the raw name is first truncated
     * to its last path component, every character outside
     * {@code [A-Za-z0-9._-]} collapses to {@code _}, leading dots are dropped
     * so it can never become a dot-segment or a hidden name, and the result
     * is length-bounded. The mapping is deterministic, so an idempotent
     * replay of the same upload still derives the same key.
     */

    private static String toSafeKeySegment(String fileName) {

        String candidate = fileName == null ? "" : fileName;

        int lastSeparator = Math.max(candidate.lastIndexOf('/'), candidate.lastIndexOf('\\'));

        if (lastSeparator >= 0) {

            candidate = candidate.substring(lastSeparator + 1);

        }

        StringBuilder cleaned = new StringBuilder(candidate.length());

        for (int index = 0; index < candidate.length(); index++) {

            char character = candidate.charAt(index);

            cleaned.append(isSafeKeySegmentChar(character) ? character : '_');

        }

        String segment = stripLeadingDots(cleaned.toString());

        if (segment.length() > MAX_KEY_FILE_NAME_LENGTH) {

            segment = stripLeadingDots(segment.substring(segment.length() - MAX_KEY_FILE_NAME_LENGTH));

        }

        return segment.isEmpty() ? FALLBACK_KEY_FILE_NAME : segment;

    }



    private static boolean isSafeKeySegmentChar(char character) {

        return character >= 'a' && character <= 'z'

                || character >= 'A' && character <= 'Z'

                || character >= '0' && character <= '9'

                || character == '.'

                || character == '_'

                || character == '-';

    }



    private static String stripLeadingDots(String value) {

        int start = 0;

        while (start < value.length() && value.charAt(start) == '.') {

            start++;

        }

        return value.substring(start);

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

