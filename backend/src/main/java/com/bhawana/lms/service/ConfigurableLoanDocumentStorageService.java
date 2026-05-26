package com.bhawana.lms.service;

import com.bhawana.lms.common.web.BusinessRuleViolationException;
import com.bhawana.lms.domain.LoanApplicationDocumentType;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class ConfigurableLoanDocumentStorageService implements LoanDocumentStorageService {

    private final DocumentStorageProperties properties;

    public ConfigurableLoanDocumentStorageService(DocumentStorageProperties properties) {
        this.properties = properties;
    }

    @Override
    public byte[] retrieve(String storageKey) {
        if (storageKey == null || storageKey.isBlank()) {
            throw new IllegalArgumentException("Storage key is required for document retrieval.");
        }
        return switch (properties.getProvider()) {
            case R2 -> retrieveFromR2OrFail(storageKey);
            case LOCAL -> FileSystemLoanDocumentStorageService.retrieve(properties.getRootPath(), storageKey);
        };
    }

    private byte[] retrieveFromR2OrFail(String storageKey) {
        if (!properties.getR2().isConfigured()) {
            throw new IllegalStateException(
                    "R2 document storage is selected but not fully configured. Set endpoint, access key, secret key, and bucket."
            );
        }
        return R2LoanDocumentStorageService.retrieve(properties.getR2(), storageKey);
    }

    @Override
    public List<StorageEntry> listAll(String prefix) {
        return switch (properties.getProvider()) {
            case R2 -> listAllFromR2OrFail(prefix);
            case LOCAL -> FileSystemLoanDocumentStorageService.listAll(properties.getRootPath(), prefix);
        };
    }

    private List<StorageEntry> listAllFromR2OrFail(String prefix) {
        if (!properties.getR2().isConfigured()) {
            throw new IllegalStateException(
                    "R2 document storage is selected but not fully configured. Set endpoint, access key, secret key, and bucket."
            );
        }
        return R2LoanDocumentStorageService.listAll(properties.getR2(), prefix);
    }

    @Override
    public StoredDocument store(
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
            DocumentStorageDescriptor descriptor = new DocumentStorageDescriptor(
                    sanitizeFileName(file.getOriginalFilename()),
                    resolveContentType(file.getContentType()),
                    checksum(content),
                    buildStorageKey(applicationId, documentType, sanitizeFileName(file.getOriginalFilename()))
            );
            return switch (properties.getProvider()) {
                case R2 -> storeToR2OrFail(descriptor, content);
                case LOCAL -> FileSystemLoanDocumentStorageService.store(descriptor, properties.getRootPath(), content);
            };
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Unable to read multipart document content.", exception);
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
            throw new IllegalStateException(
                    "R2 document storage is selected but not fully configured. Set endpoint, access key, secret key, and bucket."
            );
        }
        return R2LoanDocumentStorageService.store(properties.getR2(), descriptor, content);
    }

    private static String buildStorageKey(UUID applicationId, LoanApplicationDocumentType documentType, String fileName) {
        return "loan/" + applicationId
                + "/" + documentType.name().toLowerCase()
                + "/" + Instant.now().toEpochMilli()
                + "-" + UUID.randomUUID()
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
