package com.bhawana.lms.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bhawana.lms.common.api.error.ApiConflictException;
import com.bhawana.lms.domain.AdminApiIdempotencyRecord;
import com.bhawana.lms.domain.Lsp;
import com.bhawana.lms.domain.LspApiIdempotencyRecord;
import com.bhawana.lms.domain.LspStatus;
import com.bhawana.lms.repo.AdminApiIdempotencyRecordRepository;
import com.bhawana.lms.repo.LspApiIdempotencyRecordRepository;
import com.bhawana.lms.repo.LspRepository;
import com.bhawana.lms.support.IntegrationTestDatabaseCleaner;
import com.bhawana.lms.support.TenantContextTestExecutionListener;
import com.bhawana.lms.tenant.TenantDataAccessContextHolder;
import com.bhawana.lms.tenant.TenantScopedExecution;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestExecutionListeners;

/**
 * H17/H18 coordinator behavior: reclaimed pending records are classified —
 * database-atomic and externally-idempotent operations re-execute or recover
 * under the owner/attempt fence, while unprovable outcomes transition to a
 * terminal recovery-required state instead of endlessly renewing a dead lease.
 * Live duplicates get a fast bounded {@code IDEMPOTENCY_IN_PROGRESS}.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestExecutionListeners(
        value = TenantContextTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS
)
class IdempotencyRecoveryCoordinatorTest {

    @Autowired
    private LspApiIdempotencyService lspApiIdempotencyService;

    @Autowired
    private AdminApiIdempotencyService adminApiIdempotencyService;

    @Autowired
    private IdempotencyClaimService idempotencyClaimService;

    @Autowired
    private LspApiIdempotencyRecordRepository lspApiIdempotencyRecordRepository;

    @Autowired
    private AdminApiIdempotencyRecordRepository adminApiIdempotencyRecordRepository;

    @Autowired
    private LspRepository lspRepository;

    @Autowired
    private IntegrationTestDatabaseCleaner integrationTestDatabaseCleaner;

    @Autowired
    private ObjectMapper objectMapper;

    private UUID lspId;

    @BeforeEach
    void setUp() {
        integrationTestDatabaseCleaner.cleanIntegrationTestData();
        lspApiIdempotencyRecordRepository.deleteAllInBatch();
        adminApiIdempotencyRecordRepository.deleteAllInBatch();
        Lsp lsp = lspRepository.saveAndFlush(new Lsp(
                "RECOV-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                "Recovery Test LSP",
                LspStatus.ACTIVE
        ));
        lspId = lsp.getId();
    }

    @Test
    void reclaimedDatabaseAtomicLspOperationReexecutesOnceAndCompletes() {
        String operationKey = "LOAN_APPLICATION_INVALIDATION";
        String idempotencyKey = UUID.randomUUID().toString();
        RequestBody request = new RequestBody("same-body");
        LspApiIdempotencyRecord record = seedExpiredPendingLsp(operationKey, idempotencyKey, request);

        AtomicInteger executions = new AtomicInteger();
        String response = asTenant(lspId, () -> lspApiIdempotencyService.execute(
                lspId,
                operationKey,
                idempotencyKey,
                request,
                String.class,
                () -> {
                    executions.incrementAndGet();
                    return "executed-once";
                }
        ));

        assertEquals("executed-once", response);
        assertEquals(1, executions.get());

        LspApiIdempotencyRecord completed = findLspRecord(operationKey, idempotencyKey);
        assertFalse(IdempotencyRecordState.isPending(completed.getResponseBody()));
        assertNull(completed.getLeaseOwner());
        assertNull(completed.getLeaseExpiresAt());
        assertEquals(record.getAttempt() + 1, completed.getAttempt());

        // A later identical retry replays the stored response without re-running.
        String replayed = asTenant(lspId, () -> lspApiIdempotencyService.execute(
                lspId,
                operationKey,
                idempotencyKey,
                request,
                String.class,
                () -> {
                    executions.incrementAndGet();
                    return "must-not-run";
                }
        ));
        assertEquals("executed-once", replayed);
        assertEquals(1, executions.get());
    }

    @Test
    void reclaimedDatabaseAtomicAdminOperationReexecutesOnceAndCompletes() {
        String operationKey = "PRODUCT_CREATE";
        String idempotencyKey = UUID.randomUUID().toString();
        RequestBody request = new RequestBody("same-body");
        AdminApiIdempotencyRecord record = seedExpiredPendingAdmin(operationKey, idempotencyKey, request);

        AtomicInteger executions = new AtomicInteger();
        String response = adminApiIdempotencyService.execute(
                operationKey,
                idempotencyKey,
                request,
                String.class,
                () -> {
                    executions.incrementAndGet();
                    return "admin-executed-once";
                }
        );

        assertEquals("admin-executed-once", response);
        assertEquals(1, executions.get());

        AdminApiIdempotencyRecord completed = findAdminRecord(operationKey, idempotencyKey);
        assertFalse(IdempotencyRecordState.isPending(completed.getResponseBody()));
        assertNull(completed.getLeaseOwner());
        assertEquals(record.getAttempt() + 1, completed.getAttempt());
    }

    @Test
    void reclaimedUnclassifiableLspOperationParksTerminalRecoveryRequired() {
        String operationKey = "UNAUDITED_OPERATION";
        String idempotencyKey = UUID.randomUUID().toString();
        RequestBody request = new RequestBody("same-body");
        LspApiIdempotencyRecord record = seedExpiredPendingLsp(operationKey, idempotencyKey, request);
        int attemptAfterFirstReclaim = record.getAttempt() + 1;

        AtomicInteger executions = new AtomicInteger();
        ApiConflictException first = assertThrows(ApiConflictException.class, () ->
                asTenant(lspId, () -> lspApiIdempotencyService.execute(
                        lspId,
                        operationKey,
                        idempotencyKey,
                        request,
                        String.class,
                        () -> {
                            executions.incrementAndGet();
                            return "must-not-run";
                        }
                )));
        assertEquals("IDEMPOTENCY_RECOVERY_REQUIRED", first.getErrorCode());
        assertEquals(0, executions.get(), "unprovable outcome must not be blindly re-executed");

        LspApiIdempotencyRecord terminal = findLspRecord(operationKey, idempotencyKey);
        assertTrue(IdempotencyRecordState.isRecoveryRequired(terminal.getResponseBody()));
        assertNull(terminal.getLeaseOwner(), "terminal record must not carry a renewable lease");
        assertNull(terminal.getLeaseExpiresAt());
        assertEquals(attemptAfterFirstReclaim, terminal.getAttempt());

        // Every later retry gets the same deterministic answer — no reclaim, no
        // lease renewal, no attempt bump (the previous dead-lease renewal loop).
        for (int i = 0; i < 3; i++) {
            ApiConflictException retry = assertThrows(ApiConflictException.class, () ->
                    asTenant(lspId, () -> lspApiIdempotencyService.execute(
                            lspId,
                            operationKey,
                            idempotencyKey,
                            request,
                            String.class,
                            () -> "must-not-run"
                    )));
            assertEquals("IDEMPOTENCY_RECOVERY_REQUIRED", retry.getErrorCode());
        }

        LspApiIdempotencyRecord stillTerminal = findLspRecord(operationKey, idempotencyKey);
        assertTrue(IdempotencyRecordState.isRecoveryRequired(stillTerminal.getResponseBody()));
        assertEquals(attemptAfterFirstReclaim, stillTerminal.getAttempt());
        assertNull(stillTerminal.getLeaseOwner());
        assertNull(stillTerminal.getLeaseExpiresAt());
    }

    @Test
    void reclaimedUnclassifiableAdminOperationParksTerminalRecoveryRequired() {
        String operationKey = "UNAUDITED_ADMIN_OPERATION";
        String idempotencyKey = UUID.randomUUID().toString();
        RequestBody request = new RequestBody("same-body");
        AdminApiIdempotencyRecord record = seedExpiredPendingAdmin(operationKey, idempotencyKey, request);
        int attemptAfterFirstReclaim = record.getAttempt() + 1;

        ApiConflictException first = assertThrows(ApiConflictException.class, () ->
                adminApiIdempotencyService.execute(
                        operationKey,
                        idempotencyKey,
                        request,
                        String.class,
                        () -> "must-not-run"
                ));
        assertEquals("IDEMPOTENCY_RECOVERY_REQUIRED", first.getErrorCode());

        AdminApiIdempotencyRecord terminal = findAdminRecord(operationKey, idempotencyKey);
        assertTrue(IdempotencyRecordState.isRecoveryRequired(terminal.getResponseBody()));
        assertNull(terminal.getLeaseOwner());
        assertEquals(attemptAfterFirstReclaim, terminal.getAttempt());

        ApiConflictException retry = assertThrows(ApiConflictException.class, () ->
                adminApiIdempotencyService.execute(
                        operationKey,
                        idempotencyKey,
                        request,
                        String.class,
                        () -> "must-not-run"
                ));
        assertEquals("IDEMPOTENCY_RECOVERY_REQUIRED", retry.getErrorCode());
        assertEquals(attemptAfterFirstReclaim,
                findAdminRecord(operationKey, idempotencyKey).getAttempt());
    }

    @Test
    void staleOwnerCannotCompleteOrReleaseAfterReclaim() {
        String operationKey = "LOAN_APPLICATION_INVALIDATION";
        String idempotencyKey = UUID.randomUUID().toString();
        RequestBody request = new RequestBody("same-body");
        LspApiIdempotencyRecord record = seedExpiredPendingLsp(operationKey, idempotencyKey, request);

        String response = asTenant(lspId, () -> lspApiIdempotencyService.execute(
                lspId,
                operationKey,
                idempotencyKey,
                request,
                String.class,
                () -> "winner"
        ));
        assertEquals("winner", response);

        // The pre-crash owner still holds its attempt-1 token: its completion must
        // lose the fence and its release must not delete the completed record.
        IdempotencyClaimService.LeaseToken staleToken =
                new IdempotencyClaimService.LeaseToken(record.getId(), record.getAttempt(), "dead-worker");
        assertFalse(TenantScopedExecution.callAsAdmin(() ->
                idempotencyClaimService.completeLspApiIdempotencyRecord(
                        staleToken, 200, "\"stale-winner\"")));
        TenantScopedExecution.runAsAdmin(() ->
                idempotencyClaimService.releasePendingLspApiIdempotencyRecord(staleToken));

        LspApiIdempotencyRecord after = findLspRecord(operationKey, idempotencyKey);
        assertFalse(IdempotencyRecordState.isPending(after.getResponseBody()));
        assertEquals("\"winner\"", after.getResponseBody());
    }

    @Test
    void failedReexecutionReleasesClaimSoFreshRetryCompletes() {
        String operationKey = "LOAN_APPLICATION_INVALIDATION";
        String idempotencyKey = UUID.randomUUID().toString();
        RequestBody request = new RequestBody("same-body");
        seedExpiredPendingLsp(operationKey, idempotencyKey, request);

        AtomicInteger executions = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> asTenant(lspId, () ->
                lspApiIdempotencyService.execute(
                        lspId,
                        operationKey,
                        idempotencyKey,
                        request,
                        String.class,
                        () -> {
                            executions.incrementAndGet();
                            throw new IllegalStateException("crash during action");
                        }
                )));

        // The failed attempt released its claim; a fresh retry starts clean.
        assertTrue(TenantScopedExecution.callAsAdmin(() -> lspApiIdempotencyRecordRepository
                .findByLspIdAndOperationKeyAndIdempotencyKey(lspId, operationKey, idempotencyKey)
                .isEmpty()));

        String response = asTenant(lspId, () -> lspApiIdempotencyService.execute(
                lspId,
                operationKey,
                idempotencyKey,
                request,
                String.class,
                () -> {
                    executions.incrementAndGet();
                    return "second-attempt";
                }
        ));
        assertEquals("second-attempt", response);
        assertEquals(2, executions.get());
        assertFalse(IdempotencyRecordState.isPending(
                findLspRecord(operationKey, idempotencyKey).getResponseBody()));
    }

    @Test
    void liveLspDuplicateReturnsInProgressFastWithBoundedRetryAfter() {
        String operationKey = "LOAN_APPLICATION_INVALIDATION";
        String idempotencyKey = UUID.randomUUID().toString();
        RequestBody request = new RequestBody("same-body");
        seedLivePendingLsp(operationKey, idempotencyKey, request);

        long started = System.nanoTime();
        ApiConflictException conflict = assertThrows(ApiConflictException.class, () ->
                asTenant(lspId, () -> lspApiIdempotencyService.execute(
                        lspId,
                        operationKey,
                        idempotencyKey,
                        request,
                        String.class,
                        () -> "must-not-run"
                )));
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;

        assertEquals("IDEMPOTENCY_IN_PROGRESS", conflict.getErrorCode());
        assertTrue(conflict.getRetryAfterSeconds().isPresent());
        assertTrue(conflict.getRetryAfterSeconds().get() >= 1
                        && conflict.getRetryAfterSeconds().get() <= 5,
                "Retry-After must be bounded by the cap, not the remaining lease");
        assertTrue(elapsedMillis < 10_000,
                "a duplicate must not occupy the request thread for the old 30s poll window");
    }

    @Test
    void liveAdminDuplicateReturnsInProgressFastWithBoundedRetryAfter() {
        String operationKey = "PRODUCT_CREATE";
        String idempotencyKey = UUID.randomUUID().toString();
        RequestBody request = new RequestBody("same-body");
        seedLivePendingAdmin(operationKey, idempotencyKey, request);

        long started = System.nanoTime();
        ApiConflictException conflict = assertThrows(ApiConflictException.class, () ->
                adminApiIdempotencyService.execute(
                        operationKey,
                        idempotencyKey,
                        request,
                        String.class,
                        () -> "must-not-run"
                ));
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;

        assertEquals("IDEMPOTENCY_IN_PROGRESS", conflict.getErrorCode());
        assertTrue(conflict.getRetryAfterSeconds().isPresent());
        assertTrue(conflict.getRetryAfterSeconds().get() <= 5);
        assertTrue(elapsedMillis < 10_000);
    }

    @Test
    void changedPayloadOnRecoveryRequiredRecordStillConflicts() {
        String operationKey = "UNAUDITED_OPERATION";
        String idempotencyKey = UUID.randomUUID().toString();
        seedExpiredPendingLsp(operationKey, idempotencyKey, new RequestBody("original"));

        assertThrows(ApiConflictException.class, () -> asTenant(lspId, () ->
                lspApiIdempotencyService.execute(
                        lspId,
                        operationKey,
                        idempotencyKey,
                        new RequestBody("original"),
                        String.class,
                        () -> "must-not-run"
                )));

        // Fingerprint conflict wins over the terminal marker: a different payload
        // under the same key is a payload conflict, not a recovery question.
        ApiConflictException conflict = assertThrows(ApiConflictException.class, () ->
                asTenant(lspId, () -> lspApiIdempotencyService.execute(
                        lspId,
                        operationKey,
                        idempotencyKey,
                        new RequestBody("changed"),
                        String.class,
                        () -> "must-not-run"
                )));
        assertEquals("IDEMPOTENCY_CONFLICT", conflict.getErrorCode());
    }

    private LspApiIdempotencyRecord seedExpiredPendingLsp(
            String operationKey,
            String idempotencyKey,
            Object requestFingerprintSource
    ) {
        return seedPendingLsp(
                operationKey,
                idempotencyKey,
                requestFingerprintSource,
                "dead-worker",
                Instant.now().minus(5, ChronoUnit.MINUTES)
        );
    }

    private LspApiIdempotencyRecord seedLivePendingLsp(
            String operationKey,
            String idempotencyKey,
            Object requestFingerprintSource
    ) {
        return seedPendingLsp(
                operationKey,
                idempotencyKey,
                requestFingerprintSource,
                "busy-worker",
                Instant.now().plus(60, ChronoUnit.SECONDS)
        );
    }

    private LspApiIdempotencyRecord seedPendingLsp(
            String operationKey,
            String idempotencyKey,
            Object requestFingerprintSource,
            String leaseOwner,
            Instant leaseExpiresAt
    ) {
        LspApiIdempotencyRecord record = new LspApiIdempotencyRecord(
                lspId,
                operationKey,
                idempotencyKey,
                IdempotencyFingerprinter.fingerprint(objectMapper, requestFingerprintSource),
                IdempotencyRecordState.PENDING_RESPONSE_STATUS,
                IdempotencyRecordState.PENDING_RESPONSE_BODY
        );
        record.stampLease(leaseOwner, leaseExpiresAt);
        return TenantScopedExecution.callAsAdmin(() -> lspApiIdempotencyRecordRepository.save(record));
    }

    private AdminApiIdempotencyRecord seedExpiredPendingAdmin(
            String operationKey,
            String idempotencyKey,
            Object requestFingerprintSource
    ) {
        AdminApiIdempotencyRecord record = new AdminApiIdempotencyRecord(
                operationKey,
                idempotencyKey,
                IdempotencyFingerprinter.fingerprint(objectMapper, requestFingerprintSource),
                IdempotencyRecordState.PENDING_RESPONSE_STATUS,
                IdempotencyRecordState.PENDING_RESPONSE_BODY
        );
        record.stampLease("dead-worker", Instant.now().minus(5, ChronoUnit.MINUTES));
        return TenantScopedExecution.callAsAdmin(() -> adminApiIdempotencyRecordRepository.save(record));
    }

    private AdminApiIdempotencyRecord seedLivePendingAdmin(
            String operationKey,
            String idempotencyKey,
            Object requestFingerprintSource
    ) {
        AdminApiIdempotencyRecord record = new AdminApiIdempotencyRecord(
                operationKey,
                idempotencyKey,
                IdempotencyFingerprinter.fingerprint(objectMapper, requestFingerprintSource),
                IdempotencyRecordState.PENDING_RESPONSE_STATUS,
                IdempotencyRecordState.PENDING_RESPONSE_BODY
        );
        record.stampLease("busy-worker", Instant.now().plus(60, ChronoUnit.SECONDS));
        return TenantScopedExecution.callAsAdmin(() -> adminApiIdempotencyRecordRepository.save(record));
    }

    private LspApiIdempotencyRecord findLspRecord(String operationKey, String idempotencyKey) {
        return TenantScopedExecution.callAsAdmin(() -> lspApiIdempotencyRecordRepository
                .findByLspIdAndOperationKeyAndIdempotencyKey(lspId, operationKey, idempotencyKey)
                .orElseThrow());
    }

    private AdminApiIdempotencyRecord findAdminRecord(String operationKey, String idempotencyKey) {
        return TenantScopedExecution.callAsAdmin(() -> adminApiIdempotencyRecordRepository
                .findByOperationKeyAndIdempotencyKey(operationKey, idempotencyKey)
                .orElseThrow());
    }

    private static <T> T asTenant(UUID tenantLspId, Supplier<T> action) {
        TenantDataAccessContextHolder.useTenant(tenantLspId);
        try {
            return action.get();
        } finally {
            TenantDataAccessContextHolder.clear();
        }
    }

    private record RequestBody(String value) {
    }
}
