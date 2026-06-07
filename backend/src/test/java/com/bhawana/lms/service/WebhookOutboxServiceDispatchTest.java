package com.bhawana.lms.service;

import com.bhawana.lms.support.TenantContextTestExecutionListener;
import org.springframework.test.context.TestExecutionListeners;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.bhawana.lms.domain.Lsp;
import com.bhawana.lms.domain.LspStatus;
import com.bhawana.lms.domain.WebhookEventOutbox;
import com.bhawana.lms.domain.WebhookEventOutboxStatus;
import com.bhawana.lms.domain.WebhookEventType;
import com.bhawana.lms.repo.LspRepository;
import com.bhawana.lms.repo.WebhookEventDeliveryAttemptRepository;
import com.bhawana.lms.repo.WebhookEventOutboxRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@ActiveProfiles("test")
@TestExecutionListeners(
        value = TenantContextTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS
)
class WebhookOutboxServiceDispatchTest {

    private static final String FAST_ENDPOINT = "https://fast.partner.example/webhooks";
    private static final String SLOW_ENDPOINT = "https://slow.partner.example/webhooks";

    @Autowired
    private WebhookOutboxService webhookOutboxService;

    @Autowired
    private WebhookEventOutboxRepository webhookEventOutboxRepository;

    @Autowired
    private WebhookEventDeliveryAttemptRepository webhookEventDeliveryAttemptRepository;

    @Autowired
    private LspRepository lspRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private WebhookDeliveryClient webhookDeliveryClient;

    @BeforeEach
    void setUp() {
        webhookEventDeliveryAttemptRepository.deleteAllInBatch();
        webhookEventOutboxRepository.deleteAllInBatch();
        lspRepository.deleteAllInBatch();

        given(webhookDeliveryClient.deliver(any())).willAnswer(invocation -> {
            WebhookDeliveryClient.WebhookDeliveryRequest request = invocation.getArgument(0);
            if (request.endpointUrl().contains("slow.partner")) {
                Thread.sleep(2_500);
            }
            return new WebhookDeliveryClient.WebhookDeliveryResponse(202, "accepted");
        });
    }

    @Test
    void dispatchPendingDeliversFastAndSlowPartnersInParallel() {
        Lsp fastLsp = saveWebhookLsp("FAST", FAST_ENDPOINT);
        Lsp slowLsp = saveWebhookLsp("SLOW", SLOW_ENDPOINT);

        List<UUID> eventIds = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            eventIds.add(savePendingEvent(fastLsp, "fast-" + index).getId());
        }
        for (int index = 0; index < 5; index++) {
            eventIds.add(savePendingEvent(slowLsp, "slow-" + index).getId());
        }

        long startedAt = System.nanoTime();
        WebhookOutboxService.DispatchSummary summary = webhookOutboxService.dispatchPending(10);
        long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

        assertThat(summary.processed()).isEqualTo(10);
        assertThat(summary.delivered()).isEqualTo(10);
        assertThat(durationMs).isLessThan(8_000L);

        for (UUID eventId : eventIds) {
            WebhookEventOutbox event = webhookEventOutboxRepository.findById(eventId).orElseThrow();
            assertThat(event.getStatus()).isEqualTo(WebhookEventOutboxStatus.DELIVERED);
            assertThat(event.getClaimExpiresAt()).isNull();
        }
    }

    @Test
    void dispatchPendingReclaimsStaleInFlightEvents() {
        Lsp lsp = saveWebhookLsp("RECLAIM", FAST_ENDPOINT);
        WebhookEventOutbox stranded = savePendingEvent(lsp, "stranded-1");
        UUID eventId = stranded.getId();

        jdbcTemplate.update(
                """
                        UPDATE webhook_event_outbox
                        SET status = 'IN_FLIGHT',
                            claim_expires_at = ?
                        WHERE id = ?
                        """,
                java.sql.Timestamp.from(Instant.now().minusSeconds(120)),
                eventId
        );

        WebhookOutboxService.DispatchSummary summary = webhookOutboxService.dispatchPending(5);

        assertThat(summary.processed()).isEqualTo(1);
        assertThat(summary.delivered()).isEqualTo(1);

        WebhookEventOutbox event = webhookEventOutboxRepository.findById(eventId).orElseThrow();
        assertThat(event.getStatus()).isEqualTo(WebhookEventOutboxStatus.DELIVERED);
        assertThat(event.getClaimExpiresAt()).isNull();
    }

    @Test
    void retryableFailureClearsClaimExpiryAndRespectsNextAttemptAt() {
        Lsp lsp = saveWebhookLsp("RETRY", FAST_ENDPOINT);
        WebhookEventOutbox event = savePendingEvent(lsp, "retry-1");

        org.mockito.Mockito.reset(webhookDeliveryClient);
        given(webhookDeliveryClient.deliver(any()))
                .willReturn(new WebhookDeliveryClient.WebhookDeliveryResponse(503, "unavailable"));

        WebhookOutboxService.DispatchSummary firstPass = webhookOutboxService.dispatchPending(5);
        assertThat(firstPass.retryableFailures()).isEqualTo(1);

        WebhookEventOutbox afterFailure = webhookEventOutboxRepository.findById(event.getId()).orElseThrow();
        assertThat(afterFailure.getStatus()).isEqualTo(WebhookEventOutboxStatus.RETRYABLE_FAILURE);
        assertThat(afterFailure.getClaimExpiresAt()).isNull();
        assertThat(afterFailure.getNextAttemptAt()).isAfter(Instant.now());

        WebhookOutboxService.DispatchSummary beforeDue = webhookOutboxService.dispatchPending(5);
        assertThat(beforeDue.processed()).isZero();
    }

    private Lsp saveWebhookLsp(String code, String endpointUrl) {
        Lsp lsp = new Lsp(code, code + " Finance", LspStatus.ACTIVE);
        lsp.updateWebhookSubscription(
                true,
                endpointUrl,
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
}
