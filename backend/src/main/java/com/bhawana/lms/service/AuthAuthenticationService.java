package com.bhawana.lms.service;

import com.bhawana.lms.common.api.TokenResponse;
import com.bhawana.lms.common.correlation.CorrelationIdHolder;
import com.bhawana.lms.common.api.error.LspSurfaceIpAccessDeniedException;
import com.bhawana.lms.domain.ApiClient;
import com.bhawana.lms.domain.AppRole;
import com.bhawana.lms.domain.AppUser;
import com.bhawana.lms.domain.AuthEventFailureReason;
import com.bhawana.lms.domain.LspStatus;
import com.bhawana.lms.domain.RoleCode;
import com.bhawana.lms.repo.ApiClientRepository;
import com.bhawana.lms.repo.AppUserRepository;
import com.bhawana.lms.security.ApiClientJwtSessionValidator;
import com.bhawana.lms.security.LspInactiveAuthenticationException;
import com.bhawana.lms.security.SecurityProperties;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthAuthenticationService {

    private final AuthenticationManager authenticationManager;
    private final AppUserRepository appUserRepository;
    private final ApiClientAuthenticationService apiClientAuthenticationService;
    private final ApiClientRepository apiClientRepository;
    private final LspSurfaceIpAllowlistService lspSurfaceIpAllowlistService;
    private final AuthAuditService authAuditService;
    private final AuthTokenService authTokenService;
    private final SecurityProperties securityProperties;

    public AuthAuthenticationService(
            AuthenticationManager authenticationManager,
            AppUserRepository appUserRepository,
            ApiClientAuthenticationService apiClientAuthenticationService,
            ApiClientRepository apiClientRepository,
            LspSurfaceIpAllowlistService lspSurfaceIpAllowlistService,
            AuthAuditService authAuditService,
            AuthTokenService authTokenService,
            SecurityProperties securityProperties
    ) {
        this.authenticationManager = authenticationManager;
        this.appUserRepository = appUserRepository;
        this.apiClientAuthenticationService = apiClientAuthenticationService;
        this.apiClientRepository = apiClientRepository;
        this.lspSurfaceIpAllowlistService = lspSurfaceIpAllowlistService;
        this.authAuditService = authAuditService;
        this.authTokenService = authTokenService;
        this.securityProperties = securityProperties;
    }

    public PasswordLoginResult login(String email, String password, String remoteAddress) {
        String normalizedEmail = requireField(email, "email").toLowerCase();
        requireField(password, "password");
        String correlationId = CorrelationIdHolder.get();
        AppUser user = appUserRepository.findByEmail(normalizedEmail).orElse(null);
        String username = resolveLoginUsername(normalizedEmail, user);
        String auditUsername = user != null ? user.getUsername() : username;
        if (user != null && user.isLocked()) {
            authAuditService.recordLoginFailure(
                    auditUsername,
                    AuthEventFailureReason.INVALID_CREDENTIALS,
                    remoteAddress,
                    correlationId
            );
            throw new BadCredentialsException("Invalid credentials");
        }
        try {
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(username, password)
            );
            AppUser authenticatedUser = appUserRepository.findByUsername(authentication.getName()).orElse(user);
            if (authenticatedUser != null
                    && authenticatedUser.getLsp() != null
                    && authenticatedUser.getLsp().getStatus() != LspStatus.ACTIVE) {
                authAuditService.recordLoginFailure(
                        authentication.getName(),
                        AuthEventFailureReason.LSP_INACTIVE,
                        remoteAddress,
                        correlationId
                );
                throw new LspInactiveAuthenticationException();
            }
            try {
                if (authenticatedUser != null && authenticatedUser.getLsp() != null && hasLspUiRole(authenticatedUser)) {
                    lspSurfaceIpAllowlistService.assertUiLoginAllowed(authenticatedUser.getLsp().getId(), remoteAddress);
                }
            } catch (LspSurfaceIpAccessDeniedException exception) {
                authAuditService.recordLoginFailure(
                        authentication.getName(),
                        AuthEventFailureReason.OTHER,
                        remoteAddress,
                        correlationId
                );
                throw exception;
            }
            authAuditService.recordLoginSuccess(authentication.getName(), authenticatedUser, remoteAddress, correlationId);
            return new PasswordLoginResult(
                    authTokenService.mintTokenResponse(authentication),
                    authentication.getName()
            );
        } catch (AuthenticationException exception) {
            authAuditService.recordLoginFailureFromException(
                    auditUsername,
                    exception,
                    remoteAddress,
                    correlationId
            );
            throw exception;
        }
    }

    public ClientCredentialsResult issueClientCredentialsToken(String clientId, String clientSecret, String remoteAddress) {
        String correlationId = CorrelationIdHolder.get();
        String normalizedClientId = requireField(clientId, "clientId");
        try {
            ApiClientAuthenticationService.AuthenticatedApiClient apiClient = apiClientAuthenticationService.authenticate(
                    normalizedClientId,
                    clientSecret
            );
            ApiClient freshClient = apiClientRepository.findByClientId(apiClient.clientId())
                    .orElseThrow(() -> new IllegalStateException("API client missing after authentication."));
            lspSurfaceIpAllowlistService.assertApiTokenIssuanceAllowed(freshClient.getLsp().getId(), remoteAddress);
            authAuditService.recordApiClientTokenSuccess(freshClient, remoteAddress, correlationId);
            TokenResponse tokenResponse = authTokenService.mintTokenResponse(
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
            return new ClientCredentialsResult(tokenResponse, freshClient);
        } catch (BadCredentialsException exception) {
            authAuditService.recordApiClientTokenFailure(
                    normalizedClientId,
                    AuthEventFailureReason.INVALID_CREDENTIALS,
                    remoteAddress,
                    correlationId
            );
            throw exception;
        }
    }

    @Transactional(readOnly = true)
    public Optional<UUID> findManagedUserId(String username) {
        return appUserRepository.findByUsername(username).map(AppUser::getId);
    }

    /**
     * Rotates the refresh token and records the success audit in a single transaction, so a failed
     * audit write rolls the rotation back and the caller's existing token stays valid rather than
     * being silently revoked. The failure path does not rotate, so its audit is left to the caller.
     */
    @Transactional
    public AuthTokenService.RefreshOutcome refreshSession(
            String rawRefreshToken,
            String actorIp,
            String correlationId
    ) {
        AuthTokenService.RefreshOutcome outcome = authTokenService.rotateRefreshToken(rawRefreshToken);
        if (outcome.success()) {
            authAuditService.recordTokenRefreshSuccess(
                    outcome.subjectUsername(),
                    outcome.userId(),
                    outcome.apiClientId(),
                    actorIp,
                    correlationId
            );
        }
        return outcome;
    }

    private String resolveLoginUsername(String email, AppUser user) {
        if (user != null) {
            return user.getUsername();
        }
        SecurityProperties.BootstrapUser bootstrapUser = securityProperties.getBootstrapUser();
        if (email.equalsIgnoreCase(bootstrapUser.getEmail())) {
            return bootstrapUser.getUsername().trim().toLowerCase();
        }
        return email;
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

    public static final class PasswordLoginResult {
        private final TokenResponse tokenResponse;
        private final String username;

        public PasswordLoginResult(TokenResponse tokenResponse, String username) {
            this.tokenResponse = tokenResponse;
            this.username = username;
        }

        public TokenResponse tokenResponse() {
            return tokenResponse;
        }

        public String username() {
            return username;
        }
    }

    public static final class ClientCredentialsResult {
        private final TokenResponse tokenResponse;
        private final ApiClient apiClient;

        public ClientCredentialsResult(TokenResponse tokenResponse, ApiClient apiClient) {
            this.tokenResponse = tokenResponse;
            this.apiClient = apiClient;
        }

        public TokenResponse tokenResponse() {
            return tokenResponse;
        }

        public ApiClient apiClient() {
            return apiClient;
        }
    }
}
