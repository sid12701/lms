package com.bhawana.lms.support;

import com.bhawana.lms.service.ReportStorageService;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * In-memory {@link ReportStorageService} for tests that need report storage to work but are
 * not testing the storage adapter itself.
 *
 * <p>Replaces the MinIO testcontainer that previously stood in for Cloudflare R2. Storage keys
 * follow the same shape as {@code R2ReportStorageService} so key-format assumptions elsewhere
 * keep holding, and bytes are returned exactly as written (no String round-trip).
 *
 * <p>This deliberately does NOT cover the R2/S3 adapter: byte fidelity against a real S3
 * endpoint and the missing-key error type are properties of the AWS SDK path, not of this map.
 */
@TestConfiguration
public class InMemoryReportStorageConfig {

    @Bean
    @Primary
    ReportStorageService inMemoryReportStorageService() {
        return new InMemoryReportStorageService();
    }

    public static final class InMemoryReportStorageService implements ReportStorageService {

        private final Map<String, byte[]> objects = new ConcurrentHashMap<>();

        @Override
        public StoredReport store(ReportStorageDescriptor descriptor, java.nio.file.Path contentFile) {
            if (descriptor == null) {
                throw new IllegalArgumentException("Report storage descriptor is required.");
            }
            if (contentFile == null) {
                throw new IllegalArgumentException("Report content file is required.");
            }
            byte[] content;
            try {
                content = java.nio.file.Files.readAllBytes(contentFile);
            } catch (java.io.IOException exception) {
                throw new IllegalStateException("Unable to read report content file.", exception);
            }
            String storageKey = "reports/"
                    + descriptor.requestId()
                    + "/"
                    + descriptor.reportType().name().toLowerCase(java.util.Locale.ROOT)
                    + "/"
                    + sanitizeFileName(descriptor.fileName());
            objects.put(storageKey, content);
            return new StoredReport(
                    storageKey, descriptor.fileName(), descriptor.mediaType(), content.length);
        }

        @Override
        public byte[] retrieve(String storageKey) {
            byte[] content = objects.get(storageKey);
            if (content == null) {
                throw new IllegalStateException("No stored report for key " + storageKey);
            }
            return content.clone();
        }

        /** Test-visible count of distinct stored objects — proves retries converge on one key. */
        public int storedObjectCount() {
            return objects.size();
        }

        /** Test-visible copy of stored keys. */
        public java.util.Set<String> storedKeys() {
            return java.util.Set.copyOf(objects.keySet());
        }

        /** Mirrors the adapter's key sanitising so a filename can never inject path segments. */
        private static String sanitizeFileName(String fileName) {
            String candidate = (fileName == null || fileName.isBlank()) ? "report.bin" : fileName.trim();
            return candidate.replace("\\", "_").replace("/", "_");
        }
    }
}
