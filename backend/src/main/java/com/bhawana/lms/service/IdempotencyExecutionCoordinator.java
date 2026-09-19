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
        throwIfRecoveryRequired(record.getResponseBody());
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
        throwIfRecoveryRequired(record.getResponseBody());
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
                    if (!IdempotencyOperationClasses.isReexecutable(operationKey)) {
                        // The prior attempt's outcome is genuinely unknown: it may
                        // have committed work a rollback cannot undo (object storage,
                        // a provider call, an inner REQUIRES_NEW write). Do not
                        // blindly re-run and do not renew the dead lease — park the
                        // record in the terminal recovery-required state instead.
                        throw new RecoveryRequiredSignal();
                    }
                }

                T response = action.get();
                completeLspOrThrow(leaseToken, response);
                return response;
            });
        } catch (RecoveryRequiredSignal signal) {
            return failUnrecoverableLsp(lspId, operationKey, normalizedKey, responseType, leaseToken);
        } catch (RuntimeException exception) {
            idempotencyClaimService.releasePendingLspApiIdempotencyRecord(leaseToken);
            throw exception;
        }
    }

    /**
     * Parks a reclaimed record in the terminal recovery-required state when its
     * operation class forbids blind re-execution. The transition is fenced on this
     * attempt's lease token; if the fence is lost the row was already resolved by
     * someone else, so it is re-read once and answered honestly rather than
     * throwing over a completed record.
     */
    private <T> T failUnrecoverableLsp(
            UUID lspId,
            String operationKey,
            String normalizedKey,
            Class<T> responseType,
            IdempotencyClaimService.LeaseToken leaseToken
    ) {
        if (idempotencyClaimService.markLspApiIdempotencyRecordRecoveryRequired(leaseToken)) {
            log.error(
                    "idempotency_recovery_required scope=lsp operationKey={} idempotencyKey={} attempt={}",
                    operationKey,
                    normalizedKey,
                    leaseToken.attempt()
            );
            throw recoveryRequiredConflict();
        }

        LspApiIdempotencyRecord latest = findLspRecord(lspId, operationKey, normalizedKey);
        if (latest != null) {
            throwIfRecoveryRequired(latest.getResponseBody());
            if (!IdempotencyRecordState.isPending(latest.getResponseBody())) {
                return deserialize(latest.getResponseBody(), responseType);
            }
        }
        throw inProgressConflict(null);
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
                    if (!IdempotencyOperationClasses.isReexecutable(operationKey)) {
                        // Same rule as the LSP path: an unprovable prior outcome is
                        // parked in the terminal recovery-required state, not renewed.
                        throw new RecoveryRequiredSignal();
                    }
                }

                T response = action.get();
                completeAdminOrThrow(leaseToken, response);
                return response;
            });
        } catch (RecoveryRequiredSignal signal) {
            return failUnrecoverableAdmin(operationKey, normalizedKey, responseType, leaseToken);
        } catch (RuntimeException exception) {
            idempotencyClaimService.releasePendingAdminApiIdempotencyRecord(leaseToken);
            throw exception;
        }
    }

    /** Admin-scope counterpart of {@link #failUnrecoverableLsp}. */
    private <T> T failUnrecoverableAdmin(
            String operationKey,
            String normalizedKey,
            Class<T> responseType,
            IdempotencyClaimService.LeaseToken leaseToken
    ) {
        if (idempotencyClaimService.markAdminApiIdempotencyRecordRecoveryRequired(leaseToken)) {
            log.error(
                    "idempotency_recovery_required scope=admin operationKey={} idempotencyKey={} attempt={}",
                    operationKey,
                    normalizedKey,
                    leaseToken.attempt()
            );
            throw recoveryRequiredConflict();
        }

        AdminApiIdempotencyRecord latest = findAdminRecord(operationKey, normalizedKey);
        if (latest != null) {
            throwIfRecoveryRequired(latest.getResponseBody());
            if (!IdempotencyRecordState.isPending(latest.getResponseBody())) {
                return deserialize(latest.getResponseBody(), responseType);
            }
        }
        throw inProgressConflict(null);
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
                throwIfRecoveryRequired(latest.getResponseBody());
                return latest;
            }
        }

        // Final re-check before giving up: an owner that completed between the last
        // poll and now replays its stored response instead of getting a 409. This
        // keeps a zero completion wait honest.
        LspApiIdempotencyRecord latest = findLspRecord(lspId, operationKey, normalizedKey);
        if (latest != null && !IdempotencyRecordState.isPending(latest.getResponseBody())) {
            throwIfRecoveryRequired(latest.getResponseBody());
            return latest;
        }

        throw inProgressConflict(liveLeaseExpiresAt);
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
                throwIfRecoveryRequired(latest.getResponseBody());
                return latest;
            }
        }

        // Same final re-check as the LSP path before answering in-progress.
        AdminApiIdempotencyRecord latest = findAdminRecord(operationKey, normalizedKey);
        if (latest != null && !IdempotencyRecordState.isPending(latest.getResponseBody())) {
            throwIfRecoveryRequired(latest.getResponseBody());
            return latest;
        }

        throw inProgressConflict(liveLeaseExpiresAt);
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

    /**
     * A record parked in the terminal recovery-required state answers every later
     * retry deterministically — no lease to renew, no action to re-run. The row
     * itself stays as evidence for reconciliation.
     */
    private static void throwIfRecoveryRequired(String responseBody) {
        if (IdempotencyRecordState.isRecoveryRequired(responseBody)) {
            throw recoveryRequiredConflict();
        }
    }

    private static ApiConflictException recoveryRequiredConflict() {
        return new ApiConflictException(
                "IDEMPOTENCY_RECOVERY_REQUIRED",
                "The prior request outcome cannot be reconstructed automatically. Escalate for reconciliation."
        );
    }

    /**
     * Retryable in-progress answer. The Retry-After hint is bounded by
     * {@code retry-after-cap-seconds}: it can never span the full remaining lease,
     * so clients retry on a short fixed cadence while the owner's lease is still
     * reclaimable on expiry.
     */
    private ApiConflictException inProgressConflict(Instant liveLeaseExpiresAt) {
        long retryAfterSeconds = liveLeaseExpiresAt == null
                ? properties.getRetryAfterCapSeconds()
                : Math.min(
                        properties.getRetryAfterCapSeconds(),
                        Math.max(1L, ChronoUnit.SECONDS.between(Instant.now(), liveLeaseExpiresAt)));
        return new ApiConflictException(
                "IDEMPOTENCY_IN_PROGRESS",
                "An identical request is still being processed. Retry shortly.",
                retryAfterSeconds
        );
    }

    /**
     * Internal signal thrown inside the action transaction when a reclaimed
     * operation may not be re-executed. It rolls the (empty) recovery transaction
     * back, then the catch block writes the terminal recovery-required marker in
     * its own fenced transaction.
     */
    private static final class RecoveryRequiredSignal extends RuntimeException {
        private RecoveryRequiredSignal() {
            super(null, null, false, false);
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
