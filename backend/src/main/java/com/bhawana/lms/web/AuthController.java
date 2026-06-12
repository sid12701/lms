package com.bhawana.lms.web;

import com.bhawana.lms.common.correlation.CorrelationIdHolder;
import com.bhawana.lms.common.web.ClientIpAddresses;
import com.bhawana.lms.common.web.LspSurfaceIpAccessDeniedException;
import com.bhawana.lms.domain.ApiClient;
import com.bhawana.lms.domain.AppRole;
import com.bhawana.lms.domain.AppUser;
import com.bhawana.lms.domain.AuthEventFailureReason;
import com.bhawana.lms.domain.RoleCode;
import com.bhawana.lms.repo.ApiClientRepository;
import com.bhawana.lms.repo.AppUserRepository;
import com.bhawana.lms.security.ApiClientJwtSessionValidator;
import com.bhawana.lms.service.ApiClientAuthenticationService;
import com.bhawana.lms.service.AuthAuditService;
import com.bhawana.lms.service.AuthTokenService;
import com.bhawana.lms.service.LspSurfaceIpAllowlistService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final AppUserRepository appUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final ApiClientAuthenticationService apiClientAuthenticationService;
    private final ApiClientRepository apiClientRepository;
    private final LspSurfaceIpAllowlistService lspSurfaceIpAllowlistService;
    private final AuthAuditService authAuditService;
    private final AuthTokenService authTokenService;
    private final RefreshCookieFactory refreshCookieFactory;

    public AuthController(
            AuthenticationManager authenticationManager,
            AppUserRepository appUserRepository,
            PasswordEncoder passwordEncoder,
            ApiClientAuthenticationService apiClientAuthenticationService,
            ApiClientRepository apiClientRepository,
            LspSurfaceIpAllowlistService lspSurfaceIpAllowlistService,
            AuthAuditService authAuditService,
            AuthTokenService authTokenService,
            RefreshCookieFactory refreshCookieFactory
    ) {
        this.authenticationManager = authenticationManager;
        this.appUserRepository = appUserRepository;
        this.passwordEncoder = passwordEncoder;
        this.apiClientAuthenticationService = apiClientAuthenticationService;
        this.apiClientRepository = apiClientRepository;
        this.lspSurfaceIpAllowlistService = lspSurfaceIpAllowlistService;
        this.authAuditService = authAuditService;
        this.authTokenService = authTokenService;
        this.refreshCookieFactory = refreshCookieFactory;
    }

    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest
    ) {
        TokenResponse tokenResponse = issuePasswordToken(request, ClientIpAddresses.resolve(httpRequest));
        return ResponseEntity.ok()
                .headers(headers -> issueRefreshCookieForUsername(request.username(), headers))
                .body(tokenResponse);
    }

    @PostMapping("/token")
    public ResponseEntity<TokenResponse> token(
            @Valid @RequestBody ClientCredentialsRequest request,
            HttpServletRequest httpRequest
    ) {
        TokenResponse tokenResponse = issueClientCredentialsToken(request, ClientIpAddresses.resolve(httpRequest));
        ApiClient apiClient = apiClientRepository.findByClientId(request.clientId().trim())
                .orElseThrow(() -> new IllegalStateException("API client missing after successful authentication."));
        String rawRefreshToken = authTokenService.generateAndStoreRefreshTokenForApiClient(apiClient);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookieFactory.build(rawRefreshToken, refreshCookieFactory.refreshTtlSeconds()).toString())
                .body(tokenResponse);
    }

    @PostMapping("/refresh")
    @Transactional
    public ResponseEntity<?> refresh(
            @CookieValue(name = RefreshCookieFactory.COOKIE_NAME, required = false) String refreshCookie,
            HttpServletRequest httpRequest
    ) {
        String actorIp = ClientIpAddresses.resolve(httpRequest);
        String correlationId = CorrelationIdHolder.get();

        if (refreshCookie == null || refreshCookie.isBlank()) {
            authAuditService.recordTokenRefreshFailure(
                    AuthAuditService.ANONYMOUS_USERNAME,
                    AuthEventFailureReason.MISSING_REFRESH_COOKIE,
                    actorIp,
                    correlationId
            );
            return unauthorizedRefresh(AuthEventFailureReason.MISSING_REFRESH_COOKIE);
        }

        AuthTokenService.RefreshOutcome outcome = authTokenService.rotateRefreshToken(refreshCookie);
        if (!outcome.success()) {
            authAuditService.recordTokenRefreshFailure(
                    outcome.subjectUsername(),
                    outcome.failureReason(),
                    actorIp,
                    correlationId
            );
            return unauthorizedRefresh(outcome.failureReason());
        }

        authAuditService.recordTokenRefreshSuccess(
                outcome.subjectUsername(),
                outcome.userId(),
                outcome.apiClientId(),
                actorIp,
                correlationId
        );

        return ResponseEntity.ok()
                .header(
                        HttpHeaders.SET_COOKIE,
                        refreshCookieFactory.build(outcome.newRawRefreshToken(), refreshCookieFactory.refreshTtlSeconds()).toString()
                )
                .body(outcome.tokenResponse());
    }

    @PostMapping("/password")
    public ResponseEntity<TokenResponse> changePassword(
            Authentication authentication,
            @Valid @RequestBody ChangePasswordRequest request,
            HttpServletRequest httpRequest
    ) {
        AppUser user = appUserRepository.findByUsername(authentication.getName())
                .orElseThrow(() -> new IllegalArgumentException("Password changes are only supported for managed users."));

        if (!user.isPasswordChangeRequired()) {
            throw new IllegalArgumentException("Password change is not required for this account.");
        }

        if (passwordEncoder.matches(request.newPassword(), user.getPasswordHash())) {
            throw new IllegalArgumentException("New password must differ from the temporary password.");
        }

        user.changePassword(passwordEncoder.encode(request.newPassword()));
        appUserRepository.save(user);
        authAuditService.recordPasswordChanged(
                user,
                ClientIpAddresses.resolve(httpRequest),
                CorrelationIdHolder.get()
        );

        TokenResponse tokenResponse = authTokenService.mintTokenResponse(authentication);
        return ResponseEntity.ok()
                .headers(headers -> issueRefreshCookieForUsername(authentication.getName(), headers))
                .body(tokenResponse);
    }

    @PostMapping("/logout")
    @Transactional
    public ResponseEntity<Void> logout(
            @CookieValue(name = RefreshCookieFactory.COOKIE_NAME, required = false) String refreshCookie,
            HttpServletRequest httpRequest
    ) {
        String logoutUsername = AuthAuditService.ANONYMOUS_USERNAME;
        UUID logoutUserId = null;

        Authentication securityAuthentication = SecurityContextHolder.getContext().getAuthentication();
        if (securityAuthentication != null
                && securityAuthentication.isAuthenticated()
                && securityAuthentication.getName() != null
                && !"anonymousUser".equals(securityAuthentication.getName())) {
            logoutUsername = securityAuthentication.getName();
            logoutUserId = appUserRepository.findByUsername(logoutUsername).map(AppUser::getId).orElse(null);
        }

        if (refreshCookie != null && !refreshCookie.isBlank()) {
            AuthTokenService.RevokeOutcome revokeOutcome = authTokenService.revokeRefreshToken(refreshCookie);
            if (revokeOutcome.found()) {
                logoutUsername = revokeOutcome.subjectUsername();
                logoutUserId = revokeOutcome.userId();
            }
        }

        authAuditService.recordLogout(
                logoutUsername,
                logoutUserId,
                ClientIpAddresses.resolve(httpRequest),
                CorrelationIdHolder.get()
        );

        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, refreshCookieFactory.build("", 0).toString())
                .build();
    }

    public record LoginRequest(
            @NotBlank String username,
            @NotBlank String password
    ) {
    }

    public record ClientCredentialsRequest(
            @NotBlank String clientId,
            @NotBlank String clientSecret
    ) {
    }

    public record TokenResponse(
            String accessToken,
            String tokenType,
            long expiresInSeconds,
            boolean passwordChangeRequired
    ) {
    }

    public record RefreshFailureResponse(
            String code,
            String message
    ) {
    }

    public record ChangePasswordRequest(
            @NotBlank @Size(min = 12, max = 128) String newPassword
    ) {
    }

    private TokenResponse issuePasswordToken(LoginRequest request, String remoteAddress) {
        requireField(request.username(), "username");
        requireField(request.password(), "password");
        String correlationId = CorrelationIdHolder.get();
        AppUser user = appUserRepository.findByUsername(request.username()).orElse(null);
        if (user != null && user.isLocked()) {
            authAuditService.recordLoginFailure(
                    request.username(),
                    AuthEventFailureReason.INVALID_CREDENTIALS,
                    remoteAddress,
                    correlationId
            );
            throw new BadCredentialsException("Invalid credentials");
        }
        try {
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.username(), request.password())
            );
            try {
                if (user != null && user.getLsp() != null && hasLspUiRole(user)) {
                    lspSurfaceIpAllowlistService.assertUiLoginAllowed(user.getLsp().getId(), remoteAddress);
                }
            } catch (LspSurfaceIpAccessDeniedException exception) {
                authAuditService.recordLoginFailure(
                        request.username(),
                        AuthEventFailureReason.OTHER,
                        remoteAddress,
                        correlationId
                );
                throw exception;
            }
            authAuditService.recordLoginSuccess(request.username(), user, remoteAddress, correlationId);
            return authTokenService.mintTokenResponse(authentication);
        } catch (AuthenticationException exception) {
            authAuditService.recordLoginFailureFromException(
                    request.username(),
                    exception,
                    remoteAddress,
                    correlationId
            );
            throw exception;
        }
    }

    private TokenResponse issueClientCredentialsToken(ClientCredentialsRequest request, String remoteAddress) {
        String correlationId = CorrelationIdHolder.get();
        String clientId = requireField(request.clientId(), "clientId");
        try {
            ApiClientAuthenticationService.AuthenticatedApiClient apiClient = apiClientAuthenticationService.authenticate(
                    clientId,
                    request.clientSecret()
            );
            ApiClient freshClient = apiClientRepository.findByClientId(apiClient.clientId())
                    .orElseThrow(() -> new IllegalStateException("API client missing after authentication."));
            lspSurfaceIpAllowlistService.assertApiTokenIssuanceAllowed(freshClient.getLsp().getId(), remoteAddress);
            authAuditService.recordApiClientTokenSuccess(freshClient, remoteAddress, correlationId);
            return authTokenService.mintTokenResponse(
                    apiClient.clientId(),
                    List.of("LSP_API_CLIENT"),
                    new AuthTokenService.ManagedUserState(false, Instant.EPOCH, 0L),
                    Map.of(
                            ApiClientJwtSessionValidator.AUTH_TYPE_CLAIM,
                            ApiClientJwtSessionValidator.AUTH_TYPE_API_CLIENT,
                            "clientId", apiClient.clientId(),
                            "clientName", apiClient.clientName(),
                            "lspId", apiClient.lspId().toString(),
                            "lspCode", apiClient.lspCode(),
                            ApiClientJwtSessionValidator.TV_LSP_CLAIM, freshClient.getLsp().getTokenVersion(),
                            ApiClientJwtSessionValidator.TV_API_CLIENT_CLAIM, freshClient.getTokenVersion()
                    )
            );
        } catch (BadCredentialsException exception) {
            authAuditService.recordApiClientTokenFailure(
                    clientId,
                    AuthEventFailureReason.INVALID_CREDENTIALS,
                    remoteAddress,
                    correlationId
            );
            throw exception;
        }
    }

    private static ResponseEntity<RefreshFailureResponse> unauthorizedRefresh(AuthEventFailureReason reason) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(toRefreshFailureResponse(reason));
    }

    private static RefreshFailureResponse toRefreshFailureResponse(AuthEventFailureReason reason) {
        return switch (reason) {
            case MISSING_REFRESH_COOKIE -> new RefreshFailureResponse(
                    "MISSING_REFRESH_COOKIE",
                    "Refresh cookie is missing"
            );
            case TOKEN_EXPIRED -> new RefreshFailureResponse(
                    "TOKEN_EXPIRED",
                    "Refresh token has expired"
            );
            case TOKEN_REVOKED -> new RefreshFailureResponse(
                    "TOKEN_REVOKED",
                    "Refresh token was revoked"
            );
            case OTHER -> new RefreshFailureResponse(
                    "REFRESH_INVALID",
                    "Refresh token is invalid"
            );
            default -> new RefreshFailureResponse(
                    "REFRESH_INVALID",
                    "Refresh token is invalid"
            );
        };
    }

    private void issueRefreshCookieForUsername(String username, HttpHeaders headers) {
        authTokenService.generateAndStoreRefreshTokenForUsername(username).ifPresent(rawToken ->
                headers.add(
                        HttpHeaders.SET_COOKIE,
                        refreshCookieFactory.build(rawToken, refreshCookieFactory.refreshTtlSeconds()).toString()
                )
        );
    }

    private static boolean hasLspUiRole(AppUser user) {
        for (AppRole role : user.getRoles()) {
            RoleCode code = role.getCode();
            if (code == RoleCode.LSP_UI_READ || code == RoleCode.LSP_UI_WRITE) {
                return true;
            }
        }
        return false;
    }

    private static String requireField(String value, String fieldName) {
        if (value == null || value.trim().isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required.");
        }
        return value.trim();
    }
}
