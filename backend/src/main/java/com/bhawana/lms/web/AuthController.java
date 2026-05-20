package com.bhawana.lms.web;

import com.bhawana.lms.domain.AppUser;
import com.bhawana.lms.domain.RefreshToken;
import com.bhawana.lms.repo.AppUserRepository;
import com.bhawana.lms.repo.RefreshTokenRepository;
import com.bhawana.lms.security.SecurityProperties;
import com.bhawana.lms.service.ApiClientAuthenticationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
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

    public AuthController(
            AuthenticationManager authenticationManager,
            JwtEncoder jwtEncoder,
            SecurityProperties securityProperties,
            AppUserRepository appUserRepository,
            PasswordEncoder passwordEncoder,
            ApiClientAuthenticationService apiClientAuthenticationService,
            RefreshTokenRepository refreshTokenRepository
    ) {
        this.authenticationManager = authenticationManager;
        this.jwtEncoder = jwtEncoder;
        this.securityProperties = securityProperties;
        this.appUserRepository = appUserRepository;
        this.passwordEncoder = passwordEncoder;
        this.apiClientAuthenticationService = apiClientAuthenticationService;
        this.refreshTokenRepository = refreshTokenRepository;
    }

    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
        TokenResponse tokenResponse = issuePasswordToken(request);
        String rawRefreshToken = generateAndStoreRefreshToken(request.username(), "PASSWORD");
        ResponseCookie cookie = buildRefreshCookie(rawRefreshToken, securityProperties.getJwt().getRefreshTtl().getSeconds());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(tokenResponse);
    }

    @PostMapping("/token")
    public ResponseEntity<TokenResponse> token(@Valid @RequestBody ClientCredentialsRequest request) {
        TokenResponse tokenResponse = issueClientCredentialsToken(request);
        String rawRefreshToken = generateAndStoreRefreshToken(request.clientId(), "API_CLIENT");
        ResponseCookie cookie = buildRefreshCookie(rawRefreshToken, securityProperties.getJwt().getRefreshTtl().getSeconds());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(tokenResponse);
    }

    @PostMapping("/refresh")
    public ResponseEntity<TokenResponse> refresh(
            @CookieValue(name = REFRESH_COOKIE_NAME, required = false) String refreshCookie
    ) {
        if (refreshCookie == null || refreshCookie.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        String tokenHash = sha256Hex(refreshCookie);
        RefreshToken existing = refreshTokenRepository.findByTokenHashAndRevokedFalse(tokenHash).orElse(null);
        if (existing == null || existing.getExpiresAt().isBefore(Instant.now())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        existing.revoke();
        refreshTokenRepository.save(existing);

        String username = existing.getUsername();
        String authType = existing.getAuthType();

        TokenResponse tokenResponse = mintTokenForStoredIdentity(username, authType);
        String newRawRefreshToken = generateAndStoreRefreshToken(username, authType);
        ResponseCookie cookie = buildRefreshCookie(newRawRefreshToken, securityProperties.getJwt().getRefreshTtl().getSeconds());

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(tokenResponse);
    }

    @PostMapping("/password")
    public ResponseEntity<TokenResponse> changePassword(
            Authentication authentication,
            @Valid @RequestBody ChangePasswordRequest request
    ) {
        AppUser user = appUserRepository.findByUsernameIgnoreCase(authentication.getName())
                .orElseThrow(() -> new IllegalArgumentException("Password changes are only supported for managed users."));

        if (!user.isPasswordChangeRequired()) {
            throw new IllegalArgumentException("Password change is not required for this account.");
        }

        if (passwordEncoder.matches(request.newPassword(), user.getPasswordHash())) {
            throw new IllegalArgumentException("New password must differ from the temporary password.");
        }

        user.changePassword(passwordEncoder.encode(request.newPassword()));
        appUserRepository.save(user);

        TokenResponse tokenResponse = mintTokenResponse(authentication);
        String rawRefreshToken = generateAndStoreRefreshToken(authentication.getName(), "PASSWORD");
        ResponseCookie cookie = buildRefreshCookie(rawRefreshToken, securityProperties.getJwt().getRefreshTtl().getSeconds());

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(tokenResponse);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @CookieValue(name = REFRESH_COOKIE_NAME, required = false) String refreshCookie
    ) {
        if (refreshCookie != null && !refreshCookie.isBlank()) {
            String tokenHash = sha256Hex(refreshCookie);
            refreshTokenRepository.findByTokenHashAndRevokedFalse(tokenHash)
                    .ifPresent(token -> {
                        token.revoke();
                        refreshTokenRepository.save(token);
                    });
        }

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

    public record ChangePasswordRequest(
            @NotBlank @Size(min = 12, max = 128) String newPassword
    ) {
    }

    private TokenResponse mintTokenForStoredIdentity(String username, String authType) {
        if ("API_CLIENT".equals(authType)) {
            ApiClientAuthenticationService.AuthenticatedApiClient apiClient =
                    apiClientAuthenticationService.lookupByClientId(username);
            return mintTokenResponse(
                    apiClient.clientId(),
                    List.of("LSP_API_CLIENT"),
                    new ManagedUserState(false, Instant.EPOCH),
                    Map.of(
                            "authType", "API_CLIENT",
                            "clientId", apiClient.clientId(),
                            "clientName", apiClient.clientName(),
                            "lspId", apiClient.lspId().toString(),
                            "lspCode", apiClient.lspCode()
                    )
            );
        }

        return mintTokenResponse(
                username,
                loadRolesForUsername(username),
                loadManagedUserState(username),
                loadManagedUserClaims(username)
        );
    }

    private List<String> loadRolesForUsername(String username) {
        return appUserRepository.findByUsernameIgnoreCase(username)
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
                .claim("pwdv", managedUserState.passwordChangedAt().toEpochMilli());
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

    private TokenResponse issuePasswordToken(LoginRequest request) {
        requireField(request.username(), "username");
        requireField(request.password(), "password");
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.username(), request.password())
        );
        return mintTokenResponse(authentication);
    }

    private TokenResponse issueClientCredentialsToken(ClientCredentialsRequest request) {
        ApiClientAuthenticationService.AuthenticatedApiClient apiClient = apiClientAuthenticationService.authenticate(
                request.clientId(),
                request.clientSecret()
        );
        return mintTokenResponse(
                apiClient.clientId(),
                List.of("LSP_API_CLIENT"),
                new ManagedUserState(false, Instant.EPOCH),
                Map.of(
                        "authType", "API_CLIENT",
                        "clientId", apiClient.clientId(),
                        "clientName", apiClient.clientName(),
                        "lspId", apiClient.lspId().toString(),
                        "lspCode", apiClient.lspCode()
                )
        );
    }

    private String generateAndStoreRefreshToken(String username, String authType) {
        byte[] randomBytes = new byte[32];
        SECURE_RANDOM.nextBytes(randomBytes);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
        String tokenHash = sha256Hex(rawToken);

        Duration refreshTtl = securityProperties.getJwt().getRefreshTtl();
        Instant expiresAt = Instant.now().plus(refreshTtl);

        RefreshToken refreshToken = new RefreshToken(tokenHash, username, authType, expiresAt);
        refreshTokenRepository.save(refreshToken);

        return rawToken;
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
        return appUserRepository.findByUsernameIgnoreCase(username)
                .map(user -> new ManagedUserState(user.isPasswordChangeRequired(), user.getPasswordChangedAt()))
                .orElseGet(() -> new ManagedUserState(false, Instant.EPOCH));
    }

    private Map<String, Object> loadManagedUserClaims(String username) {
        return appUserRepository.findByUsernameIgnoreCase(username)
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

    private static String requireField(String value, String fieldName) {
        if (value == null || value.trim().isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required.");
        }
        return value.trim();
    }

    private record ManagedUserState(boolean passwordChangeRequired, Instant passwordChangedAt) {
    }
}
