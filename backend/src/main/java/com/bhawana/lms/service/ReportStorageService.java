package com.bhawana.lms.service;

import com.bhawana.lms.domain.ReportType;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.UUID;

public interface ReportStorageService {

    /**
     * Stores the report file by streaming from {@code contentFile} — the upload never
     * materialises the whole export in heap (H25).
     */
    StoredReport store(ReportStorageDescriptor descriptor, Path contentFile);

    byte[] retrieve(String storageKey);

    /**
     * Opens a streaming handle for a stored report so a download never materialises the whole
     * export in heap (M05). Existence/availability failures are raised eagerly here — before
     * any response bytes are committed — and the caller must close
     * {@link ReportStream#content()}, which releases only the underlying pooled connection,
     * never the shared storage client.
     */
    ReportStream openStream(String storageKey);

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

    /** A lazily-streamed report body plus its known content length (bytes). */
    record ReportStream(InputStream content, long contentLength) implements Closeable {

        @Override
        public void close() throws IOException {
            content.close();
        }
    }
}
