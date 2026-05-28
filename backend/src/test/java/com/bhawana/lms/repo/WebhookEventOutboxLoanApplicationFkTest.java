package com.bhawana.lms.repo;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bhawana.lms.support.PostgresDataJpaTestSupport;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class WebhookEventOutboxLoanApplicationFkTest extends PostgresDataJpaTestSupport {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID lspId;
    private UUID loanApplicationId;

    @BeforeEach
    void seedParents() {
        String suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        lspId = insertLsp("LSP-FK-" + suffix);
        UUID borrowerId = insertBorrower("PFK" + suffix.substring(0, 7));
        UUID productId = insertLoanProduct("PROD-FK-" + suffix);
        loanApplicationId = insertLoanApplication(borrowerId, lspId, productId, "EXT-FK-" + suffix);
    }

    @Test
    void outboxRejectsLoanApplicationIdWithoutMatchingApplication() {
        UUID phantomApplicationId = UUID.randomUUID();
        assertThatThrownBy(() -> insertOutbox(lspId, phantomApplicationId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void outboxAcceptsLoanApplicationIdReferencingRealApplication() {
        assertThatCode(() -> insertOutbox(lspId, loanApplicationId))
                .doesNotThrowAnyException();
    }

    @Test
    void outboxAcceptsNullLoanApplicationId() {
        assertThatCode(() -> insertOutbox(lspId, null))
                .doesNotThrowAnyException();
    }

    // ---------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------

    private UUID insertLsp(String code) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO lsp (id, code, name, status) VALUES (?, ?, ?, 'ACTIVE')",
                id, code, "LSP " + code
        );
        return id;
    }

    private UUID insertBorrower(String pan) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO borrower (id, full_name, pan, mobile) VALUES (?, ?, ?, ?)",
                id, "Borrower " + pan, pan, "9999999999"
        );
        return id;
    }

    private UUID insertLoanProduct(String code) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO loan_product (id, code, name, min_principal, max_principal, "
                        + "interest_rate, processing_fee_rate, min_tenure_months, max_tenure_months) "
                        + "VALUES (?, ?, ?, 100.00, 100000.00, 10.00, 1.00, 6, 60)",
                id, code, "Product " + code
        );
        return id;
    }

    private UUID insertLoanApplication(UUID borrowerId, UUID lspId, UUID productId, String externalId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO loan_application (id, borrower_id, lsp_id, loan_product_id, "
                        + "external_loan_id, source_channel, requested_amount, tenure_months, status) "
                        + "VALUES (?, ?, ?, ?, ?, 'API', 5000.00, 12, 'INITIALIZED')",
                id, borrowerId, lspId, productId, externalId
        );
        return id;
    }

    private void insertOutbox(UUID lspId, UUID loanApplicationId) {
        jdbcTemplate.update(
                "INSERT INTO webhook_event_outbox (id, lsp_id, event_type, aggregate_type, aggregate_id, "
                        + "loan_application_id, status, payload_json, correlation_id, attempt_count, entity_version, "
                        + "created_at, updated_at) "
                        + "VALUES (?, ?, 'LOAN_CREATED', 'LOAN_APPLICATION', ?, ?, 'PENDING', '{}'::jsonb, ?, 0, 0, NOW(), NOW())",
                UUID.randomUUID(),
                lspId,
                UUID.randomUUID().toString(),
                loanApplicationId,
                "corr-" + UUID.randomUUID().toString().substring(0, 8)
        );
    }
}
