package com.bhawana.lms.service;

import com.bhawana.lms.domain.ReportType;
import java.nio.file.Path;
import java.util.UUID;

public interface ReportStorageService {

    /**
     * Stores the report file by streaming from {@code contentFile} — the upload never
     * materialises the whole export in heap (H25).
     */
    StoredReport store(ReportStorageDescriptor descriptor, Path contentFile);

    byte[] retrieve(String storageKey);

    record ReportStorageDescriptor(
            UUID requestId,
            ReportType reportType,
            String fileName,
            String mediaType
    ) {
    }

    record StoredReport(
            String storageKey,
            String fileName,
            String mediaType,
            long sizeBytes
    ) {
    }
}
