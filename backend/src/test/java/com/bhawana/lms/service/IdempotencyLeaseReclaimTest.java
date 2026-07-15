package com.bhawana.lms.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    private UUID seedLsp() {
        UUID lspId = UUID.randomUUID();
        jdbcTemplate.update(
                "insert into lsp (id, code, name, status, webhook_enabled, token_version, enforce_ui_allowlist, enforce_api_allowlist, created_at, updated_at) "
                        + "values (?, ?, ?, ?, false, 0, false, false, current_timestamp, current_timestamp)",
                lspId,
                "LEASE-" + lspId.toString().substring(0, 8).toUpperCase(),
                "Lease Test LSP",
                "ACTIVE"
        );
        return lspId;
    }
}
