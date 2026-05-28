package com.bhawana.lms.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.storage.reports")
public class ReportStorageProperties {

    private final R2 r2 = new R2();

    public R2 getR2() {
        return r2;
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
