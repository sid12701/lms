package com.bhawana.lms.repo;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bhawana.lms.domain.Lsp;
import com.bhawana.lms.domain.LspStatus;
import com.bhawana.lms.domain.WebhookEventDeliveryAttemptStatus;
import com.bhawana.lms.domain.WebhookEventOutbox;
import com.bhawana.lms.domain.WebhookEventOutboxStatus;
import com.bhawana.lms.domain.WebhookEventType;
import com.bhawana.lms.support.PostgresDataJpaTestSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class WebhookEventDeliveryAttemptSentinelDefaultsPostgresIntegrationTest
        extends PostgresDataJpaTestSupport {

    private static final String[] REQUEST_COLUMNS = {
            "request_event_type",
            "request_delivery_id",
            "request_timestamp",
            "request_signature"
    };

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private LspRepository lspRepository;

    @Autowired
    private LspAuditEventRepository lspAuditEventRepository;

    @Autowired
    private WebhookEventOutboxRepository webhookEventOutboxRepository;

    @Autowired
    private WebhookEventDeliveryAttemptRepository deliveryAttemptRepository;

    private UUID outboxEventId;

    @BeforeEach
    void seedOutboxParent() {
        deliveryAttemptRepository.deleteAllInBatch();
        webhookEventOutboxRepository.deleteAllInBatch();
        lspAuditEventRepository.deleteAllInBatch();
        lspRepository.deleteAllInBatch();

        Lsp lsp = lspRepository.saveAndFlush(new Lsp(
                "LSP-F16-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(),
                "F-16 Sentinel Test LSP",
                LspStatus.ACTIVE
        ));
        WebhookEventOutbox outbox = webhookEventOutboxRepository.saveAndFlush(new WebhookEventOutbox(
                lsp,
                WebhookEventType.LOAN_CREATED,
                "loan_application",
                UUID.randomUUID().toString(),
                null,
                WebhookEventOutboxStatus.PENDING,
                "{}",
                "corr-" + UUID.randomUUID()
        ));
        outboxEventId = outbox.getId();
    }

    @Test
    void omittingRequestEventTypeViolatesNotNullConstraint() {
        assertThatThrownBy(() -> insertDeliveryAttemptOmitting("request_event_type"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasStackTraceContaining("request_event_type");
    }

    @Test
    void omittingRequestDeliveryIdViolatesNotNullConstraint() {
        assertThatThrownBy(() -> insertDeliveryAttemptOmitting("request_delivery_id"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasStackTraceContaining("request_delivery_id");
    }

    @Test
    void omittingRequestTimestampViolatesNotNullConstraint() {
        assertThatThrownBy(() -> insertDeliveryAttemptOmitting("request_timestamp"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasStackTraceContaining("request_timestamp");
    }

    @Test
    void omittingRequestSignatureViolatesNotNullConstraint() {
        assertThatThrownBy(() -> insertDeliveryAttemptOmitting("request_signature"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasStackTraceContaining("request_signature");
    }

    private void insertDeliveryAttemptOmitting(String omittedColumn) {
        StringBuilder columns = new StringBuilder(
                "id, outbox_event_id, attempt_number, request_url, status"
        );
        StringBuilder placeholders = new StringBuilder("?, ?, ?, ?, ?");
        List<Object> params = new ArrayList<>();
        params.add(UUID.randomUUID());
        params.add(outboxEventId);
        params.add(1);
        params.add("https://example.test/hook");
        params.add(WebhookEventDeliveryAttemptStatus.SUCCESS.name());

        for (String column : REQUEST_COLUMNS) {
            if (column.equals(omittedColumn)) {
                continue;
            }
            columns.append(", ").append(column);
            placeholders.append(", ?");
            params.add("placeholder");
        }

        String sql = "INSERT INTO webhook_event_delivery_attempt (" + columns
                + ") VALUES (" + placeholders + ")";
        jdbcTemplate.update(sql, params.toArray());
    }
}
