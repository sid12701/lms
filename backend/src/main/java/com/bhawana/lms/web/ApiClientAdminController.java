package com.bhawana.lms.web;

import com.bhawana.lms.common.web.ClientIpAddresses;
import com.bhawana.lms.domain.ApiClient;
import com.bhawana.lms.domain.ApiClientStatus;
import com.bhawana.lms.service.ApiClientManagementService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/internal/admin/api-clients")
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
public class ApiClientAdminController {

    private final ApiClientManagementService apiClientManagementService;

    public ApiClientAdminController(ApiClientManagementService apiClientManagementService) {
        this.apiClientManagementService = apiClientManagementService;
    }

    @GetMapping
    public List<ApiClientResponse> listApiClients() {
        return apiClientManagementService.listClients().stream()
                .map(ApiClientAdminController::toResponse)
                .toList();
    }

    @PostMapping
    public CreatedApiClientResponse createApiClient(
            @Valid @RequestBody CreateApiClientRequest request,
            @AuthenticationPrincipal Jwt principal,
            HttpServletRequest httpRequest
    ) {
        String actorUsername = principal == null ? "unknown" : principal.getSubject();
        ApiClientManagementService.CreatedApiClient created = apiClientManagementService.createClient(
                request.name(),
                request.description(),
                request.lspId(),
                request.status(),
                actorUsername,
                ClientIpAddresses.resolve(httpRequest)
        );
        return toCreatedResponse(created);
    }

    @PutMapping("/{id}")
    public ApiClientResponse updateApiClient(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateApiClientRequest request,
            @AuthenticationPrincipal Jwt principal,
            HttpServletRequest httpRequest
    ) {
        String actorUsername = principal == null ? "unknown" : principal.getSubject();
        ApiClientManagementService.ApiClientView updated = apiClientManagementService.updateClient(
                id,
                actorUsername,
                ClientIpAddresses.resolve(httpRequest),
                request.name(),
                request.description(),
                request.resolvedStatus()
        );
        return toResponse(updated);
    }

    @PostMapping("/{id}/rotate-secret")
    public RotateSecretResponse rotateSecret(
            @PathVariable UUID id,
            @RequestBody(required = false) RotateSecretRequest request,
            @AuthenticationPrincipal Jwt principal,
            HttpServletRequest httpRequest
    ) {
        String actorUsername = principal == null ? "unknown" : principal.getSubject();
        Integer graceSeconds = request == null ? null : request.graceSeconds();
        ApiClientManagementService.RotatedApiClient rotated = apiClientManagementService.rotateSecret(
                id,
                actorUsername,
                ClientIpAddresses.resolve(httpRequest),
                graceSeconds
        );
        return new RotateSecretResponse(
                rotated.clientView().client().getClientId(),
                rotated.rawSecret(),
                rotated.oldSecretValidUntil()
        );
    }

    private static ApiClientResponse toResponse(ApiClientManagementService.ApiClientView view) {
        return toResponse(view.client());
    }

    private static ApiClientResponse toResponse(ApiClient apiClient) {
        return new ApiClientResponse(
                apiClient.getId().toString(),
                apiClient.getClientId(),
                apiClient.getName(),
                apiClient.getDescription(),
                apiClient.getStatus().name(),
                apiClient.getLsp().getId().toString(),
                apiClient.getLsp().getName(),
                apiClient.getCreatedAt(),
                apiClient.getLastUsedAt(),
                apiClient.getLastRotatedAt()
        );
    }

    private static CreatedApiClientResponse toCreatedResponse(ApiClientManagementService.CreatedApiClient created) {
        ApiClientResponse base = toResponse(created.client());
        return new CreatedApiClientResponse(
                base.id(),
                base.clientId(),
                created.rawSecret(),
                base.name(),
                base.description(),
                base.status(),
                base.lspId(),
                base.lspName(),
                base.createdAt(),
                base.lastUsedAt(),
                base.lastRotatedAt()
        );
    }

    public record CreateApiClientRequest(
            @NotBlank String name,
            String description,
            @NotNull UUID lspId,
            ApiClientStatus status
    ) {
        public CreateApiClientRequest {
            if (status == null) {
                status = ApiClientStatus.ACTIVE;
            }
        }
    }

    public record UpdateApiClientRequest(
            String name,
            String description,
            String status
    ) {
        public ApiClientStatus resolvedStatus() {
            if (status == null || status.isBlank()) {
                return null;
            }
            return switch (status.trim().toUpperCase()) {
                case "ACTIVE" -> ApiClientStatus.ACTIVE;
                case "INACTIVE", "DISABLED" -> ApiClientStatus.INACTIVE;
                default -> throw new IllegalArgumentException("Unknown status: " + status);
            };
        }
    }

    public record RotateSecretRequest(Integer graceSeconds) {
    }

    public record ApiClientResponse(
            String id,
            String clientId,
            String name,
            String description,
            String status,
            String lspId,
            String lspName,
            Instant createdAt,
            Instant lastUsedAt,
            Instant lastRotatedAt
    ) {
    }

    public record CreatedApiClientResponse(
            String id,
            String clientId,
            String clientSecret,
            String name,
            String description,
            String status,
            String lspId,
            String lspName,
            Instant createdAt,
            Instant lastUsedAt,
            Instant lastRotatedAt
    ) {
    }

    public record RotateSecretResponse(
            String clientId,
            String clientSecret,
            Instant oldSecretValidUntil
    ) {
    }
}
