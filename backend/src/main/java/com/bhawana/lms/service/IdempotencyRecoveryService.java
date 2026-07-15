package com.bhawana.lms.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class IdempotencyRecoveryService {

    private final List<IdempotencyResultReconstructor> reconstructors;

    public IdempotencyRecoveryService(List<IdempotencyResultReconstructor> reconstructors) {
        this.reconstructors = reconstructors;
    }

    public <T> Optional<T> tryRecover(
            UUID lspId,
            String operationKey,
            Object requestFingerprintSource,
            Class<T> responseType
    ) {
        for (IdempotencyResultReconstructor reconstructor : reconstructors) {
            if (!reconstructor.supports(operationKey, responseType)) {
                continue;
            }
            Optional<T> recovered = reconstructor.tryRecover(
                    lspId,
                    operationKey,
                    requestFingerprintSource,
                    responseType
            );
            if (recovered.isPresent()) {
                return recovered;
            }
        }
        return Optional.empty();
    }

    public boolean supports(String operationKey, Class<?> responseType) {
        return reconstructors.stream().anyMatch(reconstructor -> reconstructor.supports(operationKey, responseType));
    }
}
