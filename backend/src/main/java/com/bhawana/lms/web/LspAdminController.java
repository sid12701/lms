package com.bhawana.lms.web;

import com.bhawana.lms.common.correlation.CorrelationIdHolder;
import com.bhawana.lms.common.web.ClientIpAddresses;
import com.bhawana.lms.common.web.LspStatusUpdateException;
import com.bhawana.lms.domain.Lsp;
import com.bhawana.lms.domain.LspAuditEvent;
import com.bhawana.lms.domain.LspStatus;
import com.bhawana.lms.domain.LspStatusChangeReason;
import com.bhawana.lms.domain.WebhookEventType;
import com.bhawana.lms.service.LspDirectoryService;
import com.bhawana.lms.service.LspStatusService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/internal/admin/lsps")
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
public class LspAdminController {

    private final LspDirectoryService lspDirectoryService;
    private final LspStatusService lspStatusService;

    public LspAdminController(LspDirectoryService lspDirectoryService, LspStatusService lspStatusService) {
        this.lspDirectoryService = lspDirectoryService;
        this.lspStatusService = lspStatusService;
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
    public LspResponse createLsp(@Valid @RequestBody CreateLspRequest request) {
        Lsp lsp = lspDirectoryService.createLsp(request.code(), request.name(), request.status());
        return toResponse(lsp);
    }

    @PutMapping("/{lspId}/status")
    public LspResponse updateStatus(
            @PathVariable UUID lspId,
            @Valid @RequestBody UpdateLspStatusRequest request,
            @AuthenticationPrincipal Jwt principal
    ) {
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

    @PutMapping("/{lspId}/webhook-subscription")
    public LspResponse updateWebhookSubscription(
            @PathVariable UUID lspId,
            @Valid @RequestBody UpdateWebhookSubscriptionRequest request,
            @AuthenticationPrincipal Jwt principal,
            HttpServletRequest httpRequest
    ) {
        String actorUsername = principal == null ? "unknown" : principal.getSubject();
        Lsp lsp = lspDirectoryService.updateWebhookSubscription(
                lspId,
                request.enabled(),
                request.endpointUrl(),
                request.signingSecret(),
                request.eventTypes(),
                actorUsername,
                ClientIpAddresses.resolve(httpRequest),
                CorrelationIdHolder.get()
        );
        return toResponse(lsp);
    }

    private static LspResponse toResponse(Lsp lsp) {
        return new LspResponse(
                lsp.getId().toString(),
                lsp.getCode(),
                lsp.getName(),
                lsp.getStatus().name(),
                new WebhookSubscriptionResponse(
                        lsp.isWebhookEnabled(),
                        lsp.getWebhookEndpointUrl(),
                        lsp.getWebhookSigningSecret(),
                        lsp.getWebhookEventTypes().stream().map(Enum::name).toList()
                ),
                0,
                new PortfolioSummaryResponse(0, 0, 0, java.math.BigDecimal.ZERO, null)
        );
    }

    private static LspResponse toResponse(LspDirectoryService.LspDirectoryView view) {
        Lsp lsp = view.lsp();
        return new LspResponse(
                lsp.getId().toString(),
                lsp.getCode(),
                lsp.getName(),
                lsp.getStatus().name(),
                new WebhookSubscriptionResponse(
                        lsp.isWebhookEnabled(),
                        lsp.getWebhookEndpointUrl(),
                        lsp.getWebhookSigningSecret(),
                        lsp.getWebhookEventTypes().stream().map(Enum::name).toList()
                ),
                view.userCount(),
                toPortfolioSummaryResponse(view.portfolioSummary())
        );
    }

    private static LspDetailResponse toDetailResponse(LspDirectoryService.LspDetailView view) {
        Lsp lsp = view.lsp();
        return new LspDetailResponse(
                lsp.getId().toString(),
                lsp.getCode(),
                lsp.getName(),
                lsp.getStatus().name(),
                new WebhookSubscriptionResponse(
                        lsp.isWebhookEnabled(),
                        lsp.getWebhookEndpointUrl(),
                        lsp.getWebhookSigningSecret(),
                        lsp.getWebhookEventTypes().stream().map(Enum::name).toList()
                ),
                view.users().size(),
                toPortfolioSummaryResponse(view.portfolioSummary()),
                view.users().stream()
                        .map(user -> new LspUserResponse(
                                user.id().toString(),
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
                event.getId().toString(),
                lspId.toString(),
                event.getAction(),
                event.getActorUsername(),
                event.getActorIp(),
                event.getReason() == null ? null : event.getReason().name(),
                event.getNote(),
                event.getCascadedClientCount(),
                event.getDetailsJson() == null ? null : event.getDetailsJson().toString(),
                event.getCorrelationId(),
                event.getCreatedAt().toString()
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

    public record UpdateWebhookSubscriptionRequest(
            boolean enabled,
            @Size(max = 500) String endpointUrl,
            @Size(max = 255) String signingSecret,
            List<WebhookEventType> eventTypes
    ) {
    }

    public record LspAuditEventResponse(
            String id,
            String lspId,
            String action,
            String actorUsername,
            String actorIp,
            String reason,
            String note,
            int cascadedClientCount,
            String detailsJson,
            String correlationId,
            String createdAt
    ) {
    }

    public record WebhookSubscriptionResponse(
            boolean enabled,
            String endpointUrl,
            String signingSecret,
            List<String> eventTypes
    ) {
    }

    public record LspResponse(
            String id,
            String code,
            String name,
            String status,
            WebhookSubscriptionResponse webhookSubscription,
            int userCount,
            PortfolioSummaryResponse portfolioSummary
    ) {
    }

    public record LspDetailResponse(
            String id,
            String code,
            String name,
            String status,
            WebhookSubscriptionResponse webhookSubscription,
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
            String id,
            String username,
            String email,
            String status,
            List<String> roles
    ) {
    }
}
