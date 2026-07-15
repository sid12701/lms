package com.bhawana.lms.web;

import com.bhawana.lms.common.web.ClientIpAddresses;
import com.bhawana.lms.domain.ApiClient;
import com.bhawana.lms.domain.ApiClientStatus;
import com.bhawana.lms.service.ApiClientManagementService;
import com.bhawana.lms.service.LspApiIdempotencyService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/internal/admin/api-clients")
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
public class ApiClientAdminController {

    private static final String CREATE_OPERATION_KEY = "API_CLIENT_CREATE";
    private static final String ROTATE_OPERATION_KEY = "API_CLIENT_ROTATE";

    private final ApiClientManagementService apiClientManagementService;
    private final LspApiIdempotencyService lspApiIdempotencyService;

    public ApiClientAdminController(
            ApiClientManagementService apiClientManagementService,
            LspApiIdempotencyService lspApiIdempotencyService
    ) {
        this.apiClientManagementService = apiClientManagementService;
        this.lspApiIdempotencyService = lspApiIdempotencyService;
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
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal Jwt principal,
            HttpServletRequest httpRequest
    ) {
        String actorUsername = principal == null ? "unknown" : principal.getSubject();
        String actorIp = ClientIpAddresses.resolve(httpRequest);

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return toCreatedResponse(performCreate(request, actorUsername, actorIp));
        }

        // Idempotent create: the one-time cleartext secret is captured out-of-band via the holder
        // (populated only on the fresh execution) so it is never serialized into the idempotency
        // record. A replay therefore returns the stored metadata with no secret.
        AtomicReference<String> secretHolder = new AtomicReference<>();
        ApiClientResponse metadata = lspApiIdempotencyService.execute(
                request.lspId(),
                CREATE_OPERATION_KEY,
                idempotencyKey,
                new CreateApiClientFingerprint(
                        request.name(),
                        request.description(),
                        request.lspId().toString(),
                        request.status().name()
                ),
                ApiClientResponse.class,
                () -> {
                    ApiClientManagementService.CreatedApiClient created =
                            performCreate(request, actorUsername, actorIp);
                    secretHolder.set(created.rawSecret());
                    return toResponse(created.client());
                }
        );
        return toCreatedResponse(metadata, secretHolder.get());
    }

    private ApiClientManagementService.CreatedApiClient performCreate(
            CreateApiClientRequest request,
            String actorUsername,
            String actorIp
    ) {
        return apiClientManagementService.createClient(
                request.name(),
                request.description(),
                request.lspId(),
                request.status(),
                actorUsername,
                actorIp
        );
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
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal Jwt principal,
            HttpServletRequest httpRequest
    ) {
        String actorUsername = principal == null ? "unknown" : principal.getSubject();
        String actorIp = ClientIpAddresses.resolve(httpRequest);
        Integer graceSeconds = request == null ? null : request.graceSeconds();

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return toRotateResponse(performRotate(id, actorUsername, actorIp, graceSeconds));
        }

        // Resolve the owning LSP first so an unknown client still yields 404 before idempotency runs.
        UUID lspId = apiClientManagementService.requireOwningLspId(id);

        // As with create, the freshly minted secret is captured out-of-band so it is never persisted
        // in the idempotency record; a replay returns the stored metadata with a null secret.
        AtomicReference<String> secretHolder = new AtomicReference<>();
        RotateSecretResponse stored = lspApiIdempotencyService.execute(
                lspId,
                ROTATE_OPERATION_KEY,
                idempotencyKey,
                new RotateSecretFingerprint(id.toString(), graceSeconds),
                RotateSecretResponse.class,
                () -> {
                    ApiClientManagementService.RotatedApiClient rotated =
                            performRotate(id, actorUsername, actorIp, graceSeconds);
                    secretHolder.set(rotated.rawSecret());
                    return new RotateSecretResponse(
                            rotated.clientView().client().getClientId(),
                            null,
                            rotated.oldSecretValidUntil()
                    );
                }
        );
        return new RotateSecretResponse(stored.clientId(), secretHolder.get(), stored.oldSecretValidUntil());
    }

    private ApiClientManagementService.RotatedApiClient performRotate(
            UUID id,
            String actorUsername,
            String actorIp,
            Integer graceSeconds
    ) {
        return apiClientManagementService.rotateSecret(id, actorUsername, actorIp, graceSeconds);
    }

    private static RotateSecretResponse toRotateResponse(ApiClientManagementService.RotatedApiClient rotated) {
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
        return toCreatedResponse(toResponse(created.client()), created.rawSecret());
    }

    private static CreatedApiClientResponse toCreatedResponse(ApiClientResponse base, String clientSecret) {
        return new CreatedApiClientResponse(
                base.id(),
                base.clientId(),
                clientSecret,
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

    /** Idempotency request fingerprint for create — binds the key to the client's defining fields. */
    private record CreateApiClientFingerprint(
            String name,
            String description,
            String lspId,
            String status
    ) {
    }

    /** Idempotency request fingerprint for rotate — binds the key to the target client + grace. */
    private record RotateSecretFingerprint(String clientId, Integer graceSeconds) {
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
