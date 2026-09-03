package com.bhawana.lms.service;

import com.bhawana.lms.common.correlation.CorrelationIdHolder;
import com.bhawana.lms.domain.Lsp;
import com.bhawana.lms.domain.LspAuditEvent;
import com.bhawana.lms.domain.LspAuditEventAction;
import com.bhawana.lms.repo.LspAuditEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
