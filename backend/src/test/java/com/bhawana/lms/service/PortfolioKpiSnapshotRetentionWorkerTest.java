package com.bhawana.lms.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.bhawana.lms.domain.Lsp;
import com.bhawana.lms.domain.PortfolioKpiSnapshot;
import com.bhawana.lms.repo.LspRepository;
import com.bhawana.lms.repo.PortfolioKpiSnapshotRepository;
import com.bhawana.lms.support.IntegrationTestDatabaseCleaner;
import com.bhawana.lms.support.TenantContextTestExecutionListener;
import com.bhawana.lms.tenant.TenantScopedExecution;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * L04 acceptance (real Postgres): derived KPI snapshots older than the retention
 * window are purged in bounded batches; the newest row per scope (per-LSP and the
 * global NULL-lsp scope) is preserved even when it is itself past the window, so a
 * stale scope still serves its last reading instead of blanking.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "app.portfolio-kpi.retention-days=30",
        "app.portfolio-kpi.purge-batch-size=2"
})
@TestExecutionListeners(
        value = TenantContextTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS
)
class PortfolioKpiSnapshotRetentionWorkerTest {

    @Autowired
    private PortfolioKpiSnapshotRetentionWorker worker;

    @Autowired
    private PortfolioKpiSnapshotRepository snapshotRepository;

    @Autowired
    private LspRepository lspRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private IntegrationTestDatabaseCleaner integrationTestDatabaseCleaner;

    @BeforeEach
    void setUp() {
        integrationTestDatabaseCleaner.cleanIntegrationTestData();
    }

    @AfterEach
    void tearDown() {
        integrationTestDatabaseCleaner.cleanIntegrationTestData();
    }

    @Test
    void purgeDeletesExpiredHistoryButKeepsLatestRowPerScope() {
        Lsp lsp = seedLsp("KPI-A");
        Instant now = Instant.now();
        Instant beyondWindow = now.minus(90, ChronoUnit.DAYS);
        Instant withinWindow = now.minus(10, ChronoUnit.DAYS);

        PortfolioKpiSnapshot lspOld = saveSnapshot(lsp, beyondWindow);
        PortfolioKpiSnapshot lspRecent = saveSnapshot(lsp, withinWindow);
        // An LSP whose only snapshot is older than the window: still its latest.
        Lsp silentLsp = seedLsp("KPI-B");
        PortfolioKpiSnapshot silentLspOnly = saveSnapshot(silentLsp, beyondWindow.minus(30, ChronoUnit.DAYS));

        PortfolioKpiSnapshot globalOld = saveSnapshot(null, beyondWindow);
        PortfolioKpiSnapshot globalRecent = saveSnapshot(null, withinWindow);

        TenantScopedExecution.runAsAdmin(worker::purgeExpiredSnapshotsUnderAdminScope);

        Set<UUID> surviving = snapshotRepository.findAll().stream()
                .map(PortfolioKpiSnapshot::getId)
                .collect(Collectors.toSet());
        assertThat(surviving)
                .as("within-window rows and each scope's newest row survive")
                .contains(lspRecent.getId(), silentLspOnly.getId(), globalRecent.getId());
        assertThat(surviving)
                .as("superseded expired history is purged")
                .doesNotContain(lspOld.getId(), globalOld.getId());
    }

    @Test
    void purgeIsBoundedPerBatch() {
        Instant beyondWindow = Instant.now().minus(90, ChronoUnit.DAYS);
        for (int i = 0; i < 5; i++) {
            saveSnapshot(null, beyondWindow.minus(i, ChronoUnit.HOURS));
        }

        // purge-batch-size=2 (test property): one call deletes at most one batch.
        int first = new TransactionTemplate(transactionManager).execute(status ->
                snapshotRepository.deleteExpiredBatchPreservingLatest(Instant.now(), 2));
        assertThat(first).isEqualTo(2);

        TenantScopedExecution.runAsAdmin(worker::purgeExpiredSnapshotsUnderAdminScope);
        // The newest of the five (smallest age) is the global scope's latest row —
        // preserved even though it is past the window.
        assertThat(snapshotRepository.count()).isEqualTo(1);
    }

    private Lsp seedLsp(String codePrefix) {
        UUID lspId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO lsp (id, code, name, status, token_version, enforce_ui_allowlist, enforce_api_allowlist, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'ACTIVE', 0, false, false, current_timestamp, current_timestamp)",
                lspId,
                codePrefix + "-" + lspId.toString().substring(0, 6).toUpperCase(),
                "KPI Retention LSP"
        );
        return TenantScopedExecution.callAsAdmin(() -> lspRepository.findById(lspId).orElseThrow());
    }

    private PortfolioKpiSnapshot saveSnapshot(Lsp lsp, Instant computedAt) {
        return TenantScopedExecution.callAsAdmin(() -> snapshotRepository.save(new PortfolioKpiSnapshot(
                lsp,
                computedAt,
                new BigDecimal("1000.00"),
                new BigDecimal("500.00"),
                new BigDecimal("0.00"),
                objectMapper.createObjectNode().put("ACTIVE", 1),
                objectMapper.createObjectNode().put("CURRENT", 1)
        )));
    }
}
