package com.bhawana.lms.service;

import com.bhawana.lms.common.correlation.CorrelationIdHolder;
import com.bhawana.lms.domain.WebhookEventDeliveryAttempt;
import com.bhawana.lms.domain.WebhookEventDeliveryAttemptStatus;
import com.bhawana.lms.domain.WebhookEventOutbox;
import com.bhawana.lms.domain.WebhookEventOutboxStatus;
import com.bhawana.lms.repo.WebhookEventDeliveryAttemptRepository;
import com.bhawana.lms.repo.WebhookEventOutboxRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns transactional boundaries for webhook outbox claim and per-row delivery.
 * Called from {@link WebhookOutboxService#dispatchPending} via a separate Spring bean so
 * {@code @Transactional} applies (self-invocation on the service would not).
 */
@Service
public class WebhookOutboxDispatchExecutor {

    private static final Logger log = LoggerFactory.getLogger(WebhookOutboxDispatchExecutor.class);
    private static final HexFormat HEX = HexFormat.of();

    private final WebhookEventOutboxRepository webhookEventOutboxRepository;
    private final WebhookEventDeliveryAttemptRepository webhookEventDeliveryAttemptRepository;
    private final WebhookDeliveryClient webhookDeliveryClient;
    private final AlertRuleEvaluationService alertRuleEvaluationService;

    public WebhookOutboxDispatchExecutor(
            WebhookEventOutboxRepository webhookEventOutboxRepository,
            WebhookEventDeliveryAttemptRepository webhookEventDeliveryAttemptRepository,
            WebhookDeliveryClient webhookDeliveryClient,
            @Lazy AlertRuleEvaluationService alertRuleEvaluationService
    ) {
        this.webhookEventOutboxRepository = webhookEventOutboxRepository;
        this.webhookEventDeliveryAttemptRepository = webhookEventDeliveryAttemptRepository;
        this.webhookDeliveryClient = webhookDeliveryClient;
        this.alertRuleEvaluationService = alertRuleEvaluationService;
    }

    @Transactional
    public java.util.List<WebhookEventOutbox> claimBatch(Instant now, int batchSize, Instant claimExpiresAt) {
        return webhookEventOutboxRepository.claimDispatchBatch(now, batchSize, claimExpiresAt);
    }

    @Transactional
    public WebhookOutboxService.DeliveryOutcome deliverOne(UUID eventId) {
        WebhookEventOutbox event = webhookEventOutboxRepository.findById(eventId)
                .orElseThrow(() -> new IllegalStateException("Webhook outbox event not found: " + eventId));

        if (event.getStatus() != WebhookEventOutboxStatus.IN_FLIGHT) {
            log.warn(
                    "webhook_outbox_skip_delivery eventId={} status={}",
                    eventId,
                    event.getStatus()
            );
            return WebhookOutboxService.DeliveryOutcome.RETRYABLE_FAILURE;
        }

        String previousCorrelationId = CorrelationIdHolder.get();
        if (event.getCorrelationId() != null && !event.getCorrelationId().isBlank()) {
            CorrelationIdHolder.set(event.getCorrelationId());
        }
        try {
            return dispatchEvent(event);
        } finally {
            if (previousCorrelationId == null || previousCorrelationId.isBlank()) {
                CorrelationIdHolder.clear();
            } else {
                CorrelationIdHolder.set(previousCorrelationId);
            }
        }
    }

    private WebhookOutboxService.DeliveryOutcome dispatchEvent(WebhookEventOutbox event) {
        Instant attemptedAt = Instant.now();
        int attemptNumber = event.getAttemptCount() + 1;
        WebhookDeliveryClient.WebhookDeliveryRequest request = buildDeliveryRequest(event, attemptedAt);

        try {
            WebhookDeliveryClient.WebhookDeliveryResponse response = webhookDeliveryClient.deliver(request);
            WebhookEventDeliveryAttemptStatus attemptStatus = classify(response.statusCode());
            webhookEventDeliveryAttemptRepository.save(new WebhookEventDeliveryAttempt(
                    event,
                    attemptNumber,
                    request.endpointUrl(),
                    request.eventType(),
                    request.deliveryId(),
                    request.timestamp(),
                    request.signature(),
                    response.statusCode(),
                    response.responseBody(),
                    null,
                    attemptStatus
            ));

            if (attemptStatus == WebhookEventDeliveryAttemptStatus.SUCCESS) {
                event.markDelivered(attemptedAt);
                webhookEventOutboxRepository.save(event);
                log.info(
                        "webhook_outbox_event_processed eventId={} lspId={} eventType={} aggregateType={} aggregateId={} correlationId={} outcome={} attemptNumber={} httpStatus={}",
                        event.getId(),
                        event.getLsp().getId(),
                        event.getEventType(),
                        event.getAggregateType(),
                        event.getAggregateId(),
                        event.getCorrelationId(),
                        WebhookOutboxService.DeliveryOutcome.DELIVERED,
                        attemptNumber,
                        response.statusCode()
                );
                return WebhookOutboxService.DeliveryOutcome.DELIVERED;
            }

            String errorMessage = "Webhook delivery failed with HTTP status " + response.statusCode() + ".";
            if (attemptStatus == WebhookEventDeliveryAttemptStatus.RETRYABLE_FAILURE) {
                event.markRetryableFailure(attemptedAt, attemptedAt.plusSeconds(calculateBackoffSeconds(attemptNumber)), errorMessage);
                webhookEventOutboxRepository.save(event);
                log.info(
                        "webhook_outbox_event_processed eventId={} lspId={} eventType={} aggregateType={} aggregateId={} correlationId={} outcome={} attemptNumber={} httpStatus={}",
                        event.getId(),
                        event.getLsp().getId(),
                        event.getEventType(),
                        event.getAggregateType(),
                        event.getAggregateId(),
                        event.getCorrelationId(),
                        WebhookOutboxService.DeliveryOutcome.RETRYABLE_FAILURE,
                        attemptNumber,
                        response.statusCode()
                );
                return WebhookOutboxService.DeliveryOutcome.RETRYABLE_FAILURE;
            }

            event.markPermanentFailure(attemptedAt, errorMessage);
            webhookEventOutboxRepository.save(event);
            alertRuleEvaluationService.emitWebhookDeadLetter(event, errorMessage);
            log.info(
                    "webhook_outbox_event_processed eventId={} lspId={} eventType={} aggregateType={} aggregateId={} correlationId={} outcome={} attemptNumber={} httpStatus={}",
                    event.getId(),
                    event.getLsp().getId(),
                    event.getEventType(),
                    event.getAggregateType(),
                    event.getAggregateId(),
                    event.getCorrelationId(),
                    WebhookOutboxService.DeliveryOutcome.PERMANENT_FAILURE,
                    attemptNumber,
                    response.statusCode()
            );
            return WebhookOutboxService.DeliveryOutcome.PERMANENT_FAILURE;
        } catch (RuntimeException exception) {
            webhookEventDeliveryAttemptRepository.save(new WebhookEventDeliveryAttempt(
                    event,
                    attemptNumber,
                    request.endpointUrl(),
                    request.eventType(),
                    request.deliveryId(),
                    request.timestamp(),
                    request.signature(),
                    null,
                    null,
                    exception.getMessage(),
                    WebhookEventDeliveryAttemptStatus.RETRYABLE_FAILURE
            ));
            event.markRetryableFailure(
                    attemptedAt,
                    attemptedAt.plusSeconds(calculateBackoffSeconds(attemptNumber)),
                    exception.getMessage()
            );
            webhookEventOutboxRepository.save(event);
            log.warn(
                    "webhook_outbox_event_processed eventId={} lspId={} eventType={} aggregateType={} aggregateId={} correlationId={} outcome={} attemptNumber={} errorMessage={}",
                    event.getId(),
                    event.getLsp().getId(),
                    event.getEventType(),
                    event.getAggregateType(),
                    event.getAggregateId(),
                    event.getCorrelationId(),
                    WebhookOutboxService.DeliveryOutcome.RETRYABLE_FAILURE,
                    attemptNumber,
                    exception.getMessage()
            );
            return WebhookOutboxService.DeliveryOutcome.RETRYABLE_FAILURE;
        }
    }

    private WebhookDeliveryClient.WebhookDeliveryRequest buildDeliveryRequest(WebhookEventOutbox event, Instant attemptedAt) {
        String timestamp = String.valueOf(attemptedAt.getEpochSecond());
        String signature = signPayload(event.getLsp().getWebhookSigningSecret(), timestamp, event.getPayloadJson());
        return new WebhookDeliveryClient.WebhookDeliveryRequest(
                event.getLsp().getWebhookEndpointUrl(),
                event.getPayloadJson(),
                event.getCorrelationId(),
                event.getEventType().name(),
                event.getId().toString(),
                timestamp,
                "v1=" + signature
        );
    }

    private static String signPayload(String signingSecret, String timestamp, String payloadJson) {
        if (signingSecret == null || signingSecret.isBlank()) {
            throw new IllegalStateException("Webhook signing secret is not configured.");
        }

        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] signature = mac.doFinal((timestamp + "." + payloadJson).getBytes(StandardCharsets.UTF_8));
            return HEX.formatHex(signature);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to sign webhook payload.", exception);
        }
    }

    private static WebhookEventDeliveryAttemptStatus classify(int statusCode) {
        if (statusCode >= 200 && statusCode < 300) {
            return WebhookEventDeliveryAttemptStatus.SUCCESS;
        }
        if (statusCode == 408 || statusCode == 429 || statusCode >= 500) {
            return WebhookEventDeliveryAttemptStatus.RETRYABLE_FAILURE;
        }
        return WebhookEventDeliveryAttemptStatus.PERMANENT_FAILURE;
    }

    private static long calculateBackoffSeconds(int attemptNumber) {
        int exponent = Math.min(Math.max(attemptNumber - 1, 0), 5);
        return Math.min(60L * (1L << exponent), 3600L);
    }
}
