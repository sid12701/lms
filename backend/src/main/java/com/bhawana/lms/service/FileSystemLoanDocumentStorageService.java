package com.bhawana.lms.service;

import com.bhawana.lms.common.api.error.DocumentNotFoundException;
import com.bhawana.lms.common.api.error.DocumentStorageUnavailableException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
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
        Path rootPath = properties.getRootPath().toAbsolutePath().normalize();
        Path directory = (prefix == null || prefix.isBlank())
                ? rootPath
                : resolveUnderRoot(prefix);
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
        Path targetPath = resolveUnderRoot(storageKey);
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
        Path targetPath = resolveUnderRoot(storageKey);
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

    public void delete(String storageKey) {
        Path targetPath = resolveUnderRoot(storageKey);
        try {
            Files.deleteIfExists(targetPath);
        } catch (IOException exception) {
            throw new DocumentStorageUnavailableException(
                    storageKey,
                    DocumentStorageProperties.DocumentStorageProvider.LOCAL.name(),
                    "Unable to delete document from LMS-managed local storage: " + storageKey,
                    exception
            );
        }
    }

    public StoredDocument store(DocumentStorageDescriptor descriptor, byte[] content) {
        Path targetPath = resolveUnderRoot(descriptor.storageKey());
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

    /**
     * Defense-in-depth for path injection: a storage key must stay a relative
     * path under the configured storage root. Keys are built server-side and
     * upload file names are reduced to a safe key segment before they ever
     * reach a key, but a key that still resolves outside the root (an absolute
     * path or {@code ..} segments) is rejected outright instead of being
     * resolved.
     */
    private Path resolveUnderRoot(String storageKey) {
        if (storageKey == null || storageKey.isBlank()) {
            throw new IllegalArgumentException("Storage key is required.");
        }
        Path root = properties.getRootPath().toAbsolutePath().normalize();
        Path resolved;
        try {
            resolved = root.resolve(storageKey).normalize();
        } catch (InvalidPathException exception) {
            throw new IllegalArgumentException("Storage key is not a valid path: " + storageKey, exception);
        }
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException(
                    "Storage key escapes document root: " + storageKey
            );
        }
        return resolved;
    }
}
