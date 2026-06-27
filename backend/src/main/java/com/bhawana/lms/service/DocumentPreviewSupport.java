package com.bhawana.lms.service;

import java.util.Locale;
import java.util.Set;

/**
 * Single source of truth for which stored document content types are safe to
 * serve {@code Content-Disposition: inline} for in-browser preview.
 *
 * <p>The allowlist deliberately mirrors {@link DocumentUploadPolicy}'s global
 * allowed MIME types, so every type that can be stored can also be previewed —
 * and, critically, scriptable formats (SVG, HTML) are never rendered inline.
 * This is the primary guard against "unsafe file rendering": even if such a
 * content type were persisted on a legacy row, an inline preview request for it
 * is rejected rather than streamed into an {@code <iframe>}/{@code <img>}.
 */
public final class DocumentPreviewSupport {

    /** Inline-previewable content types. Mirrors {@code DocumentUploadPolicy.GLOBAL_ALLOWED_MIME_TYPES}. */
    static final Set<String> INLINE_PREVIEWABLE_CONTENT_TYPES = Set.of(
            "application/pdf",
            "image/jpeg",
            "image/png"
    );

    private DocumentPreviewSupport() {
    }

    /**
     * @return {@code true} when {@code contentType} (parameters such as
     *         {@code ;charset=...} ignored) is safe to serve inline.
     */
    public static boolean isInlinePreviewable(String contentType) {
        return INLINE_PREVIEWABLE_CONTENT_TYPES.contains(normalize(contentType));
    }

    private static String normalize(String contentType) {
        if (contentType == null) {
            return "";
        }
        String trimmed = contentType.trim().toLowerCase(Locale.ROOT);
        int semicolon = trimmed.indexOf(';');
        return semicolon < 0 ? trimmed : trimmed.substring(0, semicolon).trim();
    }
}
