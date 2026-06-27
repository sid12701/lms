package com.bhawana.lms.web;

import com.bhawana.lms.common.correlation.CorrelationIdHolder;
import com.bhawana.lms.common.web.ClientIpAddresses;
import com.bhawana.lms.domain.AuthEventFailureReason;
import com.bhawana.lms.service.AuthAuditService;
import com.bhawana.lms.service.AuthAuthenticationService;
import com.bhawana.lms.service.AuthTokenService;
import com.bhawana.lms.service.UserAdminService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import com.bhawana.lms.web.AuthApiResponses.ChangePasswordRequest;
import com.bhawana.lms.web.AuthApiResponses.ClientCredentialsRequest;
import com.bhawana.lms.web.AuthApiResponses.LoginRequest;
import com.bhawana.lms.common.api.TokenResponse;
import com.bhawana.lms.web.AuthApiResponses.RefreshFailureResponse;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthAuthenticationService authAuthenticationService;
    private final AuthAuditService authAuditService;
    private final AuthTokenService authTokenService;
    private final RefreshCookieFactory refreshCookieFactory;
    private final UserAdminService userAdminService;

    public AuthController(
            AuthAuthenticationService authAuthenticationService,
            AuthAuditService authAuditService,
            AuthTokenService authTokenService,
            RefreshCookieFactory refreshCookieFactory,
            UserAdminService userAdminService
    ) {
        this.authAuthenticationService = authAuthenticationService;
        this.authAuditService = authAuditService;
        this.authTokenService = authTokenService;
        this.refreshCookieFactory = refreshCookieFactory;
        this.userAdminService = userAdminService;
    }

    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest
    ) {
        AuthAuthenticationService.PasswordLoginResult result = authAuthenticationService.login(
                request.email(),
                request.password(),
                ClientIpAddresses.resolve(httpRequest)
        );
        return ResponseEntity.ok()
                .headers(headers -> issueRefreshCookieForUsername(result.username(), headers))
                .body(result.tokenResponse());
    }

    @PostMapping("/token")
    public ResponseEntity<TokenResponse> token(
            @Valid @RequestBody ClientCredentialsRequest request,
            HttpServletRequest httpRequest
    ) {
        AuthAuthenticationService.ClientCredentialsResult result = authAuthenticationService.issueClientCredentialsToken(
                request.clientId(),
                request.clientSecret(),
                ClientIpAddresses.resolve(httpRequest)
        );
        String rawRefreshToken = authTokenService.generateAndStoreRefreshTokenForApiClient(result.apiClient());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookieFactory.build(rawRefreshToken, refreshCookieFactory.refreshTtlSeconds()).toString())
                .body(result.tokenResponse());
    }

    @PostMapping("/refresh")
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

        AuthTokenService.RefreshOutcome outcome =
                authAuthenticationService.refreshSession(refreshCookie, actorIp, correlationId);
        if (!outcome.success()) {
            authAuditService.recordTokenRefreshFailure(
                    outcome.subjectUsername(),
                    outcome.failureReason(),
                    actorIp,
                    correlationId
            );
            return unauthorizedRefresh(outcome.failureReason());
        }

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
        var user = userAdminService.completeRequiredPasswordChange(
                authentication.getName(),
                request.newPassword()
        );
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
            logoutUserId = authAuthenticationService.findManagedUserId(logoutUsername).orElse(null);
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
}
