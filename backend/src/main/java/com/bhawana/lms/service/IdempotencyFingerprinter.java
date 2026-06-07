package com.bhawana.lms.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class IdempotencyFingerprinter {

    private static final HexFormat HEX = HexFormat.of();

    private IdempotencyFingerprinter() {
    }

    static String fingerprint(ObjectMapper objectMapper, Object requestFingerprintSource) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] payload = objectMapper.writeValueAsString(requestFingerprintSource)
                    .getBytes(StandardCharsets.UTF_8);
            return HEX.formatHex(digest.digest(payload));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize idempotency fingerprint payload.", exception);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 digest is not available.", exception);
        }
    }
}
