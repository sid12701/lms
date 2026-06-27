package com.bhawana.lms.service;

import com.bhawana.lms.domain.LoanApplicationDocumentType;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;
import org.springframework.web.multipart.MultipartFile;

public interface LoanDocumentStorageService {

    StoredDocument store(
            UUID applicationId,
            LoanApplicationDocumentType documentType,
            MultipartFile file
    );

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

    /** A lazily-streamed document body plus its known content length (bytes). */
    record RetrievedDocumentStream(InputStream content, long contentLength) {
    }
}
