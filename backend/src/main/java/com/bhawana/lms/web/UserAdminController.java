package com.bhawana.lms.web;

import com.bhawana.lms.common.api.PagedResult;
import com.bhawana.lms.common.api.PaginationResponseBuilder;
import com.bhawana.lms.common.correlation.CorrelationIdHolder;
import com.bhawana.lms.common.web.ClientIpAddresses;
import com.bhawana.lms.domain.AppUser;
import com.bhawana.lms.domain.RoleCode;
import com.bhawana.lms.domain.UserStatus;
import com.bhawana.lms.service.AdminApiIdempotencyService;
import com.bhawana.lms.service.UserAdminService;
import com.bhawana.lms.service.SessionRevocationService;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/internal/admin/users")
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
@Validated
public class UserAdminController {

    private static final String USER_CREATE = "USER_CREATE";
    private static final String USER_UPDATE = "USER_UPDATE";
    private static final String USER_RESET_PASSWORD = "USER_RESET_PASSWORD";

    private final UserAdminService userAdminService;
    private final AdminApiIdempotencyService adminApiIdempotencyService;

    public UserAdminController(
            UserAdminService userAdminService,
            AdminApiIdempotencyService adminApiIdempotencyService
    ) {
        this.userAdminService = userAdminService;
        this.adminApiIdempotencyService = adminApiIdempotencyService;
    }

    @GetMapping
    public ResponseEntity<List<UserResponse>> listUsers(
            @RequestParam(required = false) UserStatus status,
            @RequestParam(required = false) RoleCode role,
            @RequestParam(required = false) UUID lspId,
            @RequestParam(required = false, name = "q") String query,
            @RequestParam(required = false) @Min(0) Integer offset,
            @RequestParam(required = false) @Min(1) @Max(200) Integer limit,
            @RequestParam(required = false) String paginationDetails
    ) {
        boolean includePaginationDetails = PaginationResponseBuilder.includePaginationDetails(paginationDetails);
        // M20 — directory reads are always bounded; filters run against the
        // full dataset before pagination. `role` filters by granted-role
        // membership — a multi-role user appears under every role they hold,
        // not only under the UI's collapsed primary role.
        PagedResult<AppUser> page = userAdminService.listUsers(
                status, role, lspId, query, offset, limit);
        PagedResult<UserResponse> mapped = new PagedResult<>(
                page.items().stream().map(UserAdminController::toResponse).toList(),
                page.totalCount(),
                page.offset(),
                page.limit()
        );
        return PaginationResponseBuilder.toListResponse(mapped, includePaginationDetails);
    }

    @PostMapping
    public CreateUserResponse createUser(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateUserRequest request
    ) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return doCreateUser(request);
        }
        // Reveal-once: the persisted idempotent response carries only safe user
        // identity with a null credential, mirroring the reset-password flow below.
        // The request itself is the fingerprint source so a different payload under
        // the same key conflicts; only its SHA-256 hex is persisted, never the
        // cleartext password, and the transient value is never logged.
        AtomicReference<String> temporaryPasswordHolder = new AtomicReference<>();
        CreateUserResponse stored = adminApiIdempotencyService.execute(
                USER_CREATE,
                idempotencyKey,
                request,
                CreateUserResponse.class,
                () -> {
                    UserAdminService.CreateUserResult result = performCreateUser(request);
                    temporaryPasswordHolder.set(result.temporaryPassword());
                    return toCreateResponse(result.user(), null);
                }
        );
        return new CreateUserResponse(
                stored.id(),
                stored.username(),
                stored.email(),
                stored.status(),
                stored.lspId(),
                stored.lspName(),
                stored.roles(),
                stored.passwordChangeRequired(),
                stored.createdAt(),
                temporaryPasswordHolder.get()
        );
    }

    private CreateUserResponse doCreateUser(CreateUserRequest request) {
        UserAdminService.CreateUserResult result = performCreateUser(request);
        return toCreateResponse(result.user(), result.temporaryPassword());
    }

    private UserAdminService.CreateUserResult performCreateUser(CreateUserRequest request) {
        if (request.password() == null || request.password().isBlank()) {
            return userAdminService.createUserWithGeneratedPassword(
                    request.username(),
                    request.email(),
                    request.status(),
                    request.lspId(),
                    request.roles()
            );
        }
        // Explicit compatibility mode for callers that mint their own secret: the
        // caller already holds the value, so there is nothing to reveal — the
        // response credential stays null and only the hash is persisted.
        AppUser user = userAdminService.createUser(
                request.username(),
                request.email(),
                request.password(),
                request.status(),
                request.lspId(),
                request.roles()
        );
        return new UserAdminService.CreateUserResult(user, null);
    }

    @PutMapping("/{userId}")
    public UserResponse updateUser(
            @PathVariable UUID userId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody UpdateUserRequest updateRequest,
            @AuthenticationPrincipal Jwt principal,
            HttpServletRequest httpRequest
    ) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return doUpdateUser(userId, updateRequest, principal, httpRequest);
        }
        return adminApiIdempotencyService.execute(
                USER_UPDATE,
                idempotencyKey,
                new UserUpdateFingerprint(userId.toString(), updateRequest),
                UserResponse.class,
                () -> doUpdateUser(userId, updateRequest, principal, httpRequest)
        );
    }

    private UserResponse doUpdateUser(
            UUID userId,
            UpdateUserRequest updateRequest,
            Jwt principal,
            HttpServletRequest httpRequest
    ) {
        String actorUsername = principal == null ? "unknown" : principal.getSubject();
        AppUser user = userAdminService.updateUser(
                userId,
                actorUsername,
                ClientIpAddresses.resolve(httpRequest),
                updateRequest.email(),
                updateRequest.resolvedStatus(),
                updateRequest.lspId(),
                updateRequest.roles()
        );
        return toResponse(user);
    }

    @PostMapping("/{userId}/reset-password")
    public ResetPasswordResponse resetPassword(
            @PathVariable UUID userId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal Jwt principal,
            HttpServletRequest httpRequest
    ) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return doResetPassword(userId, principal, httpRequest);
        }

        AtomicReference<String> temporaryPasswordHolder = new AtomicReference<>();
        ResetPasswordResponse stored = adminApiIdempotencyService.execute(
                USER_RESET_PASSWORD,
                idempotencyKey,
                Map.of("userId", userId.toString()),
                ResetPasswordResponse.class,
                () -> {
                    UserAdminService.ResetPasswordResult result = performResetPassword(userId, principal, httpRequest);
                    temporaryPasswordHolder.set(result.temporaryPassword());
                    return new ResetPasswordResponse(
                            result.user().getId().toString(),
                            result.user().getUsername(),
                            null
                    );
                }
        );
        return new ResetPasswordResponse(stored.id(), stored.username(), temporaryPasswordHolder.get());
    }

    private ResetPasswordResponse doResetPassword(UUID userId, Jwt principal, HttpServletRequest httpRequest) {
        UserAdminService.ResetPasswordResult result = performResetPassword(userId, principal, httpRequest);
        return new ResetPasswordResponse(
                result.user().getId().toString(),
                result.user().getUsername(),
                result.temporaryPassword()
        );
    }

    private UserAdminService.ResetPasswordResult performResetPassword(
            UUID userId,
            Jwt principal,
            HttpServletRequest httpRequest
    ) {
        String actorUsername = principal == null ? "unknown" : principal.getSubject();
        return userAdminService.resetUserPassword(
                userId,
                actorUsername,
                ClientIpAddresses.resolve(httpRequest),
                CorrelationIdHolder.get()
        );
    }

    @PostMapping("/{userId}/revoke-sessions")
    public RevokeSessionsResponse revokeSessions(
            @PathVariable UUID userId,
            @RequestBody(required = false) RevokeSessionsRequest request,
            @AuthenticationPrincipal Jwt principal,
            HttpServletRequest httpRequest
    ) {
        String actorUsername = principal == null ? "unknown" : principal.getSubject();
        String reason = request == null ? null : request.reason();
        SessionRevocationService.RevocationResult result = userAdminService.revokeUserSessions(
                userId,
                actorUsername,
                reason,
                ClientIpAddresses.resolve(httpRequest),
                CorrelationIdHolder.get()
        );
        return new RevokeSessionsResponse(
                "OK",
                result.previousTokenVersion(),
                result.newTokenVersion(),
                result.refreshTokensRevoked()
        );
    }

    private static UserResponse toResponse(AppUser user) {
        return new UserResponse(
                user.getId().toString(),
                user.getUsername(),
                user.getEmail(),
                user.getStatus().name(),
                user.getLsp() == null ? null : user.getLsp().getId().toString(),
                user.getLsp() == null ? "All LSPs" : user.getLsp().getName(),
                user.getRoles().stream().map(role -> role.getCode().name()).sorted().toList(),
                user.getLockedAt() == null ? null : user.getLockedAt().toString(),
                user.getLockReason(),
                user.isPasswordChangeRequired(),
                user.getCreatedAt().toString()
        );
    }

    private static CreateUserResponse toCreateResponse(AppUser user, String temporaryPassword) {
        return new CreateUserResponse(
                user.getId().toString(),
                user.getUsername(),
                user.getEmail(),
                user.getStatus().name(),
                user.getLsp() == null ? null : user.getLsp().getId().toString(),
                user.getLsp() == null ? "All LSPs" : user.getLsp().getName(),
                user.getRoles().stream().map(role -> role.getCode().name()).sorted().toList(),
                user.isPasswordChangeRequired(),
                user.getCreatedAt().toString(),
                temporaryPassword
        );
    }

    public record UpdateUserRequest(
            @Email String email,
            Set<RoleCode> roles,
            String status,
            UUID lspId
    ) {
        public UserStatus resolvedStatus() {
            if (status == null || status.isBlank()) {
                return null;
            }
            return switch (status.trim().toUpperCase()) {
                case "ACTIVE" -> UserStatus.ACTIVE;
                case "INACTIVE", "DISABLED" -> UserStatus.INACTIVE;
                default -> throw new IllegalArgumentException("Unknown status: " + status);
            };
        }
    }

    public record CreateUserRequest(
            @NotBlank String username,
            @Email @NotBlank String email,
            @Schema(
                    description = "Optional caller-supplied secret for explicit compatibility mode. "
                            + "Absent or blank requests server-generated mode: the server mints a "
                            + "SecureRandom temporary password, persists only its hash, and reveals "
                            + "it once in CreateUserResponse. The preferred UI never sends this field.",
                    nullable = true,
                    accessMode = Schema.AccessMode.WRITE_ONLY
            )
            String password,
            UserStatus status,
            UUID lspId,
            @NotEmpty Set<RoleCode> roles
    ) {
        public CreateUserRequest {
            if (status == null) {
                status = UserStatus.ACTIVE;
            }
        }
    }

    public record CreateUserResponse(
            String id,
            String username,
            String email,
            String status,
            String lspId,
            String lspName,
            List<String> roles,
            boolean passwordChangeRequired,
            String createdAt,
            @Schema(
                    description = "One-time temporary password, present only in the first authorized "
                            + "create response of server-generated mode. Replays under the same "
                            + "Idempotency-Key, compatibility-mode creates, ordinary reads, errors, "
                            + "audit events, and persisted idempotency payloads never carry it. "
                            + "A lost value is recovered only via a deliberate reset-password "
                            + "command with a new key — replays never rotate the credential.",
                    nullable = true,
                    accessMode = Schema.AccessMode.READ_ONLY
            )
            String temporaryPassword
    ) {
    }

    public record UserResponse(
            String id,
            String username,
            String email,
            String status,
            String lspId,
            String lspName,
            List<String> roles,
            String lockedAt,
            String lockReason,
            boolean passwordChangeRequired,
            String createdAt
    ) {
    }

    public record ResetPasswordResponse(
            String id,
            String username,
            @Schema(
                    description = "One-time temporary password, present only in the first authorized "
                            + "reset response for an Idempotency-Key. Replays return null without "
                            + "rotating the credential; a lost value requires a deliberate new "
                            + "reset command. Never persisted in audit events, logs, or the stored "
                            + "idempotency payload.",
                    nullable = true,
                    accessMode = Schema.AccessMode.READ_ONLY
            )
            String temporaryPassword
    ) {
    }

    public record RevokeSessionsRequest(String reason) {
    }

    public record RevokeSessionsResponse(
            String status,
            long previousTokenVersion,
            long newTokenVersion,
            int refreshTokensRevoked
    ) {
    }

    private record UserUpdateFingerprint(String userId, UpdateUserRequest request) {
    }
}
