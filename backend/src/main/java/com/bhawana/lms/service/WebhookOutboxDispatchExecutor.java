package com.bhawana.lms.service;

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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the transactional boundaries for webhook outbox dispatch: claim, the read that
 * prepares a signed delivery request, and the write that records its outcome.
 *
 * <p>Sequencing those steps around the (non-transactional) HTTP call — prepare → deliver →
 * record — lives in {@link WebhookOutboxService} on purpose. Each step must be a separate
 * call through this bean's proxy for its {@code @Transactional} to apply; driving them from a
 * single method on this same bean would self-invoke and silently bypass the proxy, running
 * the writes outside the intended boundaries. That is why these methods are {@code public}
 * and invoked from the orchestrating service rather than from one another.
 */
@Service
public class WebhookOutboxDispatchExecutor {

    private static final Logger log = LoggerFactory.getLogger(WebhookOutboxDispatchExecutor.class);
    private static final HexFormat HEX = HexFormat.of();

    private final WebhookEventOutboxRepository webhookEventOutboxRepository;
    private final WebhookEventDeliveryAttemptRepository webhookEventDeliveryAttemptRepository;
    private final OpsAlertEmitters opsAlertEmitters;

    public WebhookOutboxDispatchExecutor(
            WebhookEventOutboxRepository webhookEventOutboxRepository,
            WebhookEventDeliveryAttemptRepository webhookEventDeliveryAttemptRepository,
            OpsAlertEmitters opsAlertEmitters
    ) {
        this.webhookEventOutboxRepository = webhookEventOutboxRepository;
        this.webhookEventDeliveryAttemptRepository = webhookEventDeliveryAttemptRepository;
        this.opsAlertEmitters = opsAlertEmitters;
    }

    @Transactional
    public java.util.List<WebhookEventOutbox> claimBatch(Instant now, int batchSize, Instant claimExpiresAt) {
        return webhookEventOutboxRepository.claimDispatchBatch(now, batchSize, claimExpiresAt);
    }

    @Transactional(readOnly = true)
    public PreparedDelivery prepareDelivery(UUID eventId) {
        WebhookEventOutbox event = webhookEventOutboxRepository.findById(eventId)
                .orElseThrow(() -> new IllegalStateException("Webhook outbox event not found: " + eventId));

        if (event.getStatus() != WebhookEventOutboxStatus.IN_FLIGHT) {
            log.warn(
                    "webhook_outbox_skip_delivery eventId={} status={}",
                    eventId,
                    event.getStatus()
            );
            return null;
        }

        Instant attemptedAt = Instant.now();
        int attemptNumber = event.getAttemptCount() + 1;
        return new PreparedDelivery(
                eventId,
                event.getCorrelationId(),
                attemptNumber,
                attemptedAt,
                buildDeliveryRequest(event, attemptedAt)
        );
    }

    @Transactional
    public WebhookOutboxService.DeliveryOutcome recordDeliveryOutcome(
            PreparedDelivery prepared,
            WebhookDeliveryClient.WebhookDeliveryResponse response,
            RuntimeException deliveryException
    ) {
        WebhookEventOutbox event = webhookEventOutboxRepository.findById(prepared.eventId())
                .orElseThrow(() -> new IllegalStateException("Webhook outbox event not found: " + prepared.eventId()));

        if (event.getStatus() != WebhookEventOutboxStatus.IN_FLIGHT) {
            log.warn(
                    "webhook_outbox_skip_delivery eventId={} status={}",
                    prepared.eventId(),
                    event.getStatus()
            );
            return WebhookOutboxService.DeliveryOutcome.RETRYABLE_FAILURE;
        }

        if (deliveryException != null) {
            return recordRetryableTransportFailure(event, prepared, deliveryException);
        }

        return recordHttpResponse(event, prepared, response);
    }

    private WebhookOutboxService.DeliveryOutcome recordHttpResponse(
            WebhookEventOutbox event,
            PreparedDelivery prepared,
            WebhookDeliveryClient.WebhookDeliveryResponse response
    ) {
        WebhookEventDeliveryAttemptStatus attemptStatus = classify(response.statusCode());
        webhookEventDeliveryAttemptRepository.save(new WebhookEventDeliveryAttempt(
                event,
                prepared.attemptNumber(),
                prepared.request().endpointUrl(),
                prepared.request().eventType(),
                prepared.request().deliveryId(),
                prepared.request().timestamp(),
                prepared.request().signature(),
                response.statusCode(),
                response.responseBody(),
                null,
                attemptStatus
        ));

        if (attemptStatus == WebhookEventDeliveryAttemptStatus.SUCCESS) {
            event.markDelivered(prepared.attemptedAt());
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
                    prepared.attemptNumber(),
                    response.statusCode()
            );
            return WebhookOutboxService.DeliveryOutcome.DELIVERED;
        }

        String errorMessage = "Webhook delivery failed with HTTP status " + response.statusCode() + ".";
        if (attemptStatus == WebhookEventDeliveryAttemptStatus.RETRYABLE_FAILURE) {
            event.markRetryableFailure(
                    prepared.attemptedAt(),
                    prepared.attemptedAt().plusSeconds(calculateBackoffSeconds(prepared.attemptNumber())),
                    errorMessage
            );
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
                    prepared.attemptNumber(),
                    response.statusCode()
            );
            return WebhookOutboxService.DeliveryOutcome.RETRYABLE_FAILURE;
        }

        event.markPermanentFailure(prepared.attemptedAt(), errorMessage);
        webhookEventOutboxRepository.save(event);
        opsAlertEmitters.emitWebhookDeadLetter(event, errorMessage);
        log.info(
                "webhook_outbox_event_processed eventId={} lspId={} eventType={} aggregateType={} aggregateId={} correlationId={} outcome={} attemptNumber={} httpStatus={}",
                event.getId(),
                event.getLsp().getId(),
                event.getEventType(),
                event.getAggregateType(),
                event.getAggregateId(),
                event.getCorrelationId(),
                WebhookOutboxService.DeliveryOutcome.PERMANENT_FAILURE,
                prepared.attemptNumber(),
                response.statusCode()
        );
        return WebhookOutboxService.DeliveryOutcome.PERMANENT_FAILURE;
    }

    private WebhookOutboxService.DeliveryOutcome recordRetryableTransportFailure(
            WebhookEventOutbox event,
            PreparedDelivery prepared,
            RuntimeException exception
    ) {
        webhookEventDeliveryAttemptRepository.save(new WebhookEventDeliveryAttempt(
                event,
                prepared.attemptNumber(),
                prepared.request().endpointUrl(),
                prepared.request().eventType(),
                prepared.request().deliveryId(),
                prepared.request().timestamp(),
                prepared.request().signature(),
                null,
                null,
                exception.getMessage(),
                WebhookEventDeliveryAttemptStatus.RETRYABLE_FAILURE
        ));
        event.markRetryableFailure(
                prepared.attemptedAt(),
                prepared.attemptedAt().plusSeconds(calculateBackoffSeconds(prepared.attemptNumber())),
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
                prepared.attemptNumber(),
                exception.getMessage()
        );
        return WebhookOutboxService.DeliveryOutcome.RETRYABLE_FAILURE;
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

    private WebhookEventDeliveryAttemptStatus classify(int statusCode) {
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

    public record PreparedDelivery(
            UUID eventId,
            String correlationId,
            int attemptNumber,
            Instant attemptedAt,
            WebhookDeliveryClient.WebhookDeliveryRequest request
    ) {
    }
}
