package com.bhawana.lms.web;

import com.bhawana.lms.common.api.CursorPagedResult;
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
    public CursorPagedResult<AuditExplorerEvent> search(
            @RequestParam(required = false) String streams,
            @RequestParam(required = false) String actorUsername,
            @RequestParam(required = false) UUID lspId,
            @RequestParam(required = false) UUID loanApplicationId,
            @RequestParam(required = false) UUID borrowerId,
            @RequestParam(required = false) UUID productId,
            @RequestParam(required = false) String since,
            @RequestParam(required = false) String until,
            @RequestParam(required = false) String correlationId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false, defaultValue = "100") int limit
    ) {
        Set<AuditStream> requestedStreams = parseStreams(streams);
        int effectiveLimit = clamp(limit, 1, MAX_LIMIT, DEFAULT_LIMIT);

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
                normalizeBlank(cursor),
                effectiveLimit
        );
        return service.search(query);
    }

    @GetMapping("/document-access/document-type-counts")
    public List<AuditExplorerService.DocumentAccessDocumentTypeCount> countDocumentAccessByDocumentType(
            @RequestParam(required = false) String since,
            @RequestParam(required = false) String until
    ) {
        Instant effectiveUntil = parseInstant(until, "until");
        if (effectiveUntil == null) {
            effectiveUntil = Instant.now();
        }
        Instant effectiveSince = parseInstant(since, "since");
        if (effectiveSince == null) {
            effectiveSince = effectiveUntil.minus(AuditExplorerQuery.DEFAULT_WINDOW);
        }
        return service.countDocumentAccessByDocumentType(effectiveSince, effectiveUntil);
    }

    private static Set<AuditStream> parseStreams(String streams) {
        if (streams == null || streams.isBlank()) {
            return AuditExplorerQuery.ALL_STREAMS;
        }
        Set<AuditStream> parsed = new LinkedHashSet<>();
        for (String token : streams.split(",")) {
            String trimmed = token.trim();
            if (!trimmed.isEmpty()) {
                parsed.add(AuditStream.valueOf(trimmed));
            }
        }
        return parsed.isEmpty() ? AuditExplorerQuery.ALL_STREAMS : parsed;
    }

    private static Instant parseInstant(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("Invalid " + fieldName + " timestamp: " + value);
        }
    }

    private static String normalizeBlank(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static int clamp(int value, int min, int max, int defaultValue) {
        if (value < min) {
            return defaultValue;
        }
        return Math.min(value, max);
    }
}
