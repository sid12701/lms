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
// The seed service bean only exists under the approved seeding profiles (L05); the
// dedicated test-data profile is the sanctioned way to reach it from a test context.
@ActiveProfiles({"test", "test-data"})
@TestPropertySource(properties = {
        "app.seed.synthetic-portfolio.enabled=true",
        // Testcontainers shared Postgres runs with database name "lms"; the seed refuses
        // any database that is not explicitly allowlisted.
        "app.seed.synthetic-portfolio.allowed-database-names=lms",
        "app.seed.synthetic-portfolio.application-count-override=400",
        "app.seed.synthetic-portfolio.lsp-count=2",
        "app.seed.synthetic-portfolio.batch-size=200",
        // test-data is deliberately NOT a dev-exempt profile (see DeploymentProfiles), so this
        // context must satisfy UnsafeDeploymentConfigurationValidator and
        // TenantDatasourceSecurityValidator like a real deployment would. The tenant password
        // override feeds both the app.datasource.tenant.password property and the Flyway
        // tenant_app_password placeholder, so the created role matches the login credentials.
        "APP_TENANT_DATASOURCE_PASSWORD=test-tenant-rotated-password",
        "app.security.jwt.secret=test-only-jwt-secret-that-is-at-least-32-chars",
        "app.security.jwt.secure-cookies=true",
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
