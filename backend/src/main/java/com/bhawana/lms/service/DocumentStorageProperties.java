package com.bhawana.lms.service;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.storage.documents")
public class DocumentStorageProperties {

    private DocumentStorageProvider provider = DocumentStorageProvider.LOCAL;
    private Path rootPath = Path.of(System.getProperty("java.io.tmpdir"), "lms-documents");
    private final R2 r2 = new R2();

    private final OrphanReconciler orphanReconciler = new OrphanReconciler();

    public DocumentStorageProvider getProvider() {
        return provider;
    }

    public void setProvider(DocumentStorageProvider provider) {
        this.provider = provider == null ? DocumentStorageProvider.LOCAL : provider;
    }

    public Path getRootPath() {
        return rootPath;
    }

    public void setRootPath(Path rootPath) {
        if (rootPath != null) {
            this.rootPath = rootPath;
        }
    }

    public R2 getR2() {
        return r2;
    }

    public OrphanReconciler getOrphanReconciler() {
        return orphanReconciler;
    }

    /**
     * M04 orphan-object reconciler. Starts in dry-run: it reports what it would delete until an
     * operator has reviewed that inventory and turns deletion on.
     */
    public static class OrphanReconciler {

        private boolean enabled = true;

        private boolean dryRun = true;

        /** How long a PENDING object must sit untouched before it may be deleted. */
        private Duration gracePeriod = Duration.ofHours(24);

        private int batchSize = 100;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isDryRun() {
            return dryRun;
        }

        public void setDryRun(boolean dryRun) {
            this.dryRun = dryRun;
        }

        public Duration getGracePeriod() {
            return gracePeriod;
        }

        public void setGracePeriod(Duration gracePeriod) {
            if (gracePeriod != null) {
                this.gracePeriod = gracePeriod;
            }
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = Math.max(1, batchSize);
        }
    }

    public enum DocumentStorageProvider {
        LOCAL,
        R2
    }

    public static class R2 {

        private String endpoint;
        private String accessKey;
        private String secretKey;
        private String bucket;
        private String region = "auto";

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public String getAccessKey() {
            return accessKey;
        }

        public void setAccessKey(String accessKey) {
            this.accessKey = accessKey;
        }

        public String getSecretKey() {
            return secretKey;
        }

        public void setSecretKey(String secretKey) {
            this.secretKey = secretKey;
        }

        public String getBucket() {
            return bucket;
        }

        public void setBucket(String bucket) {
            this.bucket = bucket;
        }

        public String getRegion() {
            return region;
        }

        public void setRegion(String region) {
            if (region != null && !region.isBlank()) {
                this.region = region;
            }
        }

        public boolean isConfigured() {
            return hasText(endpoint) && hasText(accessKey) && hasText(secretKey) && hasText(bucket);
        }

        private static boolean hasText(String value) {
            return value != null && !value.isBlank();
        }
    }
}
