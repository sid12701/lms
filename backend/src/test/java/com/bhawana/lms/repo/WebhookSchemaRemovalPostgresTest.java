package com.bhawana.lms.repo;

import static org.assertj.core.api.Assertions.assertThat;

import com.bhawana.lms.support.PostgresDataJpaTestSupport;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Standing guard against the webhook delivery schema being reintroduced.
 *
 * <p>ADR 0007 replaced push-based webhook delivery with a pull-based loan event log; the
 * webhook delivery machinery itself was deleted first, and this asserts its schema footprint
 * — three tables, four {@code lsp} columns, and the seeded dead-letter alert rule, which
 * described a failure mode ("a webhook exhausted its retries") that the pull design cannot
 * produce — never comes back. It also pins the row-level security policies and tenant-role
 * grants on {@code loan_event}, the table whose policies and grants sit right next to the
 * ones the webhook tables used to have: dropping the webhook schema must never regress the
 * loan event log's own tenant isolation.
 *
 * <p>Runs Flyway from the real migration folder against Postgres with {@code ddl-auto:
 * validate}, so a failure here means the schema actually built from migrations disagrees
 * with this contract — not that a fixture drifted.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class WebhookSchemaRemovalPostgresTest extends PostgresDataJpaTestSupport {

    private static final List<String> EXPECTED_SURVIVING_ALERT_RULE_CODES = List.of(
            "STALE_INTAKE",
            "STUCK_DISBURSEMENT",
            "DPD_BUCKET_TRANSITION",
            "LSP_AUTO_REJECT_SPIKE",
            "BORROWER_ACTIVE_LOAN_DUPLICATE",
            "RATE_LIMIT_BREACH",
            "AUTH_BRUTE_FORCE",
            "AUTH_BRUTE_FORCE_DISTRIBUTED",
            "OLDEST_TRANSACTION_AGE"
    );

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void webhookTablesNoLongerExist() {
        assertThat(regclass("public.webhook_event_outbox")).isNull();
        assertThat(regclass("public.webhook_event_delivery_attempt")).isNull();
        assertThat(regclass("public.webhook_outbox_redrive_audit")).isNull();
    }

    @Test
    void lspHasNoWebhookColumns() {
        Integer webhookColumnCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_name = 'lsp' AND column_name LIKE 'webhook%'",
                Integer.class
        );

        assertThat(webhookColumnCount).isZero();
    }

    @Test
    void webhookRowLevelSecurityPoliciesAreGoneAndLoanEventPoliciesSurviveUndisturbed() {
        List<String> webhookPolicies = jdbcTemplate.queryForList(
                "SELECT policyname FROM pg_policies WHERE tablename LIKE 'webhook%'",
                String.class
        );
        assertThat(webhookPolicies).isEmpty();

        List<String> loanEventPolicies = jdbcTemplate.queryForList(
                "SELECT policyname FROM pg_policies WHERE tablename = 'loan_event' ORDER BY policyname",
                String.class
        );
        assertThat(loanEventPolicies)
                .containsExactly("loan_event_tenant_insert_policy", "loan_event_tenant_select_policy");
    }

    @Test
    void webhookTenantGrantsAreGoneAndLoanEventGrantsSurviveUndisturbed() {
        List<String> webhookGrantTables = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.role_table_grants WHERE table_name LIKE 'webhook%'",
                String.class
        );
        assertThat(webhookGrantTables).isEmpty();

        List<String> loanEventTenantPrivileges = jdbcTemplate.queryForList(
                "SELECT privilege_type FROM information_schema.role_table_grants "
                        + "WHERE table_name = 'loan_event' AND grantee = 'lms_tenant_app' "
                        + "ORDER BY privilege_type",
                String.class
        );
        assertThat(loanEventTenantPrivileges).containsExactly("INSERT", "SELECT");
    }

    @Test
    void deadLetterAlertRuleIsRetiredAndTheOtherNineRulesSurvive() {
        Integer deadLetterRuleCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM alert_rule WHERE code = 'WEBHOOK_DEAD_LETTER'",
                Integer.class
        );
        assertThat(deadLetterRuleCount).isZero();

        List<String> survivingCodes = jdbcTemplate.queryForList("SELECT code FROM alert_rule", String.class);
        assertThat(survivingCodes).containsExactlyInAnyOrderElementsOf(EXPECTED_SURVIVING_ALERT_RULE_CODES);
    }

    private String regclass(String qualifiedTableName) {
        return jdbcTemplate.queryForObject("SELECT to_regclass(?)::text", String.class, qualifiedTableName);
    }
}
