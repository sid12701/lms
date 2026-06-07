package com.bhawana.lms.service;

import com.bhawana.lms.common.web.ApiConflictException;
import com.bhawana.lms.domain.LspApiIdempotencyRecord;
import com.bhawana.lms.repo.LspApiIdempotencyRecordRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LspApiIdempotencyService {

    private final LspApiIdempotencyRecordRepository lspApiIdempotencyRecordRepository;
    private final ObjectMapper objectMapper;
    private final IdempotencyClaimService idempotencyClaimService;

    public LspApiIdempotencyService(
            LspApiIdempotencyRecordRepository lspApiIdempotencyRecordRepository,
            ObjectMapper objectMapper,
            IdempotencyClaimService idempotencyClaimService
    ) {
        this.lspApiIdempotencyRecordRepository = lspApiIdempotencyRecordRepository;
        this.objectMapper = objectMapper;
        this.idempotencyClaimService = idempotencyClaimService;
    }

    @Transactional
    public <T> T execute(
            UUID lspId,
            String operationKey,
            String idempotencyKey,
            Object requestFingerprintSource,
            Class<T> responseType,
            Supplier<T> action
    ) {
        String normalizedKey = requireUuidV4(idempotencyKey);
        String requestFingerprint = fingerprint(requestFingerprintSource);

        LspApiIdempotencyRecord existingRecord = lspApiIdempotencyRecordRepository
                .findByLspIdAndOperationKeyAndIdempotencyKey(lspId, operationKey, normalizedKey)
                .orElse(null);
        if (existingRecord != null) {
            if (!existingRecord.getRequestFingerprint().equals(requestFingerprint)) {
                throw new ApiConflictException(
                        "IDEMPOTENCY_CONFLICT",
                        "Idempotency-Key has already been used for a different request."
                );
            }
            return deserialize(existingRecord.getResponseBody(), responseType);
        }

        T response = action.get();
        String serializedBody = serialize(response);
        boolean claimed = idempotencyClaimService.claimLspApiIdempotencyRecord(new LspApiIdempotencyRecord(
                lspId,
                operationKey,
                normalizedKey,
                requestFingerprint,
                200,
                serializedBody
        ));
        if (!claimed) {
            return recoverAfterIdempotencyRace(
                    lspId,
                    operationKey,
                    normalizedKey,
                    requestFingerprint,
                    responseType
            );
        }
        return response;
    }

    private <T> T recoverAfterIdempotencyRace(
            UUID lspId,
            String operationKey,
            String normalizedKey,
            String requestFingerprint,
            Class<T> responseType
    ) {
        LspApiIdempotencyRecord racedRecord = lspApiIdempotencyRecordRepository
                .findByLspIdAndOperationKeyAndIdempotencyKey(lspId, operationKey, normalizedKey)
                .orElseThrow(() -> new IllegalStateException(
                        "Idempotency row missing after unique violation for key " + normalizedKey
                ));
        if (!racedRecord.getRequestFingerprint().equals(requestFingerprint)) {
            throw new ApiConflictException(
                    "IDEMPOTENCY_CONFLICT",
                    "Idempotency-Key has already been used for a different request."
            );
        }
        return deserialize(racedRecord.getResponseBody(), responseType);
    }

    public String requireUuidV4(String idempotencyKey) {
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

    private String fingerprint(Object requestFingerprintSource) {
        return IdempotencyFingerprinter.fingerprint(objectMapper, requestFingerprintSource);
    }

    private String serialize(Object response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize idempotent response.", exception);
        }
    }

    private <T> T deserialize(String responseBody, Class<T> responseType) {
        try {
            return objectMapper.readValue(responseBody, responseType);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to deserialize idempotent response.", exception);
        }
    }
}
