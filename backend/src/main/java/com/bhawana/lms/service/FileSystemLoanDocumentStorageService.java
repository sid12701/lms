package com.bhawana.lms.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

final class FileSystemLoanDocumentStorageService {

    private FileSystemLoanDocumentStorageService() {
    }

    static List<LoanDocumentStorageService.StorageEntry> listAll(Path rootPath, String prefix) {
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

    static byte[] retrieve(Path rootPath, String storageKey) {
        Path targetPath = rootPath.resolve(storageKey);
        try {
            return Files.readAllBytes(targetPath);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to retrieve document from LMS-managed local storage: " + storageKey, exception);
        }
    }

    static StoredDocument store(DocumentStorageDescriptor descriptor, Path rootPath, byte[] content) {
        Path targetPath = rootPath.resolve(descriptor.storageKey());
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
