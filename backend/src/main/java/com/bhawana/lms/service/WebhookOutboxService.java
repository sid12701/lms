package com.bhawana.lms.service;

import com.bhawana.lms.common.correlation.CorrelationIdHolder;
import com.bhawana.lms.domain.Lsp;
import com.bhawana.lms.domain.WebhookEventDeliveryAttempt;
import com.bhawana.lms.domain.WebhookEventDeliveryAttemptStatus;
import com.bhawana.lms.domain.WebhookEventOutbox;
import com.bhawana.lms.domain.WebhookEventOutboxStatus;
import com.bhawana.lms.domain.WebhookEventType;
import com.bhawana.lms.repo.LspRepository;
import com.bhawana.lms.repo.WebhookEventDeliveryAttemptRepository;
import com.bhawana.lms.repo.WebhookEventOutboxRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WebhookOutboxService {

    private final WebhookEventOutboxRepository webhookEventOutboxRepository;
    private final WebhookEventDeliveryAttemptRepository webhookEventDeliveryAttemptRepository;
    private final LspRepository lspRepository;
    private final WebhookDeliveryClient webhookDeliveryClient;
    private final ObjectMapper objectMapper;

    public WebhookOutboxService(
            WebhookEventOutboxRepository webhookEventOutboxRepository,
            WebhookEventDeliveryAttemptRepository webhookEventDeliveryAttemptRepository,
            LspRepository lspRepository,
            WebhookDeliveryClient webhookDeliveryClient,
            ObjectMapper objectMapper
    ) {
        this.webhookEventOutboxRepository = webhookEventOutboxRepository;
        this.webhookEventDeliveryAttemptRepository = webhookEventDeliveryAttemptRepository;
        this.lspRepository = lspRepository;
        this.webhookDeliveryClient = webhookDeliveryClient;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void enqueueIfSubscribed(
            Lsp lsp,
            WebhookEventType eventType,
            String aggregateType,
            String aggregateId,
            Map<String, Object> payload
    ) {
        if (lsp == null || !lsp.isWebhookEnabled() || !lsp.getWebhookEventTypes().contains(eventType)) {
            return;
        }

        LinkedHashMap<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventType", eventType.name());
        envelope.put("aggregateType", aggregateType);
        envelope.put("aggregateId", aggregateId);
        envelope.put("lspId", lsp.getId());
        envelope.put("lspCode", lsp.getCode());
        envelope.put("payload", payload);

        webhookEventOutboxRepository.save(new WebhookEventOutbox(
                lsp,
                eventType,
                aggregateType,
                aggregateId,
                WebhookEventOutboxStatus.PENDING,
                serializePayload(envelope),
                CorrelationIdHolder.get()
        ));
    }

    @Transactional(readOnly = true)
    public List<WebhookEventOutbox> listOutbox(UUID lspId) {
        if (lspId == null) {
            return webhookEventOutboxRepository.findTop50ByOrderByCreatedAtDesc();
        }

        if (!lspRepository.existsById(lspId)) {
            throw new IllegalArgumentException("Unknown LSP id: " + lspId);
        }
        return webhookEventOutboxRepository.findTop50ByLsp_IdOrderByCreatedAtDesc(lspId);
    }

    public DispatchSummary dispatchPending(int batchSize) {
        if (batchSize < 1 || batchSize > 100) {
            throw new IllegalArgumentException("Batch size must be between 1 and 100.");
        }

        List<WebhookEventOutbox> batch = webhookEventOutboxRepository.findDispatchBatch(
                Instant.now(),
                PageRequest.of(0, batchSize)
        );
        int delivered = 0;
        int retryableFailures = 0;
        int permanentFailures = 0;

        for (WebhookEventOutbox event : batch) {
            DeliveryOutcome outcome = dispatchEvent(event);
            if (outcome == DeliveryOutcome.DELIVERED) {
                delivered += 1;
            } else if (outcome == DeliveryOutcome.RETRYABLE_FAILURE) {
                retryableFailures += 1;
            } else {
                permanentFailures += 1;
            }
        }

        return new DispatchSummary(batch.size(), delivered, retryableFailures, permanentFailures);
    }

    private String serializePayload(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize webhook outbox payload.", exception);
        }
    }

    private DeliveryOutcome dispatchEvent(WebhookEventOutbox event) {
        Instant attemptedAt = Instant.now();
        int attemptNumber = event.getAttemptCount() + 1;
        String endpointUrl = event.getLsp().getWebhookEndpointUrl();

        try {
            WebhookDeliveryClient.WebhookDeliveryResponse response = webhookDeliveryClient.deliver(
                    endpointUrl,
                    event.getPayloadJson(),
                    event.getCorrelationId()
            );
            WebhookEventDeliveryAttemptStatus attemptStatus = classify(response.statusCode());
            webhookEventDeliveryAttemptRepository.save(new WebhookEventDeliveryAttempt(
                    event,
                    attemptNumber,
                    endpointUrl,
                    response.statusCode(),
                    response.responseBody(),
                    null,
                    attemptStatus
            ));

            if (attemptStatus == WebhookEventDeliveryAttemptStatus.SUCCESS) {
                event.markDelivered(attemptedAt);
                webhookEventOutboxRepository.save(event);
                return DeliveryOutcome.DELIVERED;
            }

            String errorMessage = "Webhook delivery failed with HTTP status " + response.statusCode() + ".";
            if (attemptStatus == WebhookEventDeliveryAttemptStatus.RETRYABLE_FAILURE) {
                event.markRetryableFailure(attemptedAt, attemptedAt.plusSeconds(calculateBackoffSeconds(attemptNumber)), errorMessage);
                webhookEventOutboxRepository.save(event);
                return DeliveryOutcome.RETRYABLE_FAILURE;
            }

            event.markPermanentFailure(attemptedAt, errorMessage);
            webhookEventOutboxRepository.save(event);
            return DeliveryOutcome.PERMANENT_FAILURE;
        } catch (RuntimeException exception) {
            webhookEventDeliveryAttemptRepository.save(new WebhookEventDeliveryAttempt(
                    event,
                    attemptNumber,
                    endpointUrl,
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
            return DeliveryOutcome.RETRYABLE_FAILURE;
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

    private enum DeliveryOutcome {
        DELIVERED,
        RETRYABLE_FAILURE,
        PERMANENT_FAILURE
    }

    public record DispatchSummary(
            int processed,
            int delivered,
            int retryableFailures,
            int permanentFailures
    ) {
    }
}
