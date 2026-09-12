package com.bhawana.lms.web;

import com.bhawana.lms.common.correlation.CorrelationIdHolder;
import com.bhawana.lms.common.web.ClientIpAddresses;
import com.bhawana.lms.domain.AuthEventFailureReason;
import com.bhawana.lms.security.HumanMachineSurfaceGuard;
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
    private final com.bhawana.lms.service.HumanSessionService humanSessionService;

    public AuthController(
            AuthAuthenticationService authAuthenticationService,
            AuthAuditService authAuditService,
            AuthTokenService authTokenService,
            RefreshCookieFactory refreshCookieFactory,
            UserAdminService userAdminService,
            com.bhawana.lms.service.HumanSessionService humanSessionService
    ) {
        this.authAuthenticationService = authAuthenticationService;
        this.authAuditService = authAuditService;
        this.authTokenService = authTokenService;
        this.refreshCookieFactory = refreshCookieFactory;
        this.userAdminService = userAdminService;
        this.humanSessionService = humanSessionService;
    }

    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest
    ) {
        // Access plus refresh come from one principal-fenced issuance TX; no
        // separate controller-side mint exists, closing the stale-proof gap.
        AuthAuthenticationService.PasswordLoginResult result = authAuthenticationService.login(
                request.email(),
                request.password(),
                ClientIpAddresses.resolve(httpRequest)
        );
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.SET_COOKIE,
                        refreshCookieFactory.build(
                                result.rawRefreshToken(), refreshCookieFactory.refreshTtlSeconds()).toString()
                )
                .body(result.tokenResponse());
    }

    @PostMapping("/token")
    public ResponseEntity<TokenResponse> token(
            @Valid @RequestBody ClientCredentialsRequest request,
            HttpServletRequest httpRequest
    ) {
        // Machine clients receive access only; refresh credentials are not issued.
        AuthAuthenticationService.ClientCredentialsResult result = authAuthenticationService.issueClientCredentialsToken(
                request.clientId(),
                request.clientSecret(),
                ClientIpAddresses.resolve(httpRequest)
        );
        return ResponseEntity.ok(result.tokenResponse());
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
            // Family-reuse failures are already audited inside the committing fencing
            // transaction (revocation + failure row commit together); all other failures
            // carry no state change and are audited here by the caller.
            if (!outcome.failureAuditedInTx()) {
                authAuditService.recordTokenRefreshFailure(
                        outcome.subjectUsername(),
                        outcome.failureReason(),
                        actorIp,
                        correlationId
                );
            }
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
        // Human-only surface: a machine API_CLIENT token whose clientId collides with a human
        // username would otherwise reset that human's password via authentication.getName().
        // Denied here (403) before any managed-user mutation; see HumanMachineSurfaceGuard.
        HumanMachineSurfaceGuard.requireHuman(authentication);
        // Self-service password change is an all-user fence with single-TX re-issuance
        // from the post-change snapshot (never from the pre-change Authentication). The
        // presented bearer material (sid/tv/pwdv) is extracted from the live JWT and
        // revalidated inside the fence, so a deferred request cannot overwrite an
        // intervening admin reset/disable and mint a fresh session.
        com.bhawana.lms.service.HumanSessionService.PasswordLoginIssued issued =
                humanSessionService.changePasswordAndIssue(
                        authentication.getName(),
                        request.newPassword(),
                        presentedSession(authentication),
                        ClientIpAddresses.resolve(httpRequest),
                        CorrelationIdHolder.get()
                );
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.SET_COOKIE,
                        refreshCookieFactory.build(
                                issued.rawRefreshToken(), refreshCookieFactory.refreshTtlSeconds()).toString()
                )
                .body(issued.tokenResponse());
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @CookieValue(name = RefreshCookieFactory.COOKIE_NAME, required = false) String refreshCookie,
            HttpServletRequest httpRequest
    ) {
        // Per-family revoke in one TX with the logout audit; sibling families survive.
        // The clearing cookie is Path-exact in all paths, garbage cookie included.
        if (refreshCookie != null && !refreshCookie.isBlank()) {
            authAuthenticationService.logoutFamily(
                    refreshCookie,
                    ClientIpAddresses.resolve(httpRequest),
                    CorrelationIdHolder.get()
            );
        } else {
            authAuditService.recordLogout(
                    AuthAuditService.ANONYMOUS_USERNAME,
                    null,
                    ClientIpAddresses.resolve(httpRequest),
                    CorrelationIdHolder.get()
            );
        }

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
            case TOKEN_ROTATED -> new RefreshFailureResponse(
                    "TOKEN_ROTATED",
                    "Refresh token was already rotated"
            );
            case USER_INACTIVE -> new RefreshFailureResponse(
                    "USER_INACTIVE",
                    "User is not active"
            );
            case LSP_INACTIVE -> new RefreshFailureResponse(
                    "LSP_INACTIVE",
                    "LSP is not active"
            );
            case SESSION_INVALID_STATUS -> new RefreshFailureResponse(
                    "SESSION_INVALID_STATUS",
                    "Session is no longer valid"
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

    /**
     * Extracts the presented bearer material (sid/tv/pwdv) from the live request JWT
     * for in-fence revalidation. Missing or partial material is rejected by the service.
     */
    private static com.bhawana.lms.service.HumanSessionService.PresentedSession presentedSession(
            Authentication authentication
    ) {
        if (authentication == null || !(authentication.getPrincipal() instanceof org.springframework.security.oauth2.jwt.Jwt jwt)) {
            return new com.bhawana.lms.service.HumanSessionService.PresentedSession(null, null, null);
        }
        return new com.bhawana.lms.service.HumanSessionService.PresentedSession(
                jwt.getClaimAsString("sid"),
                longClaim(jwt, "tv"),
                longClaim(jwt, "pwdv")
        );
    }

    private static Long longClaim(org.springframework.security.oauth2.jwt.Jwt jwt, String name) {
        Object value = jwt.getClaims().get(name);
        return value instanceof Number number ? number.longValue() : null;
    }
}
