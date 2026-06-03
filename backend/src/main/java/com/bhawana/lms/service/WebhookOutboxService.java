package com.bhawana.lms.service;

import com.bhawana.lms.common.correlation.CorrelationIdHolder;
import com.bhawana.lms.domain.Lsp;
import com.bhawana.lms.domain.WebhookEventOutbox;
import com.bhawana.lms.domain.WebhookEventType;
import com.bhawana.lms.repo.LspRepository;
import com.bhawana.lms.repo.WebhookEventOutboxRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WebhookOutboxService {

    private static final Logger log = LoggerFactory.getLogger(WebhookOutboxService.class);
    private static final Duration CLAIM_TTL = Duration.ofMinutes(5);

    private final WebhookEventOutboxRepository webhookEventOutboxRepository;
    private final LspRepository lspRepository;
    private final ObjectMapper objectMapper;
    private final WebhookOutboxDispatchExecutor webhookOutboxDispatchExecutor;
    private final ThreadPoolTaskExecutor webhookDeliveryExecutor;

    public WebhookOutboxService(
            WebhookEventOutboxRepository webhookEventOutboxRepository,
            LspRepository lspRepository,
            ObjectMapper objectMapper,
            WebhookOutboxDispatchExecutor webhookOutboxDispatchExecutor,
            @Qualifier("webhookDeliveryExecutor") ThreadPoolTaskExecutor webhookDeliveryExecutor
    ) {
        this.webhookEventOutboxRepository = webhookEventOutboxRepository;
        this.lspRepository = lspRepository;
        this.objectMapper = objectMapper;
        this.webhookOutboxDispatchExecutor = webhookOutboxDispatchExecutor;
        this.webhookDeliveryExecutor = webhookDeliveryExecutor;
    }

    @Transactional
    public void enqueueIfSubscribed(
            Lsp lsp,
            WebhookEventType eventType,
            String aggregateType,
            String aggregateId,
            UUID loanApplicationId,
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
                loanApplicationId,
                com.bhawana.lms.domain.WebhookEventOutboxStatus.PENDING,
                serializePayload(envelope),
                CorrelationIdHolder.get()
        ));
    }

    @Transactional(readOnly = true)
    public List<WebhookEventOutbox> listOutboxForLoanApplication(UUID loanApplicationId) {
        if (loanApplicationId == null) {
            return List.of();
        }
        return webhookEventOutboxRepository.findTop200ByLoanApplicationIdOrderByCreatedAtDesc(loanApplicationId);
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

        long startedAt = System.nanoTime();
        Instant now = Instant.now();
        Instant claimExpiresAt = now.plus(CLAIM_TTL);
        List<WebhookEventOutbox> batch = webhookOutboxDispatchExecutor.claimBatch(now, batchSize, claimExpiresAt);

        int delivered = 0;
        int retryableFailures = 0;
        int permanentFailures = 0;

        List<Future<DeliveryOutcome>> deliveries = batch.stream()
                .map(event -> webhookDeliveryExecutor.submit(() ->
                        webhookOutboxDispatchExecutor.deliverOne(event.getId())))
                .toList();

        for (Future<DeliveryOutcome> delivery : deliveries) {
            try {
                DeliveryOutcome outcome = delivery.get();
                if (outcome == DeliveryOutcome.DELIVERED) {
                    delivered += 1;
                } else if (outcome == DeliveryOutcome.RETRYABLE_FAILURE) {
                    retryableFailures += 1;
                } else {
                    permanentFailures += 1;
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Webhook delivery was interrupted.", exception);
            } catch (ExecutionException exception) {
                throw new IllegalStateException("Webhook delivery failed.", exception.getCause());
            }
        }

        log.info(
                "webhook_outbox_batch_completed processed={} delivered={} retryableFailures={} permanentFailures={} durationMs={}",
                batch.size(),
                delivered,
                retryableFailures,
                permanentFailures,
                elapsedMillis(startedAt)
        );
        return new DispatchSummary(batch.size(), delivered, retryableFailures, permanentFailures);
    }

    private String serializePayload(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize webhook outbox payload.", exception);
        }
    }

    public enum DeliveryOutcome {
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

    private static long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }
}
