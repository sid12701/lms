package com.bhawana.lms.service;

import java.time.Instant;

public record LoanApplicationLastActivity(
        String activityType,
        String actorUsername,
        String summary,
        String detail,
        String correlationId,
        Instant occurredAt
) {
}
