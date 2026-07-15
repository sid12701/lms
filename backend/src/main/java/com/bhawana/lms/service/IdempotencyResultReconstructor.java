package com.bhawana.lms.service;

import java.util.Optional;
import java.util.UUID;

/**
 * Reconstructs a completed idempotent API response from committed business state
 * when the idempotency row is still pending after a process crash.
 */
public interface IdempotencyResultReconstructor {

    boolean supports(String operationKey, Class<?> responseType);

    <T> Optional<T> tryRecover(
            UUID lspId,
            String operationKey,
            Object requestFingerprintSource,
            Class<T> responseType
    );
}
