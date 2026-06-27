package com.bhawana.lms.service;

import com.bhawana.lms.common.api.error.DocumentNotFoundException;
import com.bhawana.lms.common.api.error.DocumentStorageUnavailableException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;

@Service
public class FileSystemLoanDocumentStorageService {

    private final DocumentStorageProperties properties;

    public FileSystemLoanDocumentStorageService(DocumentStorageProperties properties) {
        this.properties = properties;
    }

    public List<LoanDocumentStorageService.StorageEntry> listAll(String prefix) {
        Path rootPath = properties.getRootPath();
        Path directory = rootPath.resolve(prefix);
        List<LoanDocumentStorageService.StorageEntry> entries = new ArrayList<>();
        if (!Files.isDirectory(directory)) {
            return entries;
        }
        try (Stream<Path> walk = Files.walk(directory)) {
            walk.filter(Files::isRegularFile).forEach(filePath -> {
                try {
                    String key = rootPath.relativize(filePath).toString().replace('\\', '/');
                    entries.add(new LoanDocumentStorageService.StorageEntry(key, Files.readAllBytes(filePath)));
                } catch (IOException exception) {
                    throw new IllegalStateException("Unable to read file: " + filePath, exception);
                }
            });
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to list documents under: " + prefix, exception);
        }
        return entries;
    }

    public byte[] retrieve(String storageKey) {
        Path targetPath = properties.getRootPath().resolve(storageKey);
        if (!Files.exists(targetPath)) {
            throw new DocumentNotFoundException(
                    "Document not found in LMS-managed local storage: " + storageKey
            );
        }
        try {
            return Files.readAllBytes(targetPath);
        } catch (IOException exception) {
            throw new DocumentStorageUnavailableException(
                    storageKey,
                    DocumentStorageProperties.DocumentStorageProvider.LOCAL.name(),
                    "Unable to retrieve document from LMS-managed local storage: " + storageKey,
                    exception
            );
        }
    }

    public LoanDocumentStorageService.RetrievedDocumentStream openStream(String storageKey) {
        Path targetPath = properties.getRootPath().resolve(storageKey);
        if (!Files.exists(targetPath)) {
            throw new DocumentNotFoundException(
                    "Document not found in LMS-managed local storage: " + storageKey
            );
        }
        try {
            long contentLength = Files.size(targetPath);
            InputStream content = Files.newInputStream(targetPath);
            return new LoanDocumentStorageService.RetrievedDocumentStream(content, contentLength);
        } catch (IOException exception) {
            throw new DocumentStorageUnavailableException(
                    storageKey,
                    DocumentStorageProperties.DocumentStorageProvider.LOCAL.name(),
                    "Unable to retrieve document from LMS-managed local storage: " + storageKey,
                    exception
            );
        }
    }

    public StoredDocument store(DocumentStorageDescriptor descriptor, byte[] content) {
        Path targetPath = properties.getRootPath().resolve(descriptor.storageKey());
        try {
            Files.createDirectories(targetPath.getParent());
            Files.write(targetPath, content);
            return new StoredDocument(
                    descriptor.originalFileName(),
                    descriptor.contentType(),
                    content.length,
                    descriptor.checksum(),
                    descriptor.storageKey(),
                    "lms-doc://" + descriptor.storageKey().replace('\\', '/')
            );
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to store document in LMS-managed local storage.", exception);
        }
    }
}
