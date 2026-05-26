package com.bhawana.lms.service;

import java.time.Instant;

/**
 * Per-loan-application webhook delivery projection. Surfaces one outbox event
 * plus its most-recent delivery attempt as a single row. Used by
 * {@code GET /api/v1/internal/ops/loan-applications/{id}/webhook-events}.
 *
 * <p>{@code status} is the externalised view of {@code WebhookEventOutboxStatus}:
 * {@code PENDING / DELIVERED / FAILED / DEAD_LETTERED}.
 */
public record LoanApplicationWebhookEventProjection(
        String eventId,
        String eventType,
        String targetUrl,
        String status,
        int attempts,
        Instant lastAttemptAt,
        Integer lastResponseCode,
        String lastError,
        Instant createdAt
) {
}
