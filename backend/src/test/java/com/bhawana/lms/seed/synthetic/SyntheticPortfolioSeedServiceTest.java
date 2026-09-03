package com.bhawana.lms.seed.synthetic;

import static org.assertj.core.api.Assertions.assertThat;

import com.bhawana.lms.support.PostgresDataJpaTestSupport;
import com.bhawana.lms.tenant.TenantDataAccessContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "app.seed.synthetic-portfolio.enabled=true",
        "app.seed.synthetic-portfolio.application-count-override=400",
        "app.seed.synthetic-portfolio.lsp-count=2",
        "app.seed.synthetic-portfolio.batch-size=200",
        "app.reports.processing.enabled=false",
        "app.disbursement.worker.enabled=false",
        "app.alert-rules.scheduler-enabled=false",
        "app.rate-limit.enabled=false"
})
class SyntheticPortfolioSeedServiceTest extends PostgresDataJpaTestSupport {

    @Autowired
    private SyntheticPortfolioSeedService seedService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void useAdminContext() {
        TenantDataAccessContextHolder.useAdmin();
    }

    @AfterEach
    void clearContext() {
        TenantDataAccessContextHolder.clear();
    }

    @Test
    void seedsScaledPortfolioWithConstraintSafeData() {
        SyntheticPortfolioSeedService.SeedResult result = seedService.seed();

        assertThat(result.elapsedMs()).isGreaterThanOrEqualTo(0);
        assertThat(count("lsp")).isEqualTo(2);
        assertThat(count("loan_application")).isBetween(390L, 410L);
        assertThat(count("borrower")).isBetween(390L, 410L);
        assertThat(count("loan_account")).isGreaterThan(200L);
        assertThat(count("loan_repayment_schedule_installment")).isGreaterThan(2_000L);
        assertThat(count("loan_payment_transaction")).isGreaterThan(0L);
        assertThat(count("loan_application_intake_audit")).isGreaterThan(0L);
        assertThat(count("api_client")).isEqualTo(2);
    }

    private long count(String table) {
        Long value = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return value == null ? 0L : value;
    }
}
