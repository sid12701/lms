package com.bhawana.lms.service;

import com.bhawana.lms.common.api.error.BusinessRuleViolationException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Filter snapshot for {@link AuditExplorerService#search(AuditExplorerQuery)}.
 */
public record AuditExplorerQuery(
        Set<AuditStream> streams,
        String actorUsername,
        UUID lspId,
        UUID loanApplicationId,
        UUID borrowerId,
        UUID productId,
        Instant since,
        Instant until,
        String correlationId,
        String cursor,
        int limit
) {

    public static final Duration DEFAULT_WINDOW = Duration.ofDays(7);
    public static final Duration MAX_WINDOW = Duration.ofDays(90);

    /** All eight streams in canonical order. */
    public static final Set<AuditStream> ALL_STREAMS = Set.of(
            AuditStream.APPLICATION,
            AuditStream.INTAKE,
            AuditStream.DOCUMENT_ACCESS,
            AuditStream.PRODUCT,
            AuditStream.APP_USER,
            AuditStream.API_CLIENT,
            AuditStream.DISBURSEMENT,
            AuditStream.REPORT_ACCESS
    );

    public AuditExplorerQuery {
        if (streams == null || streams.isEmpty()) {
            streams = ALL_STREAMS;
        }
        if (limit < 1 || limit > 500) {
            throw new BusinessRuleViolationException(
                    "INVALID_LIMIT",
                    "limit must be in [1, 500]",
                    Map.of("limit", "must be in [1, 500]")
            );
        }
        Instant effectiveUntil = until == null ? Instant.now() : until;
        Instant effectiveSince = since == null ? effectiveUntil.minus(DEFAULT_WINDOW) : since;
        if (effectiveSince.isAfter(effectiveUntil)) {
            throw new BusinessRuleViolationException(
                    "INVALID_TIME_RANGE",
                    "since must not be after until",
                    Map.of("since", "must not be after until")
            );
        }
        if (Duration.between(effectiveSince, effectiveUntil).compareTo(MAX_WINDOW) > 0) {
            throw new BusinessRuleViolationException(
                    "VALIDATION_FAILED",
                    "audit window must not exceed 90 days",
                    Map.of("window", "must not exceed 90 days")
            );
        }
        since = effectiveSince;
        until = effectiveUntil;
    }

    public enum AuditStream {
        APPLICATION,
        INTAKE,
        DOCUMENT_ACCESS,
        PRODUCT,
        APP_USER,
        API_CLIENT,
        DISBURSEMENT,
        REPORT_ACCESS
    }
}
