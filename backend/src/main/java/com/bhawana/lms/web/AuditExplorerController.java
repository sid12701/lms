package com.bhawana.lms.web;

import com.bhawana.lms.common.api.PagedResult;
import com.bhawana.lms.service.AuditExplorerQuery;
import com.bhawana.lms.service.AuditExplorerQuery.AuditStream;
import com.bhawana.lms.service.AuditExplorerService;
import com.bhawana.lms.service.AuditExplorerService.AuditExplorerEvent;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Gap #3 — {@code GET /api/v1/internal/admin/audit-events}.
 *
 * <p>SYSTEM_ADMIN-only unified search across the eight audit streams
 * (APPLICATION, INTAKE, DOCUMENT_ACCESS, PRODUCT, APP_USER, API_CLIENT,
 * DISBURSEMENT, REPORT_ACCESS). All filters are optional
 * and AND'd together. Pagination is offset/limit (cap 500); totalCount is
 * opt-in via {@code paginationDetails=true} to keep the default path off the
 * expensive COUNT(*) wrap.
 */
@RestController
@RequestMapping("/api/v1/internal/admin/audit-events")
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
public class AuditExplorerController {

    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 500;

    private final AuditExplorerService service;

    public AuditExplorerController(AuditExplorerService service) {
        this.service = service;
    }

    @GetMapping
    public PagedResult<AuditExplorerEvent> search(
            @RequestParam(required = false) String streams,
            @RequestParam(required = false) String actorUsername,
            @RequestParam(required = false) UUID lspId,
            @RequestParam(required = false) UUID loanApplicationId,
            @RequestParam(required = false) UUID borrowerId,
            @RequestParam(required = false) UUID productId,
            @RequestParam(required = false) String since,
            @RequestParam(required = false) String until,
            @RequestParam(required = false) String correlationId,
            @RequestParam(required = false, defaultValue = "0") int offset,
            @RequestParam(required = false, defaultValue = "100") int limit,
            @RequestParam(required = false, defaultValue = "false") boolean paginationDetails
    ) {
        Set<AuditStream> requestedStreams = parseStreams(streams);
        int effectiveLimit = clamp(limit, 1, MAX_LIMIT, DEFAULT_LIMIT);
        int effectiveOffset = Math.max(offset, 0);

        AuditExplorerQuery query = new AuditExplorerQuery(
                requestedStreams,
                normalizeBlank(actorUsername),
                lspId,
                loanApplicationId,
                borrowerId,
                productId,
                parseInstant(since, "since"),
                parseInstant(until, "until"),
                normalizeBlank(correlationId),
                effectiveOffset,
                effectiveLimit,
                paginationDetails
        );
        return service.search(query);
    }

    @GetMapping("/document-access/document-type-counts")
    public List<AuditExplorerService.DocumentAccessDocumentTypeCount> countDocumentAccessByDocumentType(
            @RequestParam(required = false) String since,
            @RequestParam(required = false) String until
    ) {
        return service.countDocumentAccessByDocumentType(
                parseInstant(since, "since"),
                parseInstant(until, "until")
        );
    }

    private static Set<AuditStream> parseStreams(String raw) {
        if (raw == null || raw.isBlank()) {
            return AuditExplorerQuery.ALL_STREAMS;
        }
        Set<AuditStream> result = new LinkedHashSet<>();
        for (String token : raw.split(",")) {
            String trimmed = token.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                result.add(AuditStream.valueOf(trimmed.toUpperCase()));
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException(
                        "Unknown audit stream '" + trimmed
                                + "'. Expected one of: " + List.of(AuditStream.values()));
            }
        }
        return result.isEmpty() ? AuditExplorerQuery.ALL_STREAMS : result;
    }

    private static Instant parseInstant(String raw, String fieldName) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(raw);
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException(fieldName + " must be an ISO-8601 instant (e.g. 2026-01-01T00:00:00Z)");
        }
    }

    private static String normalizeBlank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static int clamp(int value, int min, int max, int fallback) {
        if (value < min) {
            return fallback;
        }
        return Math.min(value, max);
    }
}
