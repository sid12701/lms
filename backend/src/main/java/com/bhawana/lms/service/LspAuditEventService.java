package com.bhawana.lms.service;

import com.bhawana.lms.common.correlation.CorrelationIdHolder;
import com.bhawana.lms.domain.Lsp;
import com.bhawana.lms.domain.LspAuditEvent;
import com.bhawana.lms.domain.LspAuditEventAction;
import com.bhawana.lms.repo.LspAuditEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class LspAuditEventService {

    private final LspAuditEventRepository lspAuditEventRepository;
    private final ObjectMapper objectMapper;

    public LspAuditEventService(LspAuditEventRepository lspAuditEventRepository, ObjectMapper objectMapper) {
        this.lspAuditEventRepository = lspAuditEventRepository;
        this.objectMapper = objectMapper;
    }

    public void recordWebhookSubscriptionChanges(
            Lsp lsp,
            WebhookSubscriptionSnapshot before,
            WebhookSubscriptionSnapshot after,
            String actorUsername,
            String actorIp,
            String correlationId
    ) {
        String resolvedCorrelationId = resolveCorrelationId(correlationId);

        if (before.enabledChanged(after)) {
            if (after.enabled()) {
                save(
                        lsp,
                        actorUsername,
                        LspAuditEventAction.WEBHOOK_ENABLED,
                        buildEnabledDetails(after),
                        resolvedCorrelationId,
                        actorIp
                );
            } else {
                save(
                        lsp,
                        actorUsername,
                        LspAuditEventAction.WEBHOOK_DISABLED,
                        buildDisabledDetails(before),
                        resolvedCorrelationId,
                        actorIp
                );
            }
        }
        if (before.urlChanged(after)) {
            ObjectNode details = objectMapper.createObjectNode();
            ObjectNode beforeNode = details.putObject("before");
            beforeNode.put("url", before.endpointUrl());
            ObjectNode afterNode = details.putObject("after");
            afterNode.put("url", after.endpointUrl());
            save(
                    lsp,
                    actorUsername,
                    LspAuditEventAction.WEBHOOK_URL_CHANGED,
                    details,
                    resolvedCorrelationId,
                    actorIp
            );
        }
        if (before.signingSecretChanged(after)) {
            save(
                    lsp,
                    actorUsername,
                    LspAuditEventAction.WEBHOOK_SECRET_ROTATED,
                    objectMapper.createObjectNode(),
                    resolvedCorrelationId,
                    actorIp
            );
        }
        if (before.eventTypesChanged(after)) {
            ObjectNode details = objectMapper.createObjectNode();
            details.set("before", eventTypesNode(before));
            details.set("after", eventTypesNode(after));
            save(
                    lsp,
                    actorUsername,
                    LspAuditEventAction.WEBHOOK_EVENT_TYPES_CHANGED,
                    details,
                    resolvedCorrelationId,
                    actorIp
            );
        }
    }

    public void recordIpAllowlistEntryAdded(
            Lsp lsp,
            UUID entryId,
            String cidr,
            String description,
            String surface,
            String actorUsername,
            String actorIp,
            String correlationId
    ) {
        ObjectNode details = objectMapper.createObjectNode();
        details.put("cidr", cidr);
        details.put("description", description == null ? "" : description);
        details.put("entryId", entryId.toString());
        details.put("surface", surface);
        save(
                lsp,
                actorUsername,
                LspAuditEventAction.LSP_IP_ALLOWLIST_ENTRY_ADDED,
                details,
                resolveCorrelationId(correlationId),
                actorIp
        );
    }

    public void recordIpAllowlistEntryRemoved(
            Lsp lsp,
            UUID entryId,
            String cidr,
            String description,
            String surface,
            String actorUsername,
            String actorIp,
            String correlationId
    ) {
        ObjectNode details = objectMapper.createObjectNode();
        details.put("cidr", cidr);
        details.put("description", description == null ? "" : description);
        details.put("entryId", entryId.toString());
        details.put("surface", surface);
        save(
                lsp,
                actorUsername,
                LspAuditEventAction.LSP_IP_ALLOWLIST_ENTRY_REMOVED,
                details,
                resolveCorrelationId(correlationId),
                actorIp
        );
    }

    private ObjectNode buildEnabledDetails(WebhookSubscriptionSnapshot snapshot) {
        ObjectNode details = objectMapper.createObjectNode();
        ObjectNode after = details.putObject("after");
        after.put("enabled", true);
        after.put("url", snapshot.endpointUrl());
        after.set("eventTypes", toArrayNode(snapshot.eventTypeNames()));
        return details;
    }

    private ObjectNode buildDisabledDetails(WebhookSubscriptionSnapshot snapshot) {
        ObjectNode details = objectMapper.createObjectNode();
        ObjectNode before = details.putObject("before");
        before.put("enabled", true);
        before.put("url", snapshot.endpointUrl());
        before.set("eventTypes", toArrayNode(snapshot.eventTypeNames()));
        return details;
    }

    private ObjectNode eventTypesNode(WebhookSubscriptionSnapshot snapshot) {
        ObjectNode node = objectMapper.createObjectNode();
        node.set("eventTypes", toArrayNode(snapshot.eventTypeNames()));
        return node;
    }

    private ArrayNode toArrayNode(Iterable<String> values) {
        ArrayNode array = objectMapper.createArrayNode();
        for (String value : values) {
            array.add(value);
        }
        return array;
    }

    private void save(
            Lsp lsp,
            String actorUsername,
            LspAuditEventAction action,
            ObjectNode details,
            String correlationId,
            String actorIp
    ) {
        try {
            lspAuditEventRepository.save(new LspAuditEvent(
                    lsp,
                    actorUsername,
                    action.name(),
                    null,
                    null,
                    0,
                    objectMapper.writeValueAsString(details),
                    correlationId,
                    actorIp
            ));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize LSP audit details.", exception);
        }
    }

    private static String resolveCorrelationId(String correlationId) {
        if (correlationId != null && !correlationId.isBlank()) {
            return correlationId;
        }
        return CorrelationIdHolder.get();
    }
}
