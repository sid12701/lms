package com.bhawana.lms.web;

import com.bhawana.lms.common.correlation.CorrelationIdHolder;
import com.bhawana.lms.domain.WebhookEventOutbox;
import com.bhawana.lms.service.WebhookOutboxService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/internal/admin/webhook-outbox")
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
public class WebhookOutboxAdminController {

    private final WebhookOutboxService webhookOutboxService;

    public WebhookOutboxAdminController(WebhookOutboxService webhookOutboxService) {
        this.webhookOutboxService = webhookOutboxService;
    }

    @GetMapping
    public List<WebhookOutboxEventResponse> listOutbox(@RequestParam(required = false) UUID lspId) {
        return webhookOutboxService.listOutbox(lspId).stream()
                .map(WebhookOutboxAdminController::toResponse)
                .toList();
    }

    @PostMapping("/dispatch")
    public DispatchWebhookOutboxResponse dispatchOutbox(@RequestParam(defaultValue = "20") int batchSize) {
        WebhookOutboxService.DispatchSummary summary = webhookOutboxService.dispatchPending(batchSize);
        return new DispatchWebhookOutboxResponse(
                summary.processed(),
                summary.delivered(),
                summary.retryableFailures(),
                summary.permanentFailures()
        );
    }

    @PostMapping("/{id}/redrive")
    public WebhookOutboxEventResponse redrive(
            @PathVariable UUID id,
            @AuthenticationPrincipal Jwt principal,
            HttpServletRequest request
    ) {
        WebhookEventOutbox event = webhookOutboxService.redrive(
                id,
                principal.getSubject(),
                request.getRemoteAddr(),
                CorrelationIdHolder.get()
        );
        return toResponse(event);
    }

    static WebhookOutboxEventResponse toResponse(WebhookEventOutbox event) {
        return new WebhookOutboxEventResponse(
                event.getId().toString(),
                event.getLsp().getId().toString(),
                event.getLsp().getCode(),
                event.getEventType().name(),
                event.getAggregateType(),
                event.getAggregateId(),
                event.getStatus().name(),
                event.getPayloadJson(),
                event.getCorrelationId(),
                event.getAttemptCount(),
                toNullableString(event.getLastAttemptAt()),
                toNullableString(event.getNextAttemptAt()),
                toNullableString(event.getDeliveredAt()),
                event.getLastError(),
                event.getCreatedAt().toString(),
                event.getRedriveCount()
        );
    }

    private static String toNullableString(Instant value) {
        return value == null ? null : value.toString();
    }
}
