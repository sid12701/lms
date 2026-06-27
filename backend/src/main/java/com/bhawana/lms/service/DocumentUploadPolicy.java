package com.bhawana.lms.service;

import com.bhawana.lms.common.api.error.BusinessRuleViolationException;
import com.bhawana.lms.domain.LoanApplicationDocumentType;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.web.multipart.MultipartFile;

/**
 * Per-document-type upload constraints on top of the global allowlist.
 * See docs/gap-fixes.md Follow-up #3.
 */
public final class DocumentUploadPolicy {

    static final long GLOBAL_MAX_BYTES = 10L * 1024L * 1024L;
    static final long IDENTITY_DOCUMENT_MAX_BYTES = 5L * 1024L * 1024L;

    static final Set<String> GLOBAL_ALLOWED_MIME_TYPES = Set.of(
            "application/pdf",
            "image/jpeg",
            "image/png"
    );

    private static final Set<String> PDF_ONLY_MIME_TYPES = Set.of("application/pdf");
    private static final Set<String> IDENTITY_DOCUMENT_MIME_TYPES = Set.of(
            "application/pdf",
            "image/jpeg"
    );

    private static final byte[] PNG_SIGNATURE = {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    };

    private DocumentUploadPolicy() {
    }

    static void validate(LoanApplicationDocumentType documentType, MultipartFile file) {
        validateFileName(file.getOriginalFilename());

        Constraints constraints = constraintsFor(documentType);
        String normalizedMime = normalizeMime(file.getContentType());
        if (normalizedMime == null || !constraints.allowedMimeTypes().contains(normalizedMime)) {
            throw mimeNotAllowed(documentType, file.getContentType(), constraints.allowedMimeTypes());
        }

        byte[] content = readContent(file);
        if (content.length > constraints.maxBytes()) {
            throw fileTooLarge(documentType, content.length, constraints.maxBytes());
        }
        validateContentMatchesMime(normalizedMime, content);
    }

    static long maxBytesFor(LoanApplicationDocumentType documentType) {
        return constraintsFor(documentType).maxBytes();
    }

    static void validateFileName(String originalFileName) {
        String candidate = originalFileName == null ? "" : originalFileName.trim();
        if (candidate.isEmpty() || candidate.contains("..")) {
            throw fileNameInvalid(originalFileName);
        }
    }

    private static Constraints constraintsFor(LoanApplicationDocumentType documentType) {
        return switch (documentType) {
            case LOAN_AGREEMENT -> new Constraints(GLOBAL_MAX_BYTES, PDF_ONLY_MIME_TYPES);
            case PAN_CARD, AADHAAR_FILE -> new Constraints(IDENTITY_DOCUMENT_MAX_BYTES, IDENTITY_DOCUMENT_MIME_TYPES);
            default -> new Constraints(GLOBAL_MAX_BYTES, GLOBAL_ALLOWED_MIME_TYPES);
        };
    }

    private static byte[] readContent(MultipartFile file) {
        try (InputStream inputStream = file.getInputStream()) {
            return inputStream.readAllBytes();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read multipart document content.", exception);
        }
    }

    private static void validateContentMatchesMime(String normalizedMime, byte[] content) {
        if (content.length == 0) {
            throw contentInvalid("Document content is empty.");
        }
        boolean valid = switch (normalizedMime) {
            case "application/pdf" -> startsWith(content, "%PDF".getBytes(StandardCharsets.US_ASCII));
            case "image/jpeg" -> content.length >= 3
                    && (content[0] & 0xFF) == 0xFF
                    && (content[1] & 0xFF) == 0xD8
                    && (content[2] & 0xFF) == 0xFF;
            case "image/png" -> startsWith(content, PNG_SIGNATURE);
            default -> false;
        };
        if (!valid) {
            throw contentInvalid("Document content does not match declared MIME type " + normalizedMime + ".");
        }
    }

    private static boolean startsWith(byte[] content, byte[] prefix) {
        if (content.length < prefix.length) {
            return false;
        }
        for (int index = 0; index < prefix.length; index++) {
            if (content[index] != prefix[index]) {
                return false;
            }
        }
        return true;
    }

    private static String normalizeMime(String contentType) {
        if (contentType == null) {
            return null;
        }
        String trimmed = contentType.trim().toLowerCase(Locale.ROOT);
        if (trimmed.isEmpty()) {
            return null;
        }
        int semicolon = trimmed.indexOf(';');
        return semicolon < 0 ? trimmed : trimmed.substring(0, semicolon).trim();
    }

    private static BusinessRuleViolationException mimeNotAllowed(
            LoanApplicationDocumentType documentType,
            String declaredContentType,
            Set<String> permitted
    ) {
        return new BusinessRuleViolationException(
                "DOCUMENT_MIME_NOT_ALLOWED",
                "Document MIME type is not allowed for " + documentType.name()
                        + ". Permitted: " + permitted + ".",
                Map.of(
                        "documentType", documentType.name(),
                        "contentType", declaredContentType == null ? "<missing>" : declaredContentType
                )
        );
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

    private static BusinessRuleViolationException fileNameInvalid(String originalFileName) {
        return new BusinessRuleViolationException(
                "DOCUMENT_FILE_NAME_INVALID",
                "Document file name is not allowed.",
                Map.of(
                        "fileName", originalFileName == null ? "<missing>" : originalFileName
                )
        );
    }

    private static BusinessRuleViolationException contentInvalid(String message) {
        return new BusinessRuleViolationException(
                "DOCUMENT_CONTENT_INVALID",
                message,
                Map.of()
        );
    }

    private record Constraints(long maxBytes, Set<String> allowedMimeTypes) {
    }
}
