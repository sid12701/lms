package com.bhawana.lms.service;

import java.util.UUID;

final class IdempotencyKeyValidator {

    private IdempotencyKeyValidator() {
    }

    static String requireUuidV4(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.trim().isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key header is required.");
        }
        UUID uuid;
        try {
            uuid = UUID.fromString(idempotencyKey.trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Idempotency-Key must be a UUID v4 value.");
        }
        if (uuid.version() != 4) {
            throw new IllegalArgumentException("Idempotency-Key must be a UUID v4 value.");
        }
        return uuid.toString();
    }
}
