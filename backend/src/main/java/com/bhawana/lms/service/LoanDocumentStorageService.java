package com.bhawana.lms.service;

import com.bhawana.lms.domain.LoanApplicationDocumentType;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;
import org.springframework.web.multipart.MultipartFile;

public interface LoanDocumentStorageService {

    /**
     * Validate an upload and fix its identity without touching storage (M04): content policy,
     * size, checksum and a deterministic, content-addressed storage key. A batch prepares every
     * item before any object is written, and a retry of the same bytes resolves to the same key.
     */
    PreparedDocument prepare(
            UUID applicationId,
            LoanApplicationDocumentType documentType,
            MultipartFile file
    );

    /** Write a prepared object. Rewriting the same key writes the same bytes. */
    StoredDocument store(PreparedDocument document);

    /** Delete an object; deleting a missing key is not an error. */
    void delete(String storageKey);

    byte[] retrieve(String storageKey);

    /**
     * Open a streaming handle to the stored object instead of buffering its full
     * content into heap. Existence/availability failures are raised eagerly here
     * (so callers can map them to clean 4xx/5xx before any bytes are written), and
     * the returned {@link RetrievedDocumentStream#content()} must be closed by the
     * caller — closing it also releases any underlying storage client/connection.
     */
    RetrievedDocumentStream openStream(String storageKey);

    List<StorageEntry> listAll(String prefix);

    record StorageEntry(String key, byte[] content) {
    }

    /** A validated upload whose storage key is fixed but whose bytes are not yet written. */
    record PreparedDocument(
            UUID applicationId,
            LoanApplicationDocumentType documentType,
            String fileName,
            String contentType,
            String checksum,
            String storageKey,
            byte[] content
    ) {
    }

    /** A lazily-streamed document body plus its known content length (bytes). */
    record RetrievedDocumentStream(InputStream content, long contentLength) {
    }
}
