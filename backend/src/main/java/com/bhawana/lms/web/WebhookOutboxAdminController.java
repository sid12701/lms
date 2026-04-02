package com.bhawana.lms.web;

import com.bhawana.lms.domain.WebhookEventOutbox;
import com.bhawana.lms.service.WebhookOutboxService;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
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

    private static WebhookOutboxEventResponse toResponse(WebhookEventOutbox event) {
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
                event.getCreatedAt().toString()
        );
    }

    public record WebhookOutboxEventResponse(
            String id,
            String lspId,
            String lspCode,
            String eventType,
            String aggregateType,
            String aggregateId,
            String status,
            String payloadJson,
            String correlationId,
            String createdAt
    ) {
    }
}
