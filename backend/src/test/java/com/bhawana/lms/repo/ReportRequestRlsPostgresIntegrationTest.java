package com.bhawana.lms.repo;

import static org.assertj.core.api.Assertions.assertThat;

import com.bhawana.lms.domain.Lsp;
import com.bhawana.lms.domain.LspStatus;
import com.bhawana.lms.domain.ReportRequest;
import com.bhawana.lms.domain.ReportType;
import com.bhawana.lms.support.PostgresDataJpaTestSupport;
import com.bhawana.lms.tenant.TenantDataAccessContextHolder;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@ActiveProfiles("test")
class ReportRequestRlsPostgresIntegrationTest extends PostgresDataJpaTestSupport {

    @Autowired
    private ReportRequestRepository reportRequestRepository;

    @Autowired
    private LspRepository lspRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void cleanReportRequestState() {
        TenantDataAccessContextHolder.clear();
        reportRequestRepository.deleteAllInBatch();
        lspRepository.deleteAllInBatch();
    }

    @AfterEach
    void clearTenantContext() {
        TenantDataAccessContextHolder.clear();
    }

    @Test
    void tenantSeesOnlyItsOwnReportRequestRows() {
        Lsp apex = saveLsp("APEX");
        Lsp north = saveLsp("NORTH");
        saveReportFor(apex);
        saveReportFor(north);

        assertThat(countAsTenant(apex.getId())).isEqualTo(1);
        assertThat(countAsTenant(north.getId())).isEqualTo(1);
    }

    @Test
    void adminScopedReportsAreInvisibleToEveryTenant() {
        Lsp apex = saveLsp("APEX");
        saveReportFor(apex);
        saveReportFor(null);

        assertThat(countAsTenant(apex.getId())).isEqualTo(1);
    }

    @Test
    void adminConnectionReadsEveryReportRequestRowAcrossLsps() {
        Lsp apex = saveLsp("APEX");
        Lsp north = saveLsp("NORTH");
        saveReportFor(apex);
        saveReportFor(north);
        saveReportFor(null);

        assertThat(reportRequestRepository.count()).isEqualTo(3);
    }

    private Lsp saveLsp(String codeSuffix) {
        Lsp lsp = new Lsp(
                "LSP-" + codeSuffix + "-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(),
                "LSP " + codeSuffix,
                LspStatus.ACTIVE
        );
        return lspRepository.saveAndFlush(lsp);
    }

    private void saveReportFor(Lsp lsp) {
        ReportRequest request = new ReportRequest(
                ReportType.PORTFOLIO_MIS,
                lsp,
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 1, 31),
                "ops.admin",
                "ops.admin@example.com"
        );
        reportRequestRepository.saveAndFlush(request);
    }

    private int countAsTenant(UUID lspId) {
        TenantDataAccessContextHolder.useTenant(lspId);
        try {
            return new TransactionTemplate(transactionManager).execute(status ->
                    jdbcTemplate.queryForObject("select count(*) from report_request", Integer.class)
            );
        } finally {
            TenantDataAccessContextHolder.clear();
        }
    }
}
