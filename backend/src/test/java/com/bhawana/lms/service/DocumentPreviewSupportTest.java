package com.bhawana.lms.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class DocumentPreviewSupportTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "application/pdf",
            "image/jpeg",
            "image/png",
            "IMAGE/PNG",
            "application/pdf; charset=binary"
    })
    void allowsSafePreviewContentTypes(String contentType) {
        assertTrue(DocumentPreviewSupport.isInlinePreviewable(contentType));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "image/svg+xml",
            "text/html",
            "application/xml",
            "text/plain",
            "application/octet-stream",
            "application/zip"
    })
    void rejectsScriptableOrUnknownContentTypes(String contentType) {
        assertFalse(DocumentPreviewSupport.isInlinePreviewable(contentType));
    }

    @ParameterizedTest
    @NullAndEmptySource
    void rejectsMissingContentType(String contentType) {
        assertFalse(DocumentPreviewSupport.isInlinePreviewable(contentType));
    }
}
