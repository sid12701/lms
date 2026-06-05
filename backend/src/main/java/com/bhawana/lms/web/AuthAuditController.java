package com.bhawana.lms.web;

import com.bhawana.lms.common.web.PagedResult;
import com.bhawana.lms.domain.AuthEventAudit;
import com.bhawana.lms.domain.AuthEventType;
import com.bhawana.lms.service.AuthAuditService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/internal/ops/auth-audit")
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
public class AuthAuditController {

    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 500;

    private final AuthAuditService authAuditService;

    public AuthAuditController(AuthAuditService authAuditService) {
        this.authAuditService = authAuditService;
    }

    @GetMapping
    public PagedResult<AuthAuditEventResponse> search(
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String eventType,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(required = false) Integer limit,
            @RequestParam(defaultValue = "false") boolean paginationDetails
    ) {
        int resolvedLimit = limit == null ? DEFAULT_LIMIT : Math.min(Math.max(limit, 1), MAX_LIMIT);
        AuthEventType resolvedEventType = eventType == null || eventType.isBlank()
                ? null
                : AuthEventType.valueOf(eventType.trim());

        PagedResult<AuthEventAudit> page = authAuditService.search(
                username,
                resolvedEventType,
                offset,
                resolvedLimit,
                paginationDetails
        );

        List<AuthAuditEventResponse> items = page.items().stream()
                .map(AuthAuditEventResponse::from)
                .toList();

        return new PagedResult<>(items, page.totalCount(), page.offset(), page.limit());
    }

    public record AuthAuditEventResponse(
            UUID id,
            String username,
            UUID userId,
            UUID apiClientId,
            String eventType,
            String failureReason,
            String actorIp,
            String correlationId,
            Instant createdAt
    ) {
        static AuthAuditEventResponse from(AuthEventAudit event) {
            return new AuthAuditEventResponse(
                    event.getId(),
                    event.getUsername(),
                    event.getUserId(),
                    event.getApiClientId(),
                    event.getEventType().name(),
                    event.getFailureReason() != null ? event.getFailureReason().name() : null,
                    event.getActorIp(),
                    event.getCorrelationId(),
                    event.getCreatedAt()
            );
        }
    }
}
