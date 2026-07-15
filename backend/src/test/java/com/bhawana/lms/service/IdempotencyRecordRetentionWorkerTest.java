package com.bhawana.lms.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.bhawana.lms.domain.AdminApiIdempotencyRecord;
import com.bhawana.lms.domain.LspApiIdempotencyRecord;
import com.bhawana.lms.repo.AdminApiIdempotencyRecordRepository;
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
class IdempotencyRecordRetentionWorkerTest {

    @Autowired
    private IdempotencyRecordRetentionWorker worker;

    @Autowired
    private LspApiIdempotencyRecordRepository lspApiIdempotencyRecordRepository;

    @Autowired
    private AdminApiIdempotencyRecordRepository adminApiIdempotencyRecordRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanTables() {
        TenantScopedExecution.runAsAdmin(() -> {
            lspApiIdempotencyRecordRepository.deleteAll();
            adminApiIdempotencyRecordRepository.deleteAll();
        });
    }

    @Test
    void purgeRemovesOnlyExpiredRowsFromBothTables() {
        UUID lspId = seedLsp();

        LspApiIdempotencyRecord freshLsp = TenantScopedExecution.callAsAdmin(() ->
                lspApiIdempotencyRecordRepository.save(new LspApiIdempotencyRecord(
                        lspId,
                        "LOAN_APPLICATION_CREATE",
                        UUID.randomUUID().toString(),
                        "fresh-fingerprint",
                        200,
                        "{}"
                )));
        LspApiIdempotencyRecord oldLsp = TenantScopedExecution.callAsAdmin(() ->
                lspApiIdempotencyRecordRepository.save(new LspApiIdempotencyRecord(
                        lspId,
                        "LOAN_APPLICATION_CREATE",
                        UUID.randomUUID().toString(),
                        "old-fingerprint",
                        200,
                        "{}"
                )));

        AdminApiIdempotencyRecord freshAdmin = TenantScopedExecution.callAsAdmin(() ->
                adminApiIdempotencyRecordRepository.save(new AdminApiIdempotencyRecord(
                        "PRODUCT_CREATE",
                        UUID.randomUUID().toString(),
                        "fresh-fingerprint",
                        200,
                        "{}"
                )));
        AdminApiIdempotencyRecord oldAdmin = TenantScopedExecution.callAsAdmin(() ->
                adminApiIdempotencyRecordRepository.save(new AdminApiIdempotencyRecord(
                        "PRODUCT_CREATE",
                        UUID.randomUUID().toString(),
                        "old-fingerprint",
                        200,
                        "{}"
                )));

        Instant expiredAt = Instant.now().minus(120, ChronoUnit.DAYS);
        jdbcTemplate.update(
                "update lsp_api_idempotency_record set created_at = ?, updated_at = ? where id = ?",
                expiredAt, expiredAt, oldLsp.getId()
        );
        jdbcTemplate.update(
                "update admin_api_idempotency_record set created_at = ?, updated_at = ? where id = ?",
                expiredAt, expiredAt, oldAdmin.getId()
        );

        TenantScopedExecution.runAsAdmin(worker::purgeExpiredRecordsUnderAdminScope);

        assertEquals(1, lspApiIdempotencyRecordRepository.count());
        assertEquals(freshLsp.getId(), lspApiIdempotencyRecordRepository.findAll().get(0).getId());
        assertEquals(1, adminApiIdempotencyRecordRepository.count());
        assertEquals(freshAdmin.getId(), adminApiIdempotencyRecordRepository.findAll().get(0).getId());
    }

    private UUID seedLsp() {
        UUID lspId = UUID.randomUUID();
        jdbcTemplate.update(
                "insert into lsp (id, code, name, status, webhook_enabled, token_version, enforce_ui_allowlist, enforce_api_allowlist, created_at, updated_at) "
                        + "values (?, ?, ?, ?, false, 0, false, false, current_timestamp, current_timestamp)",
                lspId,
                "RET-" + lspId.toString().substring(0, 8).toUpperCase(),
                "Retention Test LSP",
                "ACTIVE"
        );
        return lspId;
    }
}
