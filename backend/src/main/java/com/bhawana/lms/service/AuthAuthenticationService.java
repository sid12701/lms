package com.bhawana.lms.service;

import com.bhawana.lms.common.api.TokenResponse;
import com.bhawana.lms.common.correlation.CorrelationIdHolder;
import com.bhawana.lms.domain.ApiClient;
import com.bhawana.lms.domain.AppUser;
import com.bhawana.lms.domain.AuthEventFailureReason;
import com.bhawana.lms.repo.AppUserRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthAuthenticationService {

    private final AppUserRepository appUserRepository;
    private final AuthAuditService authAuditService;
    private final AuthTokenService authTokenService;
    private final HumanSessionService humanSessionService;

    public AuthAuthenticationService(
            AppUserRepository appUserRepository,
            AuthAuditService authAuditService,
            AuthTokenService authTokenService,
            HumanSessionService humanSessionService
    ) {
        this.appUserRepository = appUserRepository;
        this.authAuditService = authAuditService;
        this.authTokenService = authTokenService;
        this.humanSessionService = humanSessionService;
    }

    public PasswordLoginResult login(String email, String password, String remoteAddress) {
        HumanSessionService.PasswordLoginIssued issued =
                humanSessionService.login(email, password, remoteAddress);
        return new PasswordLoginResult(
                issued.tokenResponse(),
                issued.username(),
                issued.rawRefreshToken()
        );
    }

    public ClientCredentialsResult issueClientCredentialsToken(String clientId, String clientSecret, String remoteAddress) {
        String correlationId = CorrelationIdHolder.get();
        String normalizedClientId = requireLoginField(clientId, "clientId");
        try {
            HumanSessionService.ClientCredentialsIssued issued = humanSessionService.issueClientCredentials(
                    normalizedClientId, clientSecret, remoteAddress, correlationId);
            return new ClientCredentialsResult(issued.tokenResponse(), issued.apiClient());
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
     * Fenced refresh rotation for both branches. The routing probe runs outside any
     * fence; the shared coordinator takes the fence in principal-first order with a
     * single-TX classification (human: user → family → token; machine: api_client →
     * token, no family). Failure audits for paths with no state change stay with the
     * caller (AuthController), except family-reuse failures already audited in-TX.
     */
    public AuthTokenService.RefreshOutcome refreshSession(
            String rawRefreshToken,
            String actorIp,
            String correlationId
    ) {
        AuthTokenService.RefreshSubjectProbe probe =
                authTokenService.classifyRefreshSubject(rawRefreshToken);
        if (probe.kind() == AuthTokenService.RefreshSubjectKind.MACHINE) {
            return humanSessionService.refreshMachine(probe, rawRefreshToken, actorIp, correlationId);
        }
        return humanSessionService.refresh(probe, rawRefreshToken, actorIp, correlationId);
    }

    /**
     * Fenced logout for both branches. Human logout revokes only the presented
     * session family (sibling families survive); machine logout revokes the single
     * presented row. Unknown or garbage cookies revoke nothing.
     */
    public AuthTokenService.RevokeOutcome logoutFamily(
            String rawRefreshToken,
            String actorIp,
            String correlationId
    ) {
        AuthTokenService.RefreshSubjectProbe probe =
                authTokenService.classifyRefreshSubject(rawRefreshToken);
        if (probe.kind() == AuthTokenService.RefreshSubjectKind.MACHINE) {
            return humanSessionService.logoutMachine(probe, rawRefreshToken, actorIp, correlationId);
        }
        return humanSessionService.logoutFamily(probe, rawRefreshToken, actorIp, correlationId);
    }

    private static String requireLoginField(String value, String fieldName) {
        if (value == null || value.trim().isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required.");
        }
        return value.trim();
    }

    public static final class PasswordLoginResult {
        private final TokenResponse tokenResponse;
        private final String username;
        private final String rawRefreshToken;

        public PasswordLoginResult(TokenResponse tokenResponse, String username, String rawRefreshToken) {
            this.tokenResponse = tokenResponse;
            this.username = username;
            this.rawRefreshToken = rawRefreshToken;
        }

        public TokenResponse tokenResponse() {
            return tokenResponse;
        }

        public String username() {
            return username;
        }

        public String rawRefreshToken() {
            return rawRefreshToken;
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
