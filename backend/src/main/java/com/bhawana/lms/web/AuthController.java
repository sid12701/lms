package com.bhawana.lms.web;

import com.bhawana.lms.common.correlation.CorrelationIdHolder;
import com.bhawana.lms.common.web.ClientIpAddresses;
import com.bhawana.lms.common.web.LspSurfaceIpAccessDeniedException;
import com.bhawana.lms.domain.ApiClient;
import com.bhawana.lms.domain.AppUser;
import com.bhawana.lms.domain.AuthEventFailureReason;
import com.bhawana.lms.domain.RefreshToken;
import com.bhawana.lms.repo.ApiClientRepository;
import com.bhawana.lms.repo.AppUserRepository;
import com.bhawana.lms.repo.RefreshTokenRepository;
import com.bhawana.lms.security.ApiClientJwtSessionValidator;
import com.bhawana.lms.security.SecurityProperties;
import com.bhawana.lms.domain.AppRole;
import com.bhawana.lms.domain.RoleCode;
import com.bhawana.lms.service.ApiClientAuthenticationService;
import com.bhawana.lms.service.AuthAuditService;
import com.bhawana.lms.service.LspSurfaceIpAllowlistService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private static final String REFRESH_COOKIE_NAME = "lms-refresh";
    private static final String REFRESH_COOKIE_PATH = "/api/v1/auth";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final AuthenticationManager authenticationManager;
    private final JwtEncoder jwtEncoder;
    private final SecurityProperties securityProperties;
    private final AppUserRepository appUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final ApiClientAuthenticationService apiClientAuthenticationService;
    private final RefreshTokenRepository refreshTokenRepository;
    private final ApiClientRepository apiClientRepository;
    private final LspSurfaceIpAllowlistService lspSurfaceIpAllowlistService;
    private final AuthAuditService authAuditService;

    public AuthController(
            AuthenticationManager authenticationManager,
            JwtEncoder jwtEncoder,
            SecurityProperties securityProperties,
            AppUserRepository appUserRepository,
            PasswordEncoder passwordEncoder,
            ApiClientAuthenticationService apiClientAuthenticationService,
            RefreshTokenRepository refreshTokenRepository,
            ApiClientRepository apiClientRepository,
            LspSurfaceIpAllowlistService lspSurfaceIpAllowlistService,
            AuthAuditService authAuditService
    ) {
        this.authenticationManager = authenticationManager;
        this.jwtEncoder = jwtEncoder;
        this.securityProperties = securityProperties;
        this.appUserRepository = appUserRepository;
        this.passwordEncoder = passwordEncoder;
        this.apiClientAuthenticationService = apiClientAuthenticationService;
        this.refreshTokenRepository = refreshTokenRepository;
        this.apiClientRepository = apiClientRepository;
        this.lspSurfaceIpAllowlistService = lspSurfaceIpAllowlistService;
        this.authAuditService = authAuditService;
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
        String rawRefreshToken = generateAndStoreRefreshTokenForApiClient(apiClient);
        ResponseCookie cookie = buildRefreshCookie(rawRefreshToken, securityProperties.getJwt().getRefreshTtl().getSeconds());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(tokenResponse);
    }

    @PostMapping("/refresh")
    @Transactional
    public ResponseEntity<?> refresh(
            @CookieValue(name = REFRESH_COOKIE_NAME, required = false) String refreshCookie,
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

        String tokenHash = sha256Hex(refreshCookie);
        RefreshToken existing = refreshTokenRepository.findByTokenHash(tokenHash).orElse(null);
        if (existing == null) {
            authAuditService.recordTokenRefreshFailure(
                    AuthAuditService.UNKNOWN_USERNAME,
                    AuthEventFailureReason.TOKEN_EXPIRED,
                    actorIp,
                    correlationId
            );
            return unauthorizedRefresh(AuthEventFailureReason.TOKEN_EXPIRED);
        }
        if (existing.isRevoked()) {
            authAuditService.recordTokenRefreshFailure(
                    refreshSubjectUsername(existing),
                    AuthEventFailureReason.TOKEN_REVOKED,
                    actorIp,
                    correlationId
            );
            return unauthorizedRefresh(AuthEventFailureReason.TOKEN_REVOKED);
        }
        if (existing.getExpiresAt().isBefore(Instant.now())) {
            authAuditService.recordTokenRefreshFailure(
                    refreshSubjectUsername(existing),
                    AuthEventFailureReason.TOKEN_EXPIRED,
                    actorIp,
                    correlationId
            );
            return unauthorizedRefresh(AuthEventFailureReason.TOKEN_EXPIRED);
        }

        existing.revoke();
        refreshTokenRepository.save(existing);

        TokenResponse tokenResponse;
        String newRawRefreshToken;
        AppUser refreshUser = null;
        ApiClient refreshClient = null;
        if (existing.getAppUser() != null) {
            AppUser user = existing.getAppUser();
            refreshUser = user;
            tokenResponse = mintTokenForAppUser(user);
            newRawRefreshToken = generateAndStoreRefreshTokenForAppUser(user);
        } else if (existing.getApiClient() != null) {
            ApiClient apiClient = existing.getApiClient();
            refreshClient = apiClient;
            tokenResponse = mintTokenForApiClient(apiClient);
            newRawRefreshToken = generateAndStoreRefreshTokenForApiClient(apiClient);
        } else {
            authAuditService.recordTokenRefreshFailure(
                    AuthAuditService.UNKNOWN_USERNAME,
                    AuthEventFailureReason.OTHER,
                    actorIp,
                    correlationId
            );
            return unauthorizedRefresh(AuthEventFailureReason.OTHER);
        }

        authAuditService.recordTokenRefreshSuccess(
                refreshSubjectUsername(existing),
                refreshUser != null ? refreshUser.getId() : null,
                refreshClient != null ? refreshClient.getId() : null,
                actorIp,
                correlationId
        );

        ResponseCookie cookie = buildRefreshCookie(newRawRefreshToken, securityProperties.getJwt().getRefreshTtl().getSeconds());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(tokenResponse);
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

        TokenResponse tokenResponse = mintTokenResponse(authentication);
        return ResponseEntity.ok()
                .headers(headers -> issueRefreshCookieForUsername(authentication.getName(), headers))
                .body(tokenResponse);
    }

    @PostMapping("/logout")
    @Transactional
    public ResponseEntity<Void> logout(
            @CookieValue(name = REFRESH_COOKIE_NAME, required = false) String refreshCookie,
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
            String tokenHash = sha256Hex(refreshCookie);
            RefreshToken refreshToken = refreshTokenRepository.findByTokenHash(tokenHash).orElse(null);
            if (refreshToken != null) {
                logoutUsername = refreshSubjectUsername(refreshToken);
                if (refreshToken.getAppUser() != null) {
                    logoutUserId = refreshToken.getAppUser().getId();
                }
                if (!refreshToken.isRevoked()) {
                    refreshToken.revoke();
                    refreshTokenRepository.save(refreshToken);
                }
            }
        }

        authAuditService.recordLogout(
                logoutUsername,
                logoutUserId,
                ClientIpAddresses.resolve(httpRequest),
                CorrelationIdHolder.get()
        );

        ResponseCookie clearCookie = buildRefreshCookie("", 0);
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, clearCookie.toString())
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

    private TokenResponse mintTokenForAppUser(AppUser user) {
        return mintTokenResponse(
                user.getUsername(),
                loadRolesForUsername(user.getUsername()),
                loadManagedUserState(user.getUsername()),
                loadManagedUserClaims(user.getUsername())
        );
    }

    private TokenResponse mintTokenForApiClient(ApiClient apiClient) {
        ApiClientAuthenticationService.AuthenticatedApiClient view =
                apiClientAuthenticationService.lookupByClientId(apiClient.getClientId());
        ApiClient freshClient = apiClientRepository.findByClientId(view.clientId())
                .orElseThrow(() -> new IllegalStateException("API client missing after lookup."));
        return mintTokenResponse(
                view.clientId(),
                List.of("LSP_API_CLIENT"),
                new ManagedUserState(false, Instant.EPOCH, 0L),
                Map.of(
                        ApiClientJwtSessionValidator.AUTH_TYPE_CLAIM, ApiClientJwtSessionValidator.AUTH_TYPE_API_CLIENT,
                        "clientId", view.clientId(),
                        "clientName", view.clientName(),
                        "lspId", view.lspId().toString(),
                        "lspCode", view.lspCode(),
                        ApiClientJwtSessionValidator.TV_LSP_CLAIM, freshClient.getLsp().getTokenVersion(),
                        ApiClientJwtSessionValidator.TV_API_CLIENT_CLAIM, freshClient.getTokenVersion()
                )
        );
    }

    private List<String> loadRolesForUsername(String username) {
        return appUserRepository.findByUsername(username)
                .map(user -> user.getRoles().stream()
                        .map(role -> role.getCode().name())
                        .toList())
                .orElseGet(List::of);
    }

    private TokenResponse mintTokenResponse(Authentication authentication) {
        return mintTokenResponse(
                authentication.getName(),
                extractRoles(authentication),
                loadManagedUserState(authentication.getName()),
                loadManagedUserClaims(authentication.getName())
        );
    }

    private TokenResponse mintTokenResponse(
            String subject,
            List<String> roles,
            ManagedUserState managedUserState,
            Map<String, Object> extraClaims
    ) {
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plus(securityProperties.getJwt().getTtl());

        JwtClaimsSet.Builder claimsBuilder = JwtClaimsSet.builder()
                .issuer(securityProperties.getJwt().getIssuer())
                .subject(subject)
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .id(UUID.randomUUID().toString())
                .claim("roles", roles)
                .claim("pwdchg", managedUserState.passwordChangeRequired())
                .claim("pwdv", managedUserState.passwordChangedAt().toEpochMilli())
                .claim("tv", managedUserState.tokenVersion());
        extraClaims.forEach((key, value) -> {
            if (value != null) {
                claimsBuilder.claim(key, value);
            }
        });

        JwsHeader jwsHeader = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(jwsHeader, claimsBuilder.build())).getTokenValue();
        return new TokenResponse(
                token,
                "Bearer",
                expiresAt.getEpochSecond() - issuedAt.getEpochSecond(),
                managedUserState.passwordChangeRequired()
        );
    }

    private TokenResponse issuePasswordToken(LoginRequest request, String remoteAddress) {
        requireField(request.username(), "username");
        requireField(request.password(), "password");
        String correlationId = CorrelationIdHolder.get();
        try {
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.username(), request.password())
            );
            AppUser user = appUserRepository.findByUsername(request.username()).orElse(null);
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
            return mintTokenResponse(authentication);
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
            return mintTokenResponse(
                    apiClient.clientId(),
                    List.of("LSP_API_CLIENT"),
                    new ManagedUserState(false, Instant.EPOCH, 0L),
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

    private static String refreshSubjectUsername(RefreshToken refreshToken) {
        AppUser appUser = refreshToken.getAppUser();
        if (appUser != null) {
            return appUser.getUsername();
        }
        ApiClient apiClient = refreshToken.getApiClient();
        if (apiClient != null) {
            return apiClient.getClientId();
        }
        return AuthAuditService.UNKNOWN_USERNAME;
    }

    private String generateAndStoreRefreshTokenForAppUser(AppUser user) {
        RawRefreshToken raw = newRawRefreshToken();
        refreshTokenRepository.save(new RefreshToken(raw.hash(), user, raw.expiresAt()));
        return raw.value();
    }

    private String generateAndStoreRefreshTokenForApiClient(ApiClient apiClient) {
        RawRefreshToken raw = newRawRefreshToken();
        refreshTokenRepository.save(new RefreshToken(raw.hash(), apiClient, raw.expiresAt()));
        return raw.value();
    }

    private RawRefreshToken newRawRefreshToken() {
        byte[] randomBytes = new byte[32];
        SECURE_RANDOM.nextBytes(randomBytes);
        String value = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
        String hash = sha256Hex(value);
        Instant expiresAt = Instant.now().plus(securityProperties.getJwt().getRefreshTtl());
        return new RawRefreshToken(value, hash, expiresAt);
    }

    private record RawRefreshToken(String value, String hash, Instant expiresAt) {
    }

    private void issueRefreshCookieForUsername(String username, HttpHeaders headers) {
        appUserRepository.findByUsername(username).ifPresent(user -> {
            String rawToken = generateAndStoreRefreshTokenForAppUser(user);
            ResponseCookie cookie = buildRefreshCookie(rawToken, securityProperties.getJwt().getRefreshTtl().getSeconds());
            headers.add(HttpHeaders.SET_COOKIE, cookie.toString());
        });
    }

    private ResponseCookie buildRefreshCookie(String rawToken, long maxAgeSeconds) {
        return ResponseCookie.from(REFRESH_COOKIE_NAME, rawToken)
                .httpOnly(true)
                .secure(securityProperties.getJwt().isSecureCookies())
                .sameSite("Strict")
                .path(REFRESH_COOKIE_PATH)
                .maxAge(maxAgeSeconds)
                .build();
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 algorithm not available.", exception);
        }
    }

    private static List<String> extractRoles(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> !"ROLE_PASSWORD_CHANGE_REQUIRED".equals(authority))
                .map(role -> role.startsWith("ROLE_") ? role.substring("ROLE_".length()) : role)
                .toList();
    }

    private ManagedUserState loadManagedUserState(String username) {
        return appUserRepository.findByUsername(username)
                .map(user -> new ManagedUserState(
                        user.isPasswordChangeRequired(),
                        user.getPasswordChangedAt(),
                        user.getTokenVersion()))
                .orElseGet(() -> new ManagedUserState(false, Instant.EPOCH, 0L));
    }

    private Map<String, Object> loadManagedUserClaims(String username) {
        return appUserRepository.findByUsername(username)
                .map(user -> {
                    Map<String, Object> claims = new LinkedHashMap<>();
                    if (user.getLsp() != null) {
                        claims.put("lspId", user.getLsp().getId().toString());
                        claims.put("lspCode", user.getLsp().getCode());
                        claims.put("lspName", user.getLsp().getName());
                    }
                    return claims;
                })
                .orElseGet(Map::of);
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

    private record ManagedUserState(boolean passwordChangeRequired, Instant passwordChangedAt, long tokenVersion) {
    }
}
