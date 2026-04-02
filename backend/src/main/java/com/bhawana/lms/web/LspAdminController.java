package com.bhawana.lms.web;

import com.bhawana.lms.domain.Lsp;
import com.bhawana.lms.domain.LspStatus;
import com.bhawana.lms.domain.WebhookEventType;
import com.bhawana.lms.service.AdminDirectoryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
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

    private final AdminDirectoryService adminDirectoryService;

    public LspAdminController(AdminDirectoryService adminDirectoryService) {
        this.adminDirectoryService = adminDirectoryService;
    }

    @GetMapping
    public List<LspResponse> listLsps() {
        return adminDirectoryService.listLspDirectoryViews().stream()
                .map(LspAdminController::toResponse)
                .toList();
    }

    @GetMapping("/{lspId}")
    public LspDetailResponse getLspDetail(@PathVariable UUID lspId) {
        return toDetailResponse(adminDirectoryService.getLspDetail(lspId));
    }

    @PostMapping
    public LspResponse createLsp(@Valid @RequestBody CreateLspRequest request) {
        Lsp lsp = adminDirectoryService.createLsp(request.code(), request.name(), request.status());
        return toResponse(lsp);
    }

    @PutMapping("/{lspId}/webhook-subscription")
    public LspResponse updateWebhookSubscription(
            @PathVariable UUID lspId,
            @Valid @RequestBody UpdateWebhookSubscriptionRequest request
    ) {
        Lsp lsp = adminDirectoryService.updateWebhookSubscription(
                lspId,
                request.enabled(),
                request.endpointUrl(),
                request.signingSecret(),
                request.eventTypes()
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

    private static LspResponse toResponse(AdminDirectoryService.LspDirectoryView view) {
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

    private static LspDetailResponse toDetailResponse(AdminDirectoryService.LspDetailView view) {
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

    private static PortfolioSummaryResponse toPortfolioSummaryResponse(AdminDirectoryService.LspPortfolioSummary summary) {
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

    public record UpdateWebhookSubscriptionRequest(
            boolean enabled,
            @Size(max = 500) String endpointUrl,
            @Size(max = 255) String signingSecret,
            List<WebhookEventType> eventTypes
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
