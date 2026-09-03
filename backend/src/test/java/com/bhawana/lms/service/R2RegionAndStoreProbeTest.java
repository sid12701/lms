package com.bhawana.lms.service;

import static org.assertj.core.api.Assertions.assertThatCode;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

class R2RegionAndStoreProbeTest {

    @Test
    void regionAutoIsAcceptedByAwsSdk() {
        assertThatCode(() -> Region.of("auto")).doesNotThrowAnyException();
    }

    @Test
    @EnabledIfSystemProperty(named = "r2.probe.enabled", matches = "true")
    @EnabledIf("r2EnvPresent")
    void putObjectAgainstConfiguredR2Endpoint() throws Exception {
        Map<String, String> env = loadRepoRootEnv();
        String endpoint = env.get("APP_STORAGE_DOCUMENTS_R2_ENDPOINT");
        String accessKey = env.get("APP_STORAGE_DOCUMENTS_R2_ACCESS_KEY");
        String secretKey = env.get("APP_STORAGE_DOCUMENTS_R2_SECRET_KEY");
        String bucket = env.get("APP_STORAGE_DOCUMENTS_R2_BUCKET");
        String region = env.getOrDefault("APP_STORAGE_DOCUMENTS_R2_REGION", "auto");

        try (S3Client client = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)
                ))
                .region(Region.of(region))
                .forcePathStyle(true)
                .build()) {
            String key = "loan/debug/java-probe-" + System.currentTimeMillis() + ".txt";
            client.putObject(
                    PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(key)
                            .contentType("text/plain")
                            .build(),
                    RequestBody.fromBytes("java-probe".getBytes(StandardCharsets.UTF_8))
            );
            client.deleteObject(builder -> builder.bucket(bucket).key(key));
        }
    }

    static boolean r2EnvPresent() {
        try {
            Map<String, String> env = loadRepoRootEnv();
            return env.containsKey("APP_STORAGE_DOCUMENTS_R2_ENDPOINT")
                    && env.containsKey("APP_STORAGE_DOCUMENTS_R2_ACCESS_KEY")
                    && env.containsKey("APP_STORAGE_DOCUMENTS_R2_SECRET_KEY")
                    && env.containsKey("APP_STORAGE_DOCUMENTS_R2_BUCKET");
        } catch (Exception exception) {
            return false;
        }
    }

    private static Map<String, String> loadRepoRootEnv() throws Exception {
        Path envFile = Path.of("..", ".env").normalize();
        Map<String, String> env = new HashMap<>();
        for (String line : Files.readAllLines(envFile)) {
            if (line.isBlank() || line.startsWith("#") || !line.contains("=")) {
                continue;
            }
            int idx = line.indexOf('=');
            env.put(line.substring(0, idx).trim(), line.substring(idx + 1).trim());
        }
        return env;
    }
}
