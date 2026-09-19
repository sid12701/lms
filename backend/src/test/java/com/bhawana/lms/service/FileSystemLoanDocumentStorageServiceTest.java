package com.bhawana.lms.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileSystemLoanDocumentStorageServiceTest {

    @TempDir
    Path tempDir;

    private FileSystemLoanDocumentStorageService service() {
        DocumentStorageProperties properties = new DocumentStorageProperties();
        properties.setRootPath(tempDir);
        return new FileSystemLoanDocumentStorageService(properties);
    }

    private DocumentStorageDescriptor descriptor(String storageKey) {
        return new DocumentStorageDescriptor(
                "file.pdf",
                "application/pdf",
                "abc123",
                storageKey
        );
    }

    @Test
    void rejectsDotDotTraversalKey() {
        FileSystemLoanDocumentStorageService service = service();

        assertThrows(
                IllegalArgumentException.class,
                () -> service.store(descriptor("../outside.pdf"), "evil".getBytes())
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> service.retrieve("loan/../../outside.pdf")
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> service.delete("loan/../../outside.pdf")
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> service.openStream("loan/../../outside.pdf")
        );
    }

    @Test
    void rejectsAbsolutePathKey() {
        FileSystemLoanDocumentStorageService service = service();
        String absoluteKey = tempDir.getRoot().resolve("etc-passwd.pdf").toString();

        assertThrows(
                IllegalArgumentException.class,
                () -> service.store(descriptor(absoluteKey), "evil".getBytes())
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> service.retrieve(absoluteKey)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> service.delete(absoluteKey)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> service.openStream(absoluteKey)
        );
    }

    @Test
    void normalKeyStoresRetrievesAndDeletes() throws Exception {
        FileSystemLoanDocumentStorageService service = service();
        String storageKey = "loan/app/type/abc123-file.pdf";
        byte[] content = "%PDF-1.4-body".getBytes();

        service.store(descriptor(storageKey), content);

        assertArrayEquals(content, service.retrieve(storageKey));

        try (java.io.InputStream contentStream = service.openStream(storageKey).content()) {
            byte[] streamed = contentStream.readAllBytes();
            assertArrayEquals(content, streamed);
        }

        service.delete(storageKey);

        assertThrows(
                com.bhawana.lms.common.api.error.DocumentNotFoundException.class,
                () -> service.retrieve(storageKey)
        );
    }
}
