package com.bhawana.lms.service;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.storage.reports")
public class ReportStorageProperties {

    private final R2 r2 = new R2();

    public R2 getR2() {
        return r2;
    }

    public static class R2 implements R2S3ClientFactory.R2ClientConfiguration {

        private String endpoint;
        private String accessKey;
        private String secretKey;
        private String bucket;
        private String region = "auto";
        private Duration apiCallTimeout = Duration.ofSeconds(120);
        private Duration apiCallAttemptTimeout = Duration.ofSeconds(30);
        private Duration connectionTimeout = Duration.ofSeconds(5);
        private Duration socketTimeout = Duration.ofSeconds(30);
        private int maxConnections = 50;

        @Override
        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        @Override
        public String getAccessKey() {
            return accessKey;
        }

        public void setAccessKey(String accessKey) {
            this.accessKey = accessKey;
        }

        @Override
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

        @Override
        public String getRegion() {
            return region;
        }

        public void setRegion(String region) {
            if (region != null && !region.isBlank()) {
                this.region = region;
            }
        }

        @Override
        public Duration getApiCallTimeout() {
            return apiCallTimeout;
        }

        public void setApiCallTimeout(Duration apiCallTimeout) {
            if (apiCallTimeout != null) {
                this.apiCallTimeout = apiCallTimeout;
            }
        }

        @Override
        public Duration getApiCallAttemptTimeout() {
            return apiCallAttemptTimeout;
        }

        public void setApiCallAttemptTimeout(Duration apiCallAttemptTimeout) {
            if (apiCallAttemptTimeout != null) {
                this.apiCallAttemptTimeout = apiCallAttemptTimeout;
            }
        }

        @Override
        public Duration getConnectionTimeout() {
            return connectionTimeout;
        }

        public void setConnectionTimeout(Duration connectionTimeout) {
            if (connectionTimeout != null) {
                this.connectionTimeout = connectionTimeout;
            }
        }

        @Override
        public Duration getSocketTimeout() {
            return socketTimeout;
        }

        public void setSocketTimeout(Duration socketTimeout) {
            if (socketTimeout != null) {
                this.socketTimeout = socketTimeout;
            }
        }

        @Override
        public int getMaxConnections() {
            return maxConnections;
        }

        public void setMaxConnections(int maxConnections) {
            if (maxConnections > 0) {
                this.maxConnections = maxConnections;
            }
        }

        @Override
        public boolean isConfigured() {
            return hasText(endpoint) && hasText(accessKey) && hasText(secretKey) && hasText(bucket);
        }

        private static boolean hasText(String value) {
            return value != null && !value.isBlank();
        }
    }
}
