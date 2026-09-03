package com.bhawana.lms.service;

import com.bhawana.lms.common.api.error.ApiConflictException;
import com.bhawana.lms.domain.AdminApiIdempotencyRecord;
import com.bhawana.lms.domain.LspApiIdempotencyRecord;
import com.bhawana.lms.repo.AdminApiIdempotencyRecordRepository;
import com.bhawana.lms.repo.LspApiIdempotencyRecordRepository;
import com.bhawana.lms.tenant.AdminScopedTransactionExecutor;
import com.bhawana.lms.tenant.ScopePreservingTransactionExecutor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class IdempotencyExecutionCoordinator {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyExecutionCoordinator.class);

    private final LspApiIdempotencyRecordRepository lspApiIdempotencyRecordRepository;
    private final AdminApiIdempotencyRecordRepository adminApiIdempotencyRecordRepository;
    private final ObjectMapper objectMapper;
    private final IdempotencyClaimService idempotencyClaimService;
    private final IdempotencyRecoveryService idempotencyRecoveryService;
    private final AdminScopedTransactionExecutor adminScopedTransactionExecutor;
    private final ScopePreservingTransactionExecutor scopePreservingTransactionExecutor;
    private final IdempotencyProperties properties;

    public IdempotencyExecutionCoordinator(
            LspApiIdempotencyRecordRepository lspApiIdempotencyRecordRepository,
            AdminApiIdempotencyRecordRepository adminApiIdempotencyRecordRepository,
            ObjectMapper objectMapper,
            IdempotencyClaimService idempotencyClaimService,
            IdempotencyRecoveryService idempotencyRecoveryService,
            AdminScopedTransactionExecutor adminScopedTransactionExecutor,
            ScopePreservingTransactionExecutor scopePreservingTransactionExecutor,
            IdempotencyProperties properties
    ) {
        this.lspApiIdempotencyRecordRepository = lspApiIdempotencyRecordRepository;
        this.adminApiIdempotencyRecordRepository = adminApiIdempotencyRecordRepository;
        this.objectMapper = objectMapper;
        this.idempotencyClaimService = idempotencyClaimService;
        this.idempotencyRecoveryService = idempotencyRecoveryService;
        this.adminScopedTransactionExecutor = adminScopedTransactionExecutor;
        this.scopePreservingTransactionExecutor = scopePreservingTransactionExecutor;
        this.properties = properties;
    }

    public <T> T executeLsp(
            UUID lspId,
            String operationKey,
            String normalizedKey,
            Object requestFingerprintSource,
            Class<T> responseType,
            Supplier<T> action
    ) {
        String requestFingerprint = fingerprint(requestFingerprintSource);
        LspApiIdempotencyRecord existingRecord = findLspRecord(lspId, operationKey, normalizedKey);
        if (existingRecord != null) {
            return replayLsp(
                    existingRecord,
                    lspId,
                    operationKey,
                    normalizedKey,
                    requestFingerprint,
                    requestFingerprintSource,
                    responseType,
                    action
            );
        }

        return claimAndExecuteLsp(
                lspId,
                operationKey,
                normalizedKey,
                requestFingerprint,
                requestFingerprintSource,
                responseType,
                action
        );
    }

    public <T> T executeAdmin(
            String operationKey,
            String normalizedKey,
            Object requestFingerprintSource,
            Class<T> responseType,
            Supplier<T> action
    ) {
        String requestFingerprint = fingerprint(requestFingerprintSource);
        AdminApiIdempotencyRecord existingRecord = findAdminRecord(operationKey, normalizedKey);
        if (existingRecord != null) {
            return replayAdmin(
                    existingRecord,
                    operationKey,
                    normalizedKey,
                    requestFingerprint,
                    requestFingerprintSource,
                    responseType,
                    action
            );
        }

        return claimAndExecuteAdmin(
                operationKey,
                normalizedKey,
                requestFingerprint,
                requestFingerprintSource,
                responseType,
                action
        );
    }

    private <T> T claimAndExecuteLsp(
            UUID lspId,
            String operationKey,
            String normalizedKey,
            String requestFingerprint,
            Object requestFingerprintSource,
            Class<T> responseType,
            Supplier<T> action
    ) {
        LspApiIdempotencyRecord record = newPendingLspRecord(
                lspId,
                operationKey,
                normalizedKey,
                requestFingerprint
        );
        boolean claimed = idempotencyClaimService.claimLspApiIdempotencyRecord(record);
        if (!claimed) {
            LspApiIdempotencyRecord racedRecord = findLspRecord(lspId, operationKey, normalizedKey);
            if (racedRecord == null) {
                throw new IllegalStateException(
                        "Idempotency row missing after unique violation for key " + normalizedKey
                );
            }
            return replayLsp(
                    racedRecord,
                    lspId,
                    operationKey,
                    normalizedKey,
                    requestFingerprint,
                    requestFingerprintSource,
                    responseType,
                    action
            );
        }

        return executeClaimedLsp(
                lspId,
                operationKey,
                normalizedKey,
                requestFingerprintSource,
                responseType,
                action,
                false,
                new IdempotencyClaimService.LeaseToken(
                        record.getId(),
                        record.getAttempt(),
                        properties.getLeaseOwner()
                )
        );
    }

    private <T> T claimAndExecuteAdmin(
            String operationKey,
            String normalizedKey,
            String requestFingerprint,
            Object requestFingerprintSource,
            Class<T> responseType,
            Supplier<T> action
    ) {
        AdminApiIdempotencyRecord record = newPendingAdminRecord(
                operationKey,
                normalizedKey,
                requestFingerprint
        );
        boolean claimed = idempotencyClaimService.claimAdminApiIdempotencyRecord(record);
        if (!claimed) {
            AdminApiIdempotencyRecord racedRecord = findAdminRecord(operationKey, normalizedKey);
            if (racedRecord == null) {
                throw new IllegalStateException(
                        "Idempotency row missing after unique violation for key " + normalizedKey
                );
            }
            return replayAdmin(
                    racedRecord,
                    operationKey,
                    normalizedKey,
                    requestFingerprint,
                    requestFingerprintSource,
                    responseType,
                    action
            );
        }

        return executeClaimedAdmin(
                operationKey,
                normalizedKey,
                requestFingerprintSource,
                responseType,
                action,
                false,
                new IdempotencyClaimService.LeaseToken(
                        record.getId(),
                        record.getAttempt(),
                        properties.getLeaseOwner()
                )
        );
    }

    private <T> T replayLsp(
            LspApiIdempotencyRecord record,
            UUID lspId,
            String operationKey,
            String normalizedKey,
            String requestFingerprint,
            Object requestFingerprintSource,
            Class<T> responseType,
            Supplier<T> action
    ) {
        assertMatchingFingerprint(record.getRequestFingerprint(), requestFingerprint);
        if (!IdempotencyRecordState.isPending(record.getResponseBody())) {
            return deserialize(record.getResponseBody(), responseType);
        }

        Instant now = Instant.now();
        if (isLeaseLive(record.getLeaseExpiresAt(), now)) {
            LspApiIdempotencyRecord completedRecord = awaitLspCompletion(
                    lspId,
                    operationKey,
                    normalizedKey,
                    requestFingerprint,
                    record.getLeaseExpiresAt()
            );
            return deserialize(completedRecord.getResponseBody(), responseType);
        }

        Optional<IdempotencyClaimService.LeaseToken> reclaimedLease = tryReclaimLspLease(record, now);
        if (reclaimedLease.isPresent()) {
            log.info(
                    "idempotency_lease_reclaimed scope=lsp operationKey={} idempotencyKey={}",
                    operationKey,
                    normalizedKey
            );
            return executeClaimedLsp(
                    lspId,
                    operationKey,
                    normalizedKey,
                    requestFingerprintSource,
                    responseType,
                    action,
                    true,
                    reclaimedLease.get()
            );
        }

        LspApiIdempotencyRecord completedRecord = awaitLspCompletion(
                lspId,
                operationKey,
                normalizedKey,
                requestFingerprint,
                null
        );
        return deserialize(completedRecord.getResponseBody(), responseType);
    }

    private <T> T replayAdmin(
            AdminApiIdempotencyRecord record,
            String operationKey,
            String normalizedKey,
            String requestFingerprint,
            Object requestFingerprintSource,
            Class<T> responseType,
            Supplier<T> action
    ) {
        assertMatchingFingerprint(record.getRequestFingerprint(), requestFingerprint);
        if (!IdempotencyRecordState.isPending(record.getResponseBody())) {
            return deserialize(record.getResponseBody(), responseType);
        }

        Instant now = Instant.now();
        if (isLeaseLive(record.getLeaseExpiresAt(), now)) {
            AdminApiIdempotencyRecord completedRecord = awaitAdminCompletion(
                    operationKey,
                    normalizedKey,
                    requestFingerprint,
                    record.getLeaseExpiresAt()
            );
            return deserialize(completedRecord.getResponseBody(), responseType);
        }

        Optional<IdempotencyClaimService.LeaseToken> reclaimedLease = tryReclaimAdminLease(record, now);
        if (reclaimedLease.isPresent()) {
            log.info(
                    "idempotency_lease_reclaimed scope=admin operationKey={} idempotencyKey={}",
                    operationKey,
                    normalizedKey
            );
            return executeClaimedAdmin(
                    operationKey,
                    normalizedKey,
                    requestFingerprintSource,
                    responseType,
                    action,
                    true,
                    reclaimedLease.get()
            );
        }

        AdminApiIdempotencyRecord completedRecord = awaitAdminCompletion(
                operationKey,
                normalizedKey,
                requestFingerprint,
                null
        );
        return deserialize(completedRecord.getResponseBody(), responseType);
    }

    private <T> T executeClaimedLsp(
            UUID lspId,
            String operationKey,
            String normalizedKey,
            Object requestFingerprintSource,
            Class<T> responseType,
            Supplier<T> action,
            boolean attemptRecovery,
            IdempotencyClaimService.LeaseToken leaseToken
    ) {
        if (attemptRecovery && !idempotencyRecoveryService.supports(operationKey, responseType)) {
            log.error(
                    "idempotency_recovery_required scope=lsp operationKey={} idempotencyKey={} attempt={}",
                    operationKey,
                    normalizedKey,
                    leaseToken.attempt()
            );
            throw new ApiConflictException(
                    "IDEMPOTENCY_RECOVERY_REQUIRED",
                    "The prior request outcome cannot be reconstructed automatically. Escalate for reconciliation."
            );
        }

        try {
            return scopePreservingTransactionExecutor.call(() -> {
                if (attemptRecovery) {
                    Optional<T> recovered = idempotencyRecoveryService.tryRecover(
                            lspId,
                            operationKey,
                            requestFingerprintSource,
                            responseType
                    );
                    if (recovered.isPresent()) {
                        T response = recovered.get();
                        completeLspOrThrow(leaseToken, response);
                        log.info(
                                "idempotency_recovered scope=lsp operationKey={} idempotencyKey={}",
                                operationKey,
                                normalizedKey
                        );
                        return response;
                    }
                }

                T response = action.get();
                completeLspOrThrow(leaseToken, response);
                return response;
            });
        } catch (RuntimeException exception) {
            idempotencyClaimService.releasePendingLspApiIdempotencyRecord(leaseToken);
            throw exception;
        }
    }

    private <T> T executeClaimedAdmin(
            String operationKey,
            String normalizedKey,
            Object requestFingerprintSource,
            Class<T> responseType,
            Supplier<T> action,
            boolean attemptRecovery,
            IdempotencyClaimService.LeaseToken leaseToken
    ) {
        if (attemptRecovery && !idempotencyRecoveryService.supports(operationKey, responseType)) {
            log.error(
                    "idempotency_recovery_required scope=admin operationKey={} idempotencyKey={} attempt={}",
                    operationKey,
                    normalizedKey,
                    leaseToken.attempt()
            );
            throw new ApiConflictException(
                    "IDEMPOTENCY_RECOVERY_REQUIRED",
                    "The prior request outcome cannot be reconstructed automatically. Escalate for reconciliation."
            );
        }

        try {
            return adminScopedTransactionExecutor.call(() -> {
                if (attemptRecovery) {
                    Optional<T> recovered = idempotencyRecoveryService.tryRecover(
                            null,
                            operationKey,
                            requestFingerprintSource,
                            responseType
                    );
                    if (recovered.isPresent()) {
                        T response = recovered.get();
                        completeAdminOrThrow(leaseToken, response);
                        log.info(
                                "idempotency_recovered scope=admin operationKey={} idempotencyKey={}",
                                operationKey,
                                normalizedKey
                        );
                        return response;
                    }
                }

                T response = action.get();
                completeAdminOrThrow(leaseToken, response);
                return response;
            });
        } catch (RuntimeException exception) {
            idempotencyClaimService.releasePendingAdminApiIdempotencyRecord(leaseToken);
            throw exception;
        }
    }

    private void completeLspOrThrow(IdempotencyClaimService.LeaseToken leaseToken, Object response) {
        if (!idempotencyClaimService.completeLspApiIdempotencyRecord(leaseToken, 200, serialize(response))) {
            throw new IllegalStateException("Idempotency lease was lost before LSP request completion.");
        }
    }

    private void completeAdminOrThrow(IdempotencyClaimService.LeaseToken leaseToken, Object response) {
        if (!idempotencyClaimService.completeAdminApiIdempotencyRecord(leaseToken, 200, serialize(response))) {
            throw new IllegalStateException("Idempotency lease was lost before admin request completion.");
        }
    }

    private LspApiIdempotencyRecord awaitLspCompletion(
            UUID lspId,
            String operationKey,
            String normalizedKey,
            String requestFingerprint,
            Instant liveLeaseExpiresAt
    ) {
        long pollNanos = TimeUnit.MILLISECONDS.toNanos(50);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(properties.getCompletionWaitSeconds());
        while (System.nanoTime() < deadline) {
            sleepBriefly(pollNanos);
            LspApiIdempotencyRecord latest = findLspRecord(lspId, operationKey, normalizedKey);
            if (latest == null) {
                throw new IllegalStateException(
                        "Idempotency row missing while waiting for completion: " + normalizedKey
                );
            }
            assertMatchingFingerprint(latest.getRequestFingerprint(), requestFingerprint);
            if (!IdempotencyRecordState.isPending(latest.getResponseBody())) {
                return latest;
            }
        }

        LspApiIdempotencyRecord latest = findLspRecord(lspId, operationKey, normalizedKey);
        if (latest != null && !IdempotencyRecordState.isPending(latest.getResponseBody())) {
            return latest;
        }

        long retryAfterSeconds = liveLeaseExpiresAt == null
                ? 5L
                : Math.max(1L, ChronoUnit.SECONDS.between(Instant.now(), liveLeaseExpiresAt));
        throw new ApiConflictException(
                "IDEMPOTENCY_IN_PROGRESS",
                "An identical request is still being processed. Retry shortly.",
                retryAfterSeconds
        );
    }

    private AdminApiIdempotencyRecord awaitAdminCompletion(
            String operationKey,
            String normalizedKey,
            String requestFingerprint,
            Instant liveLeaseExpiresAt
    ) {
        long pollNanos = TimeUnit.MILLISECONDS.toNanos(50);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(properties.getCompletionWaitSeconds());
        while (System.nanoTime() < deadline) {
            sleepBriefly(pollNanos);
            AdminApiIdempotencyRecord latest = findAdminRecord(operationKey, normalizedKey);
            if (latest == null) {
                throw new IllegalStateException(
                        "Idempotency row missing while waiting for completion: " + normalizedKey
                );
            }
            assertMatchingFingerprint(latest.getRequestFingerprint(), requestFingerprint);
            if (!IdempotencyRecordState.isPending(latest.getResponseBody())) {
                return latest;
            }
        }

        AdminApiIdempotencyRecord latest = findAdminRecord(operationKey, normalizedKey);
        if (latest != null && !IdempotencyRecordState.isPending(latest.getResponseBody())) {
            return latest;
        }

        long retryAfterSeconds = liveLeaseExpiresAt == null
                ? 5L
                : Math.max(1L, ChronoUnit.SECONDS.between(Instant.now(), liveLeaseExpiresAt));
        throw new ApiConflictException(
                "IDEMPOTENCY_IN_PROGRESS",
                "An identical request is still being processed. Retry shortly.",
                retryAfterSeconds
        );
    }

    private LspApiIdempotencyRecord newPendingLspRecord(
            UUID lspId,
            String operationKey,
            String normalizedKey,
            String requestFingerprint
    ) {
        LspApiIdempotencyRecord record = new LspApiIdempotencyRecord(
                lspId,
                operationKey,
                normalizedKey,
                requestFingerprint,
                IdempotencyRecordState.PENDING_RESPONSE_STATUS,
                IdempotencyRecordState.PENDING_RESPONSE_BODY
        );
        record.stampLease(properties.getLeaseOwner(), leaseExpiry(Instant.now()));
        return record;
    }

    private AdminApiIdempotencyRecord newPendingAdminRecord(
            String operationKey,
            String normalizedKey,
            String requestFingerprint
    ) {
        AdminApiIdempotencyRecord record = new AdminApiIdempotencyRecord(
                operationKey,
                normalizedKey,
                requestFingerprint,
                IdempotencyRecordState.PENDING_RESPONSE_STATUS,
                IdempotencyRecordState.PENDING_RESPONSE_BODY
        );
        record.stampLease(properties.getLeaseOwner(), leaseExpiry(Instant.now()));
        return record;
    }

    private Instant leaseExpiry(Instant now) {
        return now.plus(properties.getLeaseDurationSeconds(), ChronoUnit.SECONDS);
    }

    private boolean isLeaseLive(Instant leaseExpiresAt, Instant now) {
        return leaseExpiresAt != null && leaseExpiresAt.isAfter(now);
    }

    private Optional<IdempotencyClaimService.LeaseToken> tryReclaimLspLease(
            LspApiIdempotencyRecord record,
            Instant now
    ) {
        return idempotencyClaimService.tryReclaimExpiredLspApiIdempotencyLease(
                record.getId(),
                record.getAttempt(),
                properties.getLeaseOwner(),
                leaseExpiry(now)
        );
    }

    private Optional<IdempotencyClaimService.LeaseToken> tryReclaimAdminLease(
            AdminApiIdempotencyRecord record,
            Instant now
    ) {
        return idempotencyClaimService.tryReclaimExpiredAdminApiIdempotencyLease(
                record.getId(),
                record.getAttempt(),
                properties.getLeaseOwner(),
                leaseExpiry(now)
        );
    }

    private LspApiIdempotencyRecord findLspRecord(UUID lspId, String operationKey, String normalizedKey) {
        return scopePreservingTransactionExecutor.call(() -> lspApiIdempotencyRecordRepository
                .findByLspIdAndOperationKeyAndIdempotencyKey(lspId, operationKey, normalizedKey)
                .orElse(null));
    }

    private AdminApiIdempotencyRecord findAdminRecord(String operationKey, String normalizedKey) {
        return adminScopedTransactionExecutor.call(() -> adminApiIdempotencyRecordRepository
                .findByOperationKeyAndIdempotencyKey(operationKey, normalizedKey)
                .orElse(null));
    }

    private static void assertMatchingFingerprint(String storedFingerprint, String requestFingerprint) {
        if (!storedFingerprint.equals(requestFingerprint)) {
            throw new ApiConflictException(
                    "IDEMPOTENCY_CONFLICT",
                    "Idempotency-Key has already been used for a different request."
            );
        }
    }

    private static void sleepBriefly(long pollNanos) {
        try {
            Thread.sleep(TimeUnit.NANOSECONDS.toMillis(pollNanos));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for idempotent response.", exception);
        }
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
