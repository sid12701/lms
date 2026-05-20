package com.bhawana.lms.web;

import com.bhawana.lms.domain.OpsAlert;
import com.bhawana.lms.domain.OpsAlertStatus;
import com.bhawana.lms.service.OpsAlertService;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/internal/alerts")
@PreAuthorize("hasAnyRole('SYSTEM_ADMIN','OPS_USER')")
public class OpsAlertController {

    private final OpsAlertService opsAlertService;

    public OpsAlertController(OpsAlertService opsAlertService) {
        this.opsAlertService = opsAlertService;
    }

    @GetMapping
    public List<OpsAlertResponse> listAlerts(@RequestParam(required = false) OpsAlertStatus status) {
        return opsAlertService.listAlerts(status).stream()
                .map(OpsAlertController::toResponse)
                .toList();
    }

    @PostMapping("/{alertId}/acknowledge")
    public OpsAlertResponse acknowledge(Authentication authentication, @PathVariable UUID alertId) {
        return toResponse(opsAlertService.acknowledge(alertId, authentication == null ? "system" : authentication.getName()));
    }

    private static OpsAlertResponse toResponse(OpsAlert alert) {
        return new OpsAlertResponse(
                alert.getId().toString(),
                alert.getType().name(),
                alert.getSeverity().name(),
                alert.getStatus().name(),
                alert.getTitle(),
                alert.getMessage(),
                alert.getSubjectType(),
                alert.getSubjectId() == null ? null : alert.getSubjectId().toString(),
                alert.getCorrelationId(),
                alert.getContextJson(),
                alert.getCreatedAt().toString(),
                alert.getAcknowledgedAt() == null ? null : alert.getAcknowledgedAt().toString(),
                alert.getAcknowledgedByUsername()
        );
    }

    public record OpsAlertResponse(
            String id,
            String type,
            String severity,
            String status,
            String title,
            String message,
            String subjectType,
            String subjectId,
            String correlationId,
            String contextJson,
            String createdAt,
            String acknowledgedAt,
            String acknowledgedByUsername
    ) {
    }
}
