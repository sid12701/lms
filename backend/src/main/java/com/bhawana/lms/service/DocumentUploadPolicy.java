package com.bhawana.lms.service;

import com.bhawana.lms.common.web.BusinessRuleViolationException;
import com.bhawana.lms.domain.LoanApplicationDocumentType;
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

    private DocumentUploadPolicy() {
    }

    static void validate(LoanApplicationDocumentType documentType, MultipartFile file) {
        Constraints constraints = constraintsFor(documentType);
        long declaredSize = file.getSize();
        if (declaredSize > constraints.maxBytes()) {
            throw fileTooLarge(documentType, declaredSize, constraints.maxBytes());
        }

        String normalizedMime = normalizeMime(file.getContentType());
        if (normalizedMime == null || !constraints.allowedMimeTypes().contains(normalizedMime)) {
            throw mimeNotAllowed(documentType, file.getContentType(), constraints.allowedMimeTypes());
        }
    }

    static long maxBytesFor(LoanApplicationDocumentType documentType) {
        return constraintsFor(documentType).maxBytes();
    }

    private static Constraints constraintsFor(LoanApplicationDocumentType documentType) {
        return switch (documentType) {
            case LOAN_AGREEMENT -> new Constraints(GLOBAL_MAX_BYTES, PDF_ONLY_MIME_TYPES);
            case PAN_CARD, AADHAAR_FILE -> new Constraints(IDENTITY_DOCUMENT_MAX_BYTES, IDENTITY_DOCUMENT_MIME_TYPES);
            default -> new Constraints(GLOBAL_MAX_BYTES, GLOBAL_ALLOWED_MIME_TYPES);
        };
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

    private record Constraints(long maxBytes, Set<String> allowedMimeTypes) {
    }
}
