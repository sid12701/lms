package com.bhawana.lms.service;

import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

@Service
public class LspApiIdempotencyService {

    private final IdempotencyExecutionCoordinator idempotencyExecutionCoordinator;

    public LspApiIdempotencyService(IdempotencyExecutionCoordinator idempotencyExecutionCoordinator) {
        this.idempotencyExecutionCoordinator = idempotencyExecutionCoordinator;
    }

    public <T> T execute(
            UUID lspId,
            String operationKey,
            String idempotencyKey,
            Object requestFingerprintSource,
            Class<T> responseType,
            Supplier<T> action
    ) {
        String normalizedKey = requireUuidV4(idempotencyKey);
        return idempotencyExecutionCoordinator.executeLsp(
                lspId,
                operationKey,
                normalizedKey,
                requestFingerprintSource,
                responseType,
                action
        );
    }

    public String requireUuidV4(String idempotencyKey) {
        return IdempotencyKeyValidator.requireUuidV4(idempotencyKey);
    }
}
