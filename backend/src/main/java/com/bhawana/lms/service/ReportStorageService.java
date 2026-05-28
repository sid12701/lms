package com.bhawana.lms.service;

import com.bhawana.lms.domain.ReportType;
import java.util.UUID;

public interface ReportStorageService {

    StoredReport store(ReportStorageDescriptor descriptor, byte[] content);

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
