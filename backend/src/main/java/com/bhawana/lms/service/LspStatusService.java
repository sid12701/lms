package com.bhawana.lms.service;

import com.bhawana.lms.common.correlation.CorrelationIdHolder;
import com.bhawana.lms.common.web.LspStatusUpdateException;
import com.bhawana.lms.domain.ApiClient;
import com.bhawana.lms.domain.Lsp;
import com.bhawana.lms.domain.LspAuditEvent;
import com.bhawana.lms.domain.LspStatus;
import com.bhawana.lms.domain.LspStatusChangeReason;
import com.bhawana.lms.domain.OpsAlertSeverity;
import com.bhawana.lms.domain.OpsAlertType;
import com.bhawana.lms.repo.ApiClientRepository;
import com.bhawana.lms.repo.LspAuditEventRepository;
import com.bhawana.lms.repo.LspRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LspStatusService {

    private final LspRepository lspRepository;
    private final ApiClientRepository apiClientRepository;
    private final LspAuditEventRepository lspAuditEventRepository;
    private final OpsAlertService opsAlertService;
    private final ObjectMapper objectMapper;

    public LspStatusService(
            LspRepository lspRepository,
            ApiClientRepository apiClientRepository,
            LspAuditEventRepository lspAuditEventRepository,
            OpsAlertService opsAlertService,
            ObjectMapper objectMapper
    ) {
        this.lspRepository = lspRepository;
        this.apiClientRepository = apiClientRepository;
        this.lspAuditEventRepository = lspAuditEventRepository;
        this.opsAlertService = opsAlertService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Lsp updateStatus(
            UUID lspId,
            LspStatus targetStatus,
            LspStatusChangeReason reason,
            String note,
            String actorUsername
    ) {
        if (targetStatus == null) {
            throw new IllegalArgumentException("LSP status is required.");
        }
        if (reason == null) {
            throw new LspStatusUpdateException("REASON_REQUIRED", "Status change reason is required.");
        }
        if (note == null || note.isBlank()) {
            throw new LspStatusUpdateException("NOTE_REQUIRED", "Status change note is required.");
        }

        Lsp lsp = lspRepository.findById(lspId)
                .orElseThrow(() -> new IllegalArgumentException("LSP not found: " + lspId));

        if (lsp.getStatus() == targetStatus) {
            throw new LspStatusUpdateException(
                    "STATUS_UNCHANGED",
                    "LSP is already " + targetStatus.name() + "; no audit event was recorded."
            );
        }

        return switch (targetStatus) {
            case INACTIVE -> disable(lsp, reason, note.trim(), actorUsername);
            case ACTIVE -> reactivate(lsp, reason, note.trim(), actorUsername);
        };
    }

    @Transactional(readOnly = true)
    public List<LspAuditEvent> listAuditEvents(UUID lspId) {
        if (!lspRepository.existsById(lspId)) {
            throw new IllegalArgumentException("LSP not found: " + lspId);
        }
        return lspAuditEventRepository.findByLsp_IdOrderByCreatedAtDesc(lspId);
    }

    private Lsp disable(Lsp lsp, LspStatusChangeReason reason, String note, String actorUsername) {
        lsp.updateStatus(LspStatus.INACTIVE);
        lsp.revokeAllSessions();

        List<ApiClient> clients = apiClientRepository.findByLsp_Id(lsp.getId());
        for (ApiClient client : clients) {
            client.deactivate();
            client.revokeAllSessions();
        }
        apiClientRepository.saveAll(clients);
        Lsp saved = lspRepository.save(lsp);

        writeAudit(saved, actorUsername, "LSP_DISABLED", reason, note, clients.size());
        emitDisabledAlert(saved, reason, note, clients.size());
        return saved;
    }

    private Lsp reactivate(Lsp lsp, LspStatusChangeReason reason, String note, String actorUsername) {
        lsp.updateStatus(LspStatus.ACTIVE);
        Lsp saved = lspRepository.save(lsp);

        writeAudit(saved, actorUsername, "LSP_REACTIVATED", reason, note, 0);
        return saved;
    }

    private void writeAudit(
            Lsp lsp,
            String actorUsername,
            String action,
            LspStatusChangeReason reason,
            String note,
            int cascadedClientCount
    ) {
        ObjectNode details = objectMapper.createObjectNode();
        details.put("lspCode", lsp.getCode());
        details.put("status", lsp.getStatus().name());
        details.put("tokenVersion", lsp.getTokenVersion());

        lspAuditEventRepository.save(new LspAuditEvent(
                lsp,
                actorUsername,
                action,
                reason,
                note,
                cascadedClientCount,
                details.toString(),
                CorrelationIdHolder.get()
        ));
    }

    private void emitDisabledAlert(Lsp lsp, LspStatusChangeReason reason, String note, int cascadedClientCount) {
        String contextJson = "{\"lspCode\":\""
                + lsp.getCode()
                + "\",\"reason\":\""
                + reason.name()
                + "\",\"cascadedClientCount\":"
                + cascadedClientCount
                + "}";
        opsAlertService.createAlert(
                OpsAlertType.LSP_DISABLED,
                OpsAlertSeverity.HIGH,
                "LSP disabled: " + lsp.getCode(),
                note,
                "LSP",
                lsp.getId(),
                CorrelationIdHolder.get(),
                contextJson
        );
    }
}
