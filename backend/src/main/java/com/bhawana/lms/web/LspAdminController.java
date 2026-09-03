package com.bhawana.lms.web;

import com.bhawana.lms.common.api.error.LspStatusUpdateException;
import com.bhawana.lms.domain.Lsp;
import com.bhawana.lms.domain.LspAuditEvent;
import com.bhawana.lms.domain.LspStatus;
import com.bhawana.lms.domain.LspStatusChangeReason;
import com.bhawana.lms.service.AdminApiIdempotencyService;
import com.bhawana.lms.service.LspDirectoryService;
import com.bhawana.lms.service.LspStatusService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/internal/admin/lsps")
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
public class LspAdminController {

    private static final String LSP_CREATE = "LSP_CREATE";
    private static final String LSP_STATUS_UPDATE = "LSP_STATUS_UPDATE";

    private final LspDirectoryService lspDirectoryService;
    private final LspStatusService lspStatusService;
    private final AdminApiIdempotencyService adminApiIdempotencyService;

    public LspAdminController(
            LspDirectoryService lspDirectoryService,
            LspStatusService lspStatusService,
            AdminApiIdempotencyService adminApiIdempotencyService
    ) {
        this.lspDirectoryService = lspDirectoryService;
        this.lspStatusService = lspStatusService;
        this.adminApiIdempotencyService = adminApiIdempotencyService;
    }

    @GetMapping
    public List<LspResponse> listLsps() {
        return lspDirectoryService.listLspDirectoryViews().stream()
                .map(LspAdminController::toResponse)
                .toList();
    }

    @GetMapping("/{lspId}")
    public LspDetailResponse getLspDetail(@PathVariable UUID lspId) {
        return toDetailResponse(lspDirectoryService.getLspDetail(lspId));
    }

    @PostMapping
    public LspResponse createLsp(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateLspRequest request
    ) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return doCreateLsp(request);
        }
        return adminApiIdempotencyService.execute(
                LSP_CREATE,
                idempotencyKey,
                request,
                LspResponse.class,
                () -> doCreateLsp(request)
        );
    }

    private LspResponse doCreateLsp(CreateLspRequest request) {
        Lsp lsp = lspDirectoryService.createLsp(request.code(), request.name(), request.status());
        return toResponse(lsp);
    }

    @PutMapping("/{lspId}/status")
    public LspResponse updateStatus(
            @PathVariable UUID lspId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody UpdateLspStatusRequest request,
            @AuthenticationPrincipal Jwt principal
    ) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return doUpdateStatus(lspId, request, principal);
        }
        return adminApiIdempotencyService.execute(
                LSP_STATUS_UPDATE,
                idempotencyKey,
                new LspStatusUpdateFingerprint(lspId.toString(), request),
                LspResponse.class,
                () -> doUpdateStatus(lspId, request, principal)
        );
    }

    private LspResponse doUpdateStatus(UUID lspId, UpdateLspStatusRequest request, Jwt principal) {
        String actorUsername = principal == null ? "unknown" : principal.getSubject();
        Lsp lsp = lspStatusService.updateStatus(
                lspId,
                request.resolvedStatus(),
                request.resolvedReason(),
                request.note(),
                actorUsername
        );
        return toResponse(lsp);
    }

    @GetMapping("/{lspId}/audit-events")
    public List<LspAuditEventResponse> listAuditEvents(@PathVariable UUID lspId) {
        return lspStatusService.listAuditEvents(lspId).stream()
                .map(event -> toAuditEventResponse(lspId, event))
                .toList();
    }

    private static LspResponse toResponse(Lsp lsp) {
        return new LspResponse(
                lsp.getId(),
                lsp.getCode(),
                lsp.getName(),
                lsp.getStatus().name(),
                lsp.getCreatedAt(),
                0,
                new PortfolioSummaryResponse(0, 0, 0, java.math.BigDecimal.ZERO, null)
        );
    }

    private static LspResponse toResponse(LspDirectoryService.LspDirectoryView view) {
        Lsp lsp = view.lsp();
        return new LspResponse(
                lsp.getId(),
                lsp.getCode(),
                lsp.getName(),
                lsp.getStatus().name(),
                lsp.getCreatedAt(),
                view.userCount(),
                toPortfolioSummaryResponse(view.portfolioSummary())
        );
    }

    private static LspDetailResponse toDetailResponse(LspDirectoryService.LspDetailView view) {
        Lsp lsp = view.lsp();
        return new LspDetailResponse(
                lsp.getId(),
                lsp.getCode(),
                lsp.getName(),
                lsp.getStatus().name(),
                lsp.getCreatedAt(),
                view.users().size(),
                toPortfolioSummaryResponse(view.portfolioSummary()),
                view.users().stream()
                        .map(user -> new LspUserResponse(
                                user.id(),
                                user.username(),
                                user.email(),
                                user.status().name(),
                                user.roles().stream().map(Enum::name).toList()
                        ))
                        .toList()
        );
    }

    private static LspAuditEventResponse toAuditEventResponse(UUID lspId, LspAuditEvent event) {
        return new LspAuditEventResponse(
                event.getId(),
                lspId,
                event.getAction(),
                event.getActorUsername(),
                event.getActorIp(),
                event.getReason() == null ? null : event.getReason().name(),
                event.getNote(),
                event.getCascadedClientCount(),
                event.getDetailsJson() == null ? null : event.getDetailsJson().toString(),
                event.getCorrelationId(),
                event.getCreatedAt()
        );
    }

    private static PortfolioSummaryResponse toPortfolioSummaryResponse(LspDirectoryService.LspPortfolioSummary summary) {
        return new PortfolioSummaryResponse(
                summary.loanApplicationCount(),
                summary.approvedLoanCount(),
                summary.disbursedLoanCount(),
                summary.totalDisbursedAmount(),
                summary.latestDisbursalDate()
        );
    }

    public record CreateLspRequest(
            @NotBlank String code,
            @NotBlank String name,
            LspStatus status
    ) {
        public CreateLspRequest {
            if (status == null) {
                status = LspStatus.ACTIVE;
            }
        }
    }

    public record UpdateLspStatusRequest(
            @NotBlank String status,
            String reason,
            String note
    ) {
        public LspStatus resolvedStatus() {
            return switch (status.trim().toUpperCase()) {
                case "ACTIVE" -> LspStatus.ACTIVE;
                case "INACTIVE", "DISABLED" -> LspStatus.INACTIVE;
                default -> throw new IllegalArgumentException("Unknown LSP status: " + status);
            };
        }

        public LspStatusChangeReason resolvedReason() {
            if (reason == null || reason.isBlank()) {
                throw new LspStatusUpdateException("REASON_REQUIRED", "Status change reason is required.");
            }
            try {
                return LspStatusChangeReason.valueOf(reason.trim().toUpperCase());
            } catch (IllegalArgumentException exception) {
                throw new LspStatusUpdateException("INVALID_REASON", "Unknown status change reason: " + reason);
            }
        }
    }

    public record LspAuditEventResponse(
            UUID id,
            UUID lspId,
            String action,
            String actorUsername,
            String actorIp,
            String reason,
            String note,
            int cascadedClientCount,
            String detailsJson,
            String correlationId,
            Instant createdAt
    ) {
    }

    public record LspResponse(
            UUID id,
            String code,
            String name,
            String status,
            Instant createdAt,
            int userCount,
            PortfolioSummaryResponse portfolioSummary
    ) {
    }

    public record LspDetailResponse(
            UUID id,
            String code,
            String name,
            String status,
            Instant createdAt,
            int userCount,
            PortfolioSummaryResponse portfolioSummary,
            List<LspUserResponse> users
    ) {
    }

    public record PortfolioSummaryResponse(
            int loanApplicationCount,
            int approvedLoanCount,
            int disbursedLoanCount,
            java.math.BigDecimal totalDisbursedAmount,
            java.time.LocalDate latestDisbursalDate
    ) {
    }

    public record LspUserResponse(
            UUID id,
            String username,
            String email,
            String status,
            List<String> roles
    ) {
    }

    private record LspStatusUpdateFingerprint(String lspId, UpdateLspStatusRequest request) {
    }
}
