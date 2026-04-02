package com.bhawana.lms.service;

import com.bhawana.lms.common.correlation.CorrelationIdHolder;
import com.bhawana.lms.domain.Lsp;
import com.bhawana.lms.domain.WebhookEventOutbox;
import com.bhawana.lms.domain.WebhookEventOutboxStatus;
import com.bhawana.lms.domain.WebhookEventType;
import com.bhawana.lms.repo.LspRepository;
import com.bhawana.lms.repo.WebhookEventOutboxRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WebhookOutboxService {

    private final WebhookEventOutboxRepository webhookEventOutboxRepository;
    private final LspRepository lspRepository;
    private final ObjectMapper objectMapper;

    public WebhookOutboxService(
            WebhookEventOutboxRepository webhookEventOutboxRepository,
            LspRepository lspRepository,
            ObjectMapper objectMapper
    ) {
        this.webhookEventOutboxRepository = webhookEventOutboxRepository;
        this.lspRepository = lspRepository;
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

    private String serializePayload(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize webhook outbox payload.", exception);
        }
    }
}
