package com.bhawana.lms.service;

import com.bhawana.lms.domain.Lsp;
import com.bhawana.lms.domain.LspStatus;
import com.bhawana.lms.domain.OpsAlertStatus;
import com.bhawana.lms.domain.OpsAlertType;
import com.bhawana.lms.domain.WebhookEventOutbox;
import com.bhawana.lms.domain.WebhookEventOutboxStatus;
import com.bhawana.lms.domain.WebhookEventType;
import com.bhawana.lms.domain.WebhookOutboxRedriveAudit;
import com.bhawana.lms.repo.LspRepository;
import com.bhawana.lms.repo.OpsAlertRepository;
import com.bhawana.lms.repo.WebhookEventDeliveryAttemptRepository;
import com.bhawana.lms.repo.WebhookEventOutboxRepository;
import com.bhawana.lms.repo.WebhookOutboxRedriveAuditRepository;
import com.bhawana.lms.support.TenantContextTestExecutionListener;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestExecutionListeners(
        value = TenantContextTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS
)
class WebhookOutboxSoftFourxxAndRedriveTest {

    private static final String ENDPOINT = "https://partner.example/webhooks";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WebhookOutboxService webhookOutboxService;

    @Autowired
    private WebhookEventOutboxRepository webhookEventOutboxRepository;

    @Autowired
    private WebhookEventDeliveryAttemptRepository webhookEventDeliveryAttemptRepository;

    @Autowired
    private WebhookOutboxRedriveAuditRepository webhookOutboxRedriveAuditRepository;

    @Autowired
    private OpsAlertRepository opsAlertRepository;

    @Autowired
    private LspRepository lspRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private WebhookDeliveryClient webhookDeliveryClient;

    @BeforeEach
    void setUp() {
        webhookOutboxRedriveAuditRepository.deleteAllInBatch();
        webhookEventDeliveryAttemptRepository.deleteAllInBatch();
        webhookEventOutboxRepository.deleteAllInBatch();
        opsAlertRepository.deleteAllInBatch();
        lspRepository.deleteAllInBatch();
    }

    @Test
    void webhook404BecomesPermanentOnFirstAttemptWithDeadLetterAlert() {
        Lsp lsp = saveWebhookLsp("HARD404");
        WebhookEventOutbox event = savePendingEvent(lsp, "evt-404");

        given(webhookDeliveryClient.deliver(any()))
                .willReturn(new WebhookDeliveryClient.WebhookDeliveryResponse(404, "not found"));

        WebhookOutboxService.DispatchSummary summary = webhookOutboxService.dispatchPending(1);

        assertThat(summary.permanentFailures()).isEqualTo(1);
        assertThat(summary.retryableFailures()).isZero();

        WebhookEventOutbox updated = webhookEventOutboxRepository.findById(event.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(WebhookEventOutboxStatus.PERMANENT_FAILURE);
        assertThat(updated.getAttemptCount()).isEqualTo(1);
        assertThat(updated.getNextAttemptAt()).isNull();
        assertThat(opsAlertRepository.existsByTypeAndSubjectIdAndStatus(
                OpsAlertType.WEBHOOK_DEAD_LETTER,
                event.getId(),
                OpsAlertStatus.NEW
        )).isTrue();
    }

    @Test
    void webhook410BecomesPermanentOnFirstAttempt() {
        Lsp lsp = saveWebhookLsp("HARD410");
        WebhookEventOutbox event = savePendingEvent(lsp, "evt-410");

        given(webhookDeliveryClient.deliver(any()))
                .willReturn(new WebhookDeliveryClient.WebhookDeliveryResponse(410, "gone"));

        WebhookOutboxService.DispatchSummary summary = webhookOutboxService.dispatchPending(1);

        assertThat(summary.permanentFailures()).isEqualTo(1);
        WebhookEventOutbox updated = webhookEventOutboxRepository.findById(event.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(WebhookEventOutboxStatus.PERMANENT_FAILURE);
        assertThat(updated.getAttemptCount()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404, 410, 422})
    void nonSoftClientErrorsRemainPermanentOnFirstAttempt(int statusCode) {
        Lsp lsp = saveWebhookLsp("HARD-" + statusCode);
        WebhookEventOutbox event = savePendingEvent(lsp, "evt-" + statusCode);

        given(webhookDeliveryClient.deliver(any()))
                .willReturn(new WebhookDeliveryClient.WebhookDeliveryResponse(statusCode, "client error"));

        WebhookOutboxService.DispatchSummary summary = webhookOutboxService.dispatchPending(1);

        assertThat(summary.permanentFailures()).isEqualTo(1);
        WebhookEventOutbox updated = webhookEventOutboxRepository.findById(event.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(WebhookEventOutboxStatus.PERMANENT_FAILURE);
        assertThat(updated.getAttemptCount()).isEqualTo(1);
    }

    @Test
    void redrivePermanentEventResetsToPendingAndWritesAuditRow() throws Exception {
        Lsp lsp = saveWebhookLsp("REDRIVE");
        WebhookEventOutbox event = driveToPermanentFailure(lsp, "evt-redrive", 401);

        mockMvc.perform(post("/api/v1/internal/admin/webhook-outbox/{id}/redrive", event.getId())
                        .with(systemAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.attemptCount").value(0))
                .andExpect(jsonPath("$.redriveCount").value(1))
                .andExpect(jsonPath("$.lastError").doesNotExist());

        List<WebhookOutboxRedriveAudit> auditRows =
                webhookOutboxRedriveAuditRepository.findByWebhookEvent_IdOrderByCreatedAtDesc(event.getId());
        assertThat(auditRows).hasSize(1);
        assertThat(auditRows.getFirst().getActorUsername()).isEqualTo("ops.admin");
        assertThat(auditRows.getFirst().getLspId()).isEqualTo(lsp.getId());
        assertThat(auditRows.getFirst().getRedriveCount()).isEqualTo(1);
    }

    @Test
    void redriveRejectedWhenEventIsNotPermanent() throws Exception {
        Lsp lsp = saveWebhookLsp("PENDING");
        WebhookEventOutbox event = savePendingEvent(lsp, "evt-pending");

        mockMvc.perform(post("/api/v1/internal/admin/webhook-outbox/{id}/redrive", event.getId())
                        .with(systemAdmin()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WEBHOOK_OUTBOX_NOT_REDRIVABLE"));
    }

    @Test
    void redriveRejectedAfterThreeRedrives() throws Exception {
        Lsp lsp = saveWebhookLsp("CAP");
        WebhookEventOutbox event = driveToPermanentFailure(lsp, "evt-cap-redrive", 401);

        for (int redrive = 1; redrive <= 3; redrive++) {
            mockMvc.perform(post("/api/v1/internal/admin/webhook-outbox/{id}/redrive", event.getId())
                            .with(systemAdmin()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.redriveCount").value(redrive));
            driveToPermanentFailureOnExisting(event.getId(), 401);
        }

        mockMvc.perform(post("/api/v1/internal/admin/webhook-outbox/{id}/redrive", event.getId())
                        .with(systemAdmin()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WEBHOOK_OUTBOX_REDRIVE_CAP_EXCEEDED"));
    }

    @Test
    void redriveEndpointRequiresSystemAdmin() throws Exception {
        Lsp lsp = saveWebhookLsp("RBAC");
        WebhookEventOutbox event = driveToPermanentFailure(lsp, "evt-rbac", 401);

        mockMvc.perform(post("/api/v1/internal/admin/webhook-outbox/{id}/redrive", event.getId())
                        .with(opsUser()))
                .andExpect(status().isForbidden());
    }

    @Test
    void redrivenEventReEntersDispatchLoop() throws Exception {
        Lsp lsp = saveWebhookLsp("LOOP");
        WebhookEventOutbox event = driveToPermanentFailure(lsp, "evt-loop", 401);

        mockMvc.perform(post("/api/v1/internal/admin/webhook-outbox/{id}/redrive", event.getId())
                        .with(systemAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));

        given(webhookDeliveryClient.deliver(any()))
                .willReturn(new WebhookDeliveryClient.WebhookDeliveryResponse(202, "accepted"));

        WebhookOutboxService.DispatchSummary summary = webhookOutboxService.dispatchPending(1);
        assertThat(summary.delivered()).isEqualTo(1);

        WebhookEventOutbox delivered = webhookEventOutboxRepository.findById(event.getId()).orElseThrow();
        assertThat(delivered.getStatus()).isEqualTo(WebhookEventOutboxStatus.DELIVERED);
    }

    @Test
    void retryableFailureAtCapBecomesPermanentWithDeadLetter() {
        Lsp lsp = saveWebhookLsp("CAP503");
        WebhookEventOutbox event = savePendingEvent(lsp, "evt-cap-503");
        seedAttemptCount(event.getId(), 7);

        given(webhookDeliveryClient.deliver(any()))
                .willReturn(new WebhookDeliveryClient.WebhookDeliveryResponse(503, "unavailable"));

        WebhookOutboxService.DispatchSummary summary = webhookOutboxService.dispatchPending(1);

        assertThat(summary.permanentFailures()).isEqualTo(1);
        assertThat(summary.retryableFailures()).isZero();

        WebhookEventOutbox updated = webhookEventOutboxRepository.findById(event.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(WebhookEventOutboxStatus.PERMANENT_FAILURE);
        assertThat(updated.getAttemptCount()).isEqualTo(8);
        assertThat(updated.getNextAttemptAt()).isNull();
        assertThat(updated.getLastError()).contains("retries exhausted after 8 attempts");
        assertThat(opsAlertRepository.existsByTypeAndSubjectIdAndStatus(
                OpsAlertType.WEBHOOK_DEAD_LETTER,
                event.getId(),
                OpsAlertStatus.NEW
        )).isTrue();
    }

    @Test
    void transportFailureAtCapBecomesPermanentWithDeadLetter() {
        Lsp lsp = saveWebhookLsp("CAP-TRANSPORT");
        WebhookEventOutbox event = savePendingEvent(lsp, "evt-cap-transport");
        seedAttemptCount(event.getId(), 7);

        given(webhookDeliveryClient.deliver(any()))
                .willThrow(new RuntimeException("connection reset"));

        WebhookOutboxService.DispatchSummary summary = webhookOutboxService.dispatchPending(1);

        assertThat(summary.permanentFailures()).isEqualTo(1);
        assertThat(summary.retryableFailures()).isZero();

        WebhookEventOutbox updated = webhookEventOutboxRepository.findById(event.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(WebhookEventOutboxStatus.PERMANENT_FAILURE);
        assertThat(updated.getAttemptCount()).isEqualTo(8);
        assertThat(updated.getNextAttemptAt()).isNull();
        assertThat(updated.getLastError()).contains("connection reset");
        assertThat(opsAlertRepository.existsByTypeAndSubjectIdAndStatus(
                OpsAlertType.WEBHOOK_DEAD_LETTER,
                event.getId(),
                OpsAlertStatus.NEW
        )).isTrue();
    }

    @Test
    void redriveExhaustedRetryableEventResetsBudgetAndDelivers() throws Exception {
        Lsp lsp = saveWebhookLsp("REDRIVE-EXHAUST");
        WebhookEventOutbox event = savePendingEvent(lsp, "evt-redrive-exhaust");
        seedAttemptCount(event.getId(), 7);

        given(webhookDeliveryClient.deliver(any()))
                .willReturn(new WebhookDeliveryClient.WebhookDeliveryResponse(503, "unavailable"));
        webhookOutboxService.dispatchPending(1);

        WebhookEventOutbox exhausted = webhookEventOutboxRepository.findById(event.getId()).orElseThrow();
        assertThat(exhausted.getStatus()).isEqualTo(WebhookEventOutboxStatus.PERMANENT_FAILURE);
        assertThat(exhausted.getAttemptCount()).isEqualTo(8);

        mockMvc.perform(post("/api/v1/internal/admin/webhook-outbox/{id}/redrive", event.getId())
                        .with(systemAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.attemptCount").value(0));

        given(webhookDeliveryClient.deliver(any()))
                .willReturn(new WebhookDeliveryClient.WebhookDeliveryResponse(202, "accepted"));

        WebhookOutboxService.DispatchSummary summary = webhookOutboxService.dispatchPending(1);
        assertThat(summary.delivered()).isEqualTo(1);

        WebhookEventOutbox delivered = webhookEventOutboxRepository.findById(event.getId()).orElseThrow();
        assertThat(delivered.getStatus()).isEqualTo(WebhookEventOutboxStatus.DELIVERED);
        assertThat(delivered.getAttemptCount()).isEqualTo(1);
    }

    private void seedAttemptCount(UUID eventId, int attemptCount) {
        jdbcTemplate.update(
                """
                        UPDATE webhook_event_outbox
                        SET attempt_count = ?,
                            status = 'PENDING',
                            next_attempt_at = NULL,
                            claim_expires_at = NULL
                        WHERE id = ?
                        """,
                attemptCount,
                eventId
        );
    }

    private WebhookEventOutbox driveToPermanentFailure(Lsp lsp, String aggregateId, int statusCode) {
        WebhookEventOutbox event = savePendingEvent(lsp, aggregateId);
        given(webhookDeliveryClient.deliver(any()))
                .willReturn(new WebhookDeliveryClient.WebhookDeliveryResponse(statusCode, "failed"));
        webhookOutboxService.dispatchPending(1);
        return webhookEventOutboxRepository.findById(event.getId()).orElseThrow();
    }

    private void driveToPermanentFailureOnExisting(UUID eventId, int statusCode) {
        given(webhookDeliveryClient.deliver(any()))
                .willReturn(new WebhookDeliveryClient.WebhookDeliveryResponse(statusCode, "failed"));
        makeDueForDispatch(eventId);
        webhookOutboxService.dispatchPending(1);
    }

    private void makeDueForDispatch(UUID eventId) {
        jdbcTemplate.update(
                """
                        UPDATE webhook_event_outbox
                        SET next_attempt_at = ?
                        WHERE id = ?
                        """,
                Timestamp.from(Instant.now().minusSeconds(120)),
                eventId
        );
    }

    private Lsp saveWebhookLsp(String code) {
        Lsp lsp = new Lsp(code, code + " Finance", LspStatus.ACTIVE);
        lsp.updateWebhookSubscription(
                true,
                ENDPOINT,
                "test-signing-secret-" + code,
                List.of(WebhookEventType.LOAN_CREATED)
        );
        return lspRepository.saveAndFlush(lsp);
    }

    private WebhookEventOutbox savePendingEvent(Lsp lsp, String aggregateId) {
        return webhookEventOutboxRepository.saveAndFlush(new WebhookEventOutbox(
                lsp,
                WebhookEventType.LOAN_CREATED,
                "LOAN_APPLICATION",
                aggregateId,
                null,
                WebhookEventOutboxStatus.PENDING,
                "{\"aggregateId\":\"" + aggregateId + "\"}",
                "correlation-" + aggregateId
        ));
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor systemAdmin() {
        return jwt().jwt(token -> token.subject("ops.admin").claim("roles", List.of("SYSTEM_ADMIN")))
                .authorities(() -> "ROLE_SYSTEM_ADMIN");
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor opsUser() {
        return jwt().jwt(token -> token.subject("ops.user").claim("roles", List.of("OPS_USER")))
                .authorities(() -> "ROLE_OPS_USER");
    }
}
