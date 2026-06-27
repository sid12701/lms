package com.bhawana.lms.service;

import com.bhawana.lms.common.api.error.ApiConflictException;
import com.bhawana.lms.domain.LspApiIdempotencyRecord;
import com.bhawana.lms.repo.LspApiIdempotencyRecordRepository;
import com.bhawana.lms.tenant.AdminScopedTransactionExecutor;
import com.bhawana.lms.tenant.TenantAccessContext;
import com.bhawana.lms.tenant.TenantDataAccessContextHolder;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

@Service
public class LspApiIdempotencyService {

    private final LspApiIdempotencyRecordRepository lspApiIdempotencyRecordRepository;
    private final ObjectMapper objectMapper;
    private final IdempotencyClaimService idempotencyClaimService;
    private final AdminScopedTransactionExecutor adminScopedTransactionExecutor;

    public LspApiIdempotencyService(
            LspApiIdempotencyRecordRepository lspApiIdempotencyRecordRepository,
            ObjectMapper objectMapper,
            IdempotencyClaimService idempotencyClaimService,
            AdminScopedTransactionExecutor adminScopedTransactionExecutor
    ) {
        this.lspApiIdempotencyRecordRepository = lspApiIdempotencyRecordRepository;
        this.objectMapper = objectMapper;
        this.idempotencyClaimService = idempotencyClaimService;
        this.adminScopedTransactionExecutor = adminScopedTransactionExecutor;
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
        String requestFingerprint = fingerprint(requestFingerprintSource);

        LspApiIdempotencyRecord existingRecord = findRecord(lspId, operationKey, normalizedKey);
        if (existingRecord != null) {
            return replayExisting(existingRecord, requestFingerprint, responseType);
        }

        // Run the wrapped action under admin scope but NOT inside an idempotency transaction:
        // the action manages its own transaction(s). Holding one transaction open across the
        // action would pin an admin connection for the whole request, and the claim below needs
        // a second admin connection (REQUIRES_NEW) — under concurrent idempotent calls every
        // request would hold one connection while waiting for another, deadlocking the pool.
        T response = runUnderAdminScope(action);

        boolean claimed = idempotencyClaimService.claimLspApiIdempotencyRecord(new LspApiIdempotencyRecord(
                lspId,
                operationKey,
                normalizedKey,
                requestFingerprint,
                200,
                serialize(response)
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

    private LspApiIdempotencyRecord findRecord(UUID lspId, String operationKey, String normalizedKey) {
        return adminScopedTransactionExecutor.call(() -> lspApiIdempotencyRecordRepository
                .findByLspIdAndOperationKeyAndIdempotencyKey(lspId, operationKey, normalizedKey)
                .orElse(null));
    }

    private <T> T replayExisting(
            LspApiIdempotencyRecord record,
            String requestFingerprint,
            Class<T> responseType
    ) {
        if (!record.getRequestFingerprint().equals(requestFingerprint)) {
            throw new ApiConflictException(
                    "IDEMPOTENCY_CONFLICT",
                    "Idempotency-Key has already been used for a different request."
            );
        }
        return deserialize(record.getResponseBody(), responseType);
    }

    private <T> T runUnderAdminScope(Supplier<T> action) {
        TenantAccessContext previous = TenantDataAccessContextHolder.snapshot();
        TenantDataAccessContextHolder.useAdmin();
        try {
            return action.get();
        } finally {
            TenantDataAccessContextHolder.restore(previous);
        }
    }

    private <T> T recoverAfterIdempotencyRace(
            UUID lspId,
            String operationKey,
            String normalizedKey,
            String requestFingerprint,
            Class<T> responseType
    ) {
        LspApiIdempotencyRecord racedRecord = findRecord(lspId, operationKey, normalizedKey);
        if (racedRecord == null) {
            throw new IllegalStateException(
                    "Idempotency row missing after unique violation for key " + normalizedKey
            );
        }
        return replayExisting(racedRecord, requestFingerprint, responseType);
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
