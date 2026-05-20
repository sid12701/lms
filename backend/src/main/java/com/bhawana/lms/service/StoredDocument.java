package com.bhawana.lms.service;

public record StoredDocument(
        String fileName,
        String contentType,
        long fileSizeBytes,
        String fileChecksum,
        String storageKey,
        String canonicalUri
) {
}
