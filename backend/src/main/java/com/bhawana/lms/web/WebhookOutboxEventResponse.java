package com.bhawana.lms.web;

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
        int attemptCount,
        String lastAttemptAt,
        String nextAttemptAt,
        String deliveredAt,
        String lastError,
        String createdAt,
        int redriveCount
) {
}
