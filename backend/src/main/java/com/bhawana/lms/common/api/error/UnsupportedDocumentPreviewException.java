package com.bhawana.lms.common.api.error;

/**
 * Raised when an inline preview is requested for a stored document whose content
 * type is not on the safe inline-preview allowlist. Mapped to HTTP 415 so the
 * UI can fall back to a download-only affordance.
 */
public class UnsupportedDocumentPreviewException extends RuntimeException {

    public UnsupportedDocumentPreviewException(String message) {
        super(message);
    }
}

