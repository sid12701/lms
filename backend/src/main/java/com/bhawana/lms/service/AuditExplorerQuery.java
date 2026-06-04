package com.bhawana.lms.service;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Filter snapshot for {@link AuditExplorerService#search(AuditExplorerQuery)}.
 *
 * <p>Gap #3 — Unified cross-domain audit search. The endpoint executes a
 * native UNION ALL across the eight supported audit tables; this record
 * carries the filter values that push down per branch.
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
        int offset,
        int limit,
        boolean includePaginationDetails
) {

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
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be non-negative");
        }
        if (limit < 1 || limit > 500) {
            throw new IllegalArgumentException("limit must be in [1, 500]");
        }
        if (since != null && until != null && since.isAfter(until)) {
            throw new IllegalArgumentException("since must not be after until");
        }
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
