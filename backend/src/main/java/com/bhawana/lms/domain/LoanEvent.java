package com.bhawana.lms.domain;

import java.time.Instant;
import java.util.UUID;

/** One immutable fact in the partner-facing loan event log. */
public record LoanEvent(
        UUID id,
        UUID lspId,
        LoanEventType eventType,
        String aggregateType,
        String aggregateId,
        UUID loanApplicationId,
        String payloadJson,
        Instant occurredAt,
        String correlationId,
        String transactionId,
        long position
) {
}
