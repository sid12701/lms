package com.bhawana.lms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bhawana.lms.domain.OpsAlertType;
import com.bhawana.lms.repo.OpsAlertRepository;
import com.bhawana.lms.support.IntegrationTestDatabaseCleaner;
import com.bhawana.lms.support.TenantContextTestExecutionListener;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * M06 acceptance: the threshold the API displays is the threshold the evaluator
 * enforces, because both read the single typed {@code app.alert-rules.*} source.
 *
 * <p>This context overrides {@code stale-intake-hours} to 2 (the production default
 * is 24). A 3-hour-old INITIALIZED application must alert — it would NOT under the
 * seeded {@code config_json} copy that used to be displayed ({@code staleHours: 24})
 * — while a 1-hour-old application stays quiet. The API must then report 2, proving
 * the displayed boundary and the evaluated boundary move together.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "app.alert-rules.stale-intake-hours=2")
@TestExecutionListeners(
        value = TenantContextTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS
)
class AlertRuleEffectiveConfigIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AlertRuleEvaluationWorker alertRuleEvaluationWorker;

    @Autowired
    private OpsAlertRepository opsAlertRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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
    void overriddenThresholdDrivesEvaluationAndIsWhatTheApiDisplays() throws Exception {
        UUID staleApplication = seedInitializedApplication("STALE-3H", Instant.now().minus(3, ChronoUnit.HOURS));
        UUID freshApplication = seedInitializedApplication("FRESH-1H", Instant.now().minus(1, ChronoUnit.HOURS));

        alertRuleEvaluationWorker.evaluateScheduledRules();

        List<String> alertedSubjects = opsAlertRepository.findAll().stream()
                .filter(alert -> alert.getType() == OpsAlertType.STALE_INTAKE)
                .map(alert -> alert.getSubjectId() == null ? null : alert.getSubjectId().toString())
                .toList();
        assertThat(alertedSubjects)
                .as("the 3h-old application crosses the configured 2h boundary")
                .containsExactly(staleApplication.toString());
        assertThat(alertedSubjects).doesNotContain(freshApplication.toString());

        // The same boundary, surfaced read-only through the rules API.
        mockMvc.perform(get("/api/v1/internal/alerts/rules")
                        .with(jwt()
                                .jwt(jwt -> jwt
                                        .subject("ops.admin")
                                        .claim("roles", List.of("SYSTEM_ADMIN")))
                                .authorities(() -> "ROLE_SYSTEM_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.code=='STALE_INTAKE')].effectiveConfig.staleHours").value(2))
                .andExpect(jsonPath("$[?(@.code=='STALE_INTAKE')].configSource", org.hamcrest.Matchers.everyItem(is("application-config"))))
                .andExpect(jsonPath("$[*].configJson").doesNotExist());
    }

    private UUID seedInitializedApplication(String label, Instant createdAt) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        UUID lspId = UUID.randomUUID();
        UUID borrowerId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID productVersionId = UUID.randomUUID();
        UUID applicationId = UUID.randomUUID();

        jdbcTemplate.update(
                "INSERT INTO lsp (id, code, name, status, token_version, enforce_ui_allowlist, enforce_api_allowlist, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'ACTIVE', 0, false, false, current_timestamp, current_timestamp)",
                lspId, "LSP-" + suffix, "Effective Config LSP " + suffix
        );
        jdbcTemplate.update(
                "INSERT INTO borrower (id, full_name, pan, mobile) VALUES (?, ?, ?, ?)",
                borrowerId, "Borrower " + label, "P" + suffix.substring(0, 7), "9999999999"
        );
        jdbcTemplate.update(
                "INSERT INTO loan_product (id, code, name, min_principal, max_principal, interest_rate, "
                        + "processing_fee_rate, min_tenure_months, max_tenure_months) "
                        + "VALUES (?, ?, ?, 100.00, 100000.00, 10.00, 1.00, 6, 60)",
                productId, "PRD-" + suffix, "Effective Config Product"
        );
        jdbcTemplate.update(
                "INSERT INTO loan_product_version (id, loan_product_id, version_number, min_principal, max_principal, "
                        + "interest_rate, processing_fee_rate, min_tenure_months, max_tenure_months, effective_from) "
                        + "VALUES (?, ?, 1, 100.00, 100000.00, 10.00, 1.00, 6, 60, NOW())",
                productVersionId, productId
        );
        jdbcTemplate.update(
                "INSERT INTO loan_application (id, borrower_id, lsp_id, loan_product_id, loan_product_version_id, "
                        + "external_loan_id, source_channel, requested_amount, tenure_months, status, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 'API', 5000.00, 12, 'INITIALIZED', ?)",
                applicationId, borrowerId, lspId, productId, productVersionId,
                label + "-" + suffix, Timestamp.from(createdAt)
        );
        return applicationId;
    }
}
