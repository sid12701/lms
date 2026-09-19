package com.bhawana.lms.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bhawana.lms.domain.LspApiIdempotencyRecord;
import com.bhawana.lms.repo.LspApiIdempotencyRecordRepository;
import com.bhawana.lms.support.TenantContextTestExecutionListener;
import com.bhawana.lms.tenant.TenantScopedExecution;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestExecutionListeners;

@SpringBootTest
@ActiveProfiles("test")
@TestExecutionListeners(
        value = TenantContextTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS
)
class IdempotencyLeaseReclaimTest {

    @Autowired
    private IdempotencyClaimService idempotencyClaimService;

    @Autowired
    private LspApiIdempotencyRecordRepository lspApiIdempotencyRecordRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        TenantScopedExecution.runAsAdmin(lspApiIdempotencyRecordRepository::deleteAll);
    }

    @Test
    void reclaimSucceedsOnlyForExpiredPendingLease() {
        UUID lspId = seedLsp();
        LspApiIdempotencyRecord record = new LspApiIdempotencyRecord(
                lspId,
                "LOAN_APPLICATION_CREATE",
                UUID.randomUUID().toString(),
                "fingerprint",
                IdempotencyRecordState.PENDING_RESPONSE_STATUS,
                IdempotencyRecordState.PENDING_RESPONSE_BODY
        );
        record.stampLease("dead-worker", Instant.now().minus(5, ChronoUnit.MINUTES));
        UUID recordId = TenantScopedExecution.callAsAdmin(() ->
                lspApiIdempotencyRecordRepository.save(record).getId());

        var reclaimedLease = idempotencyClaimService.tryReclaimExpiredLspApiIdempotencyLease(
                recordId,
                1,
                "recovery-worker",
                Instant.now().plus(60, ChronoUnit.SECONDS)
        );
        assertTrue(reclaimedLease.isPresent());

        LspApiIdempotencyRecord reclaimed = TenantScopedExecution.callAsAdmin(() ->
                lspApiIdempotencyRecordRepository.findById(recordId).orElseThrow());
        assertEquals("recovery-worker", reclaimed.getLeaseOwner());
        assertEquals(2, reclaimed.getAttempt());
        assertTrue(reclaimed.getLeaseExpiresAt().isAfter(Instant.now()));

        assertFalse(idempotencyClaimService.tryReclaimExpiredLspApiIdempotencyLease(
                recordId,
                2,
                "another-worker",
                Instant.now().plus(60, ChronoUnit.SECONDS)
        ).isPresent());

        boolean staleWorkerCompleted = idempotencyClaimService.completeLspApiIdempotencyRecord(
                new IdempotencyClaimService.LeaseToken(recordId, 1, "dead-worker"),
                200,
                "{\"worker\":\"stale\"}"
        );
        assertFalse(staleWorkerCompleted);

        LspApiIdempotencyRecord afterStaleCompletion = TenantScopedExecution.callAsAdmin(() ->
                lspApiIdempotencyRecordRepository.findById(recordId).orElseThrow());
        assertEquals(
                IdempotencyRecordState.PENDING_RESPONSE_BODY,
                afterStaleCompletion.getResponseBody(),
                "A worker that lost its lease must not overwrite the current attempt");
    }

    @Test
    void recoveryRequiredMarkIsTerminalAndLeaseFenced() {
        UUID lspId = seedLsp();
        String idempotencyKey = UUID.randomUUID().toString();
        LspApiIdempotencyRecord record = new LspApiIdempotencyRecord(
                lspId,
                "UNAUDITED_OPERATION",
                idempotencyKey,
                "fingerprint",
                IdempotencyRecordState.PENDING_RESPONSE_STATUS,
                IdempotencyRecordState.PENDING_RESPONSE_BODY
        );
        record.stampLease("dead-worker", Instant.now().minus(5, ChronoUnit.MINUTES));
        UUID recordId = TenantScopedExecution.callAsAdmin(() ->
                lspApiIdempotencyRecordRepository.save(record).getId());

        var reclaimedLease = idempotencyClaimService.tryReclaimExpiredLspApiIdempotencyLease(
                recordId,
                1,
                "recovery-worker",
                Instant.now().plus(60, ChronoUnit.SECONDS)
        );
        assertTrue(reclaimedLease.isPresent());

        // A stale attempt cannot park the row: the terminal write is fenced on
        // id + attempt + owner + pending body.
        assertFalse(idempotencyClaimService.markLspApiIdempotencyRecordRecoveryRequired(
                new IdempotencyClaimService.LeaseToken(recordId, 1, "dead-worker")));

        assertTrue(idempotencyClaimService.markLspApiIdempotencyRecordRecoveryRequired(
                reclaimedLease.get()));

        LspApiIdempotencyRecord terminal = TenantScopedExecution.callAsAdmin(() ->
                lspApiIdempotencyRecordRepository.findById(recordId).orElseThrow());
        assertTrue(IdempotencyRecordState.isRecoveryRequired(terminal.getResponseBody()));
        assertNull(terminal.getLeaseOwner());
        assertNull(terminal.getLeaseExpiresAt());

        // Terminal rows are evidence: they are never reclaimed again and a stale
        // owner's completion stays fenced out.
        assertFalse(idempotencyClaimService.tryReclaimExpiredLspApiIdempotencyLease(
                recordId,
                terminal.getAttempt(),
                "another-worker",
                Instant.now().plus(60, ChronoUnit.SECONDS)
        ).isPresent());
        assertFalse(idempotencyClaimService.completeLspApiIdempotencyRecord(
                new IdempotencyClaimService.LeaseToken(recordId, 1, "dead-worker"),
                200,
                "{\"worker\":\"stale\"}"
        ));
        assertTrue(IdempotencyRecordState.isRecoveryRequired(TenantScopedExecution.callAsAdmin(() ->
                lspApiIdempotencyRecordRepository.findById(recordId).orElseThrow()).getResponseBody()));
    }
    private UUID seedLsp() {
        UUID lspId = UUID.randomUUID();
        jdbcTemplate.update(
                "insert into lsp (id, code, name, status, token_version, enforce_ui_allowlist, enforce_api_allowlist, created_at, updated_at) "
                        + "values (?, ?, ?, ?, 0, false, false, current_timestamp, current_timestamp)",
                lspId,
                "LEASE-" + lspId.toString().substring(0, 8).toUpperCase(),
                "Lease Test LSP",
                "ACTIVE"
        );
        return lspId;
    }
}
