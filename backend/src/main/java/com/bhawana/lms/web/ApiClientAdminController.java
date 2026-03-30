package com.bhawana.lms.web;

import com.bhawana.lms.domain.ApiClient;
import com.bhawana.lms.domain.ApiClientStatus;
import com.bhawana.lms.service.ApiClientManagementService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
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
    public CreatedApiClientResponse createApiClient(@Valid @RequestBody CreateApiClientRequest request) {
        ApiClientManagementService.CreatedApiClient created = apiClientManagementService.createClient(
                request.name(),
                request.description(),
                request.lspId(),
                request.status()
        );
        return toCreatedResponse(created.client(), created.rawSecret());
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
                apiClient.getLastUsedAt()
        );
    }

    private static CreatedApiClientResponse toCreatedResponse(ApiClient apiClient, String rawSecret) {
        ApiClientResponse base = toResponse(apiClient);
        return new CreatedApiClientResponse(
                base.id(),
                base.clientId(),
                rawSecret,
                base.name(),
                base.description(),
                base.status(),
                base.lspId(),
                base.lspName(),
                base.createdAt(),
                base.lastUsedAt()
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

    public record ApiClientResponse(
            String id,
            String clientId,
            String name,
            String description,
            String status,
            String lspId,
            String lspName,
            Instant createdAt,
            Instant lastUsedAt
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
            Instant lastUsedAt
    ) {
    }
}
