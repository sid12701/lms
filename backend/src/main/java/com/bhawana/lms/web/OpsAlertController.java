package com.bhawana.lms.web;

import com.bhawana.lms.common.correlation.CorrelationIdHolder;
import com.bhawana.lms.common.util.AlertContextJson;
import com.bhawana.lms.common.api.PagedResult;
import com.bhawana.lms.common.api.PaginationResponseBuilder;
import com.bhawana.lms.domain.AlertRule;
import com.bhawana.lms.domain.OpsAlert;
import com.bhawana.lms.domain.OpsAlertSeverity;
import com.bhawana.lms.domain.OpsAlertStatus;
import com.bhawana.lms.domain.OpsAlertType;
import com.bhawana.lms.service.AlertRuleEvaluationWorker;
import com.bhawana.lms.service.AdminApiIdempotencyService;
import com.bhawana.lms.service.OpsAlertService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/internal/alerts")
@PreAuthorize("hasAnyRole('SYSTEM_ADMIN','OPS_USER')")
@Validated
public class OpsAlertController {

    private static final Logger log = LoggerFactory.getLogger(OpsAlertController.class);
    private static final String ALERT_ACKNOWLEDGE = "ALERT_ACKNOWLEDGE";
    private static final String ALERT_ESCALATE = "ALERT_ESCALATE";

    private final OpsAlertService opsAlertService;
    private final AlertRuleEvaluationWorker alertRuleEvaluationWorker;
    private final ObjectMapper objectMapper;
    private final AdminApiIdempotencyService adminApiIdempotencyService;

    public OpsAlertController(
            OpsAlertService opsAlertService,
            AlertRuleEvaluationWorker alertRuleEvaluationWorker,
            ObjectMapper objectMapper,
            AdminApiIdempotencyService adminApiIdempotencyService
    ) {
        this.opsAlertService = opsAlertService;
        this.alertRuleEvaluationWorker = alertRuleEvaluationWorker;
        this.objectMapper = objectMapper;
        this.adminApiIdempotencyService = adminApiIdempotencyService;
    }

    @GetMapping("/rules")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public List<AlertRuleResponse> listAlertRules() {
        return alertRuleEvaluationWorker.listRules().stream()
                .map(OpsAlertController::toRuleResponse)
                .toList();
    }

    @GetMapping
    public ResponseEntity<List<OpsAlertResponse>> listAlerts(
            @RequestParam(required = false) OpsAlertStatus status,
            @RequestParam(required = false) @Min(0) Integer offset,
            @RequestParam(required = false) @Min(1) @Max(200) Integer limit,
            @RequestParam(required = false) String paginationDetails
    ) {
        boolean includePaginationDetails = PaginationResponseBuilder.includePaginationDetails(paginationDetails);
        PagedResult<OpsAlert> page = opsAlertService.listAlerts(
                status, offset, limit, includePaginationDetails);
        PagedResult<OpsAlertResponse> mapped = new PagedResult<>(
                page.items().stream().map(OpsAlertController::toResponse).toList(),
                page.totalCount(),
                page.offset(),
                page.limit()
        );
        return PaginationResponseBuilder.toListResponse(mapped, includePaginationDetails);
    }

    @PostMapping("/{alertId}/acknowledge")
    public OpsAlertResponse acknowledge(
            Authentication authentication,
            @PathVariable UUID alertId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody(required = false) AcknowledgeAlertRequest body
    ) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return doAcknowledge(authentication, alertId, body);
        }
        return adminApiIdempotencyService.execute(
                ALERT_ACKNOWLEDGE,
                idempotencyKey,
                new AcknowledgeFingerprint(alertId.toString(), body),
                OpsAlertResponse.class,
                () -> doAcknowledge(authentication, alertId, body)
        );
    }

    private OpsAlertResponse doAcknowledge(
            Authentication authentication,
            UUID alertId,
            AcknowledgeAlertRequest body
    ) {
        String actor = authentication == null ? "system" : authentication.getName();
        String note = body == null ? null : body.note();
        return toResponse(opsAlertService.acknowledge(alertId, actor, note));
    }

    @PostMapping("/escalate")
    public OpsAlertResponse escalate(
            Authentication authentication,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody EscalateAlertRequest body
    ) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return doEscalate(authentication, body);
        }
        return adminApiIdempotencyService.execute(
                ALERT_ESCALATE,
                idempotencyKey,
                body,
                OpsAlertResponse.class,
                () -> doEscalate(authentication, body)
        );
    }

    private OpsAlertResponse doEscalate(Authentication authentication, EscalateAlertRequest body) {
        String actor = authentication == null ? "system" : authentication.getName();
        String trimmedTitle = body.title() == null ? "" : body.title().trim();
        String trimmedMessage = body.message() == null ? "" : body.message().trim();
        if (trimmedTitle.isEmpty() || trimmedMessage.isEmpty()) {
            throw new IllegalArgumentException("Escalation title and message are required.");
        }
        String trimmedSubjectType = body.subjectType() == null ? "" : body.subjectType().trim();
        String subjectType = trimmedSubjectType.isEmpty() ? "SYSTEM" : trimmedSubjectType.toUpperCase();
        UUID subjectId = parseOptionalUuid(body.subjectId());
        String contextJson = AlertContextJson.serialize(
                objectMapper,
                log,
                Map.of("escalatedByUsername", actor)
        );
        OpsAlert created = opsAlertService.createAlert(
                OpsAlertType.OPS_USER_ESCALATION,
                OpsAlertSeverity.HIGH,
                trimmedTitle,
                trimmedMessage,
                subjectType,
                subjectId,
                CorrelationIdHolder.get(),
                contextJson
        );
        return toResponse(created);
    }

    private record AcknowledgeFingerprint(String alertId, AcknowledgeAlertRequest body) {
    }

    private static UUID parseOptionalUuid(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            return UUID.fromString(trimmed);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Invalid subjectId: must be a UUID.", ex);
        }
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
                alert.getAcknowledgedByUsername(),
                alert.getAcknowledgementNote()
        );
    }

    public record AcknowledgeAlertRequest(
            @Size(max = 500, message = "note must be 500 characters or fewer") String note
    ) {
    }

    public record EscalateAlertRequest(
            @Size(max = 64, message = "subjectType must be 64 characters or fewer") String subjectType,
            @Size(max = 64, message = "subjectId must be 64 characters or fewer") String subjectId,
            @NotBlank(message = "title is required")
            @Size(max = 255, message = "title must be 255 characters or fewer") String title,
            @NotBlank(message = "message is required")
            @Size(max = 1000, message = "message must be 1000 characters or fewer") String message
    ) {
    }

    public record AlertRuleResponse(
            String id,
            String code,
            String name,
            String description,
            boolean enabled,
            String audience,
            String triggerKind,
            String configJson,
            String lastEvaluatedAt
    ) {
    }

    private static AlertRuleResponse toRuleResponse(AlertRule rule) {
        return new AlertRuleResponse(
                rule.getId().toString(),
                rule.getCode(),
                rule.getName(),
                rule.getDescription(),
                rule.isEnabled(),
                rule.getAudience().name(),
                rule.getTriggerKind().name(),
                rule.getConfigJson(),
                rule.getLastEvaluatedAt() == null ? null : rule.getLastEvaluatedAt().toString()
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
            String acknowledgedByUsername,
            String acknowledgementNote
    ) {
    }
}
