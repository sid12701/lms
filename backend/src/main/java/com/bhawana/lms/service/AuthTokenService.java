package com.bhawana.lms.service;

import com.bhawana.lms.common.api.TokenResponse;
import com.bhawana.lms.domain.ApiClient;
import com.bhawana.lms.domain.AppUser;
import com.bhawana.lms.domain.AuthEventFailureReason;
import com.bhawana.lms.domain.RefreshToken;
import com.bhawana.lms.repo.ApiClientRepository;
import com.bhawana.lms.repo.AppUserRepository;
import com.bhawana.lms.repo.RefreshTokenRepository;
import com.bhawana.lms.security.ApiClientJwtSessionValidator;
import com.bhawana.lms.security.SecurityProperties;
import com.bhawana.lms.security.SessionValidityPolicy;
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
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthTokenService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final JwtEncoder jwtEncoder;
    private final SecurityProperties securityProperties;
    private final AppUserRepository appUserRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final ApiClientRepository apiClientRepository;
    private final ApiClientAuthenticationService apiClientAuthenticationService;
    private final SessionValidityPolicy sessionValidityPolicy;

    public AuthTokenService(
            JwtEncoder jwtEncoder,
            SecurityProperties securityProperties,
            AppUserRepository appUserRepository,
            RefreshTokenRepository refreshTokenRepository,
            ApiClientRepository apiClientRepository,
            ApiClientAuthenticationService apiClientAuthenticationService,
            SessionValidityPolicy sessionValidityPolicy
    ) {
        this.jwtEncoder = jwtEncoder;
        this.securityProperties = securityProperties;
        this.appUserRepository = appUserRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.apiClientRepository = apiClientRepository;
        this.apiClientAuthenticationService = apiClientAuthenticationService;
        this.sessionValidityPolicy = sessionValidityPolicy;
    }

    public TokenResponse mintTokenForAppUser(AppUser user) {
        return mintTokenResponse(
                user.getUsername(),
                loadRolesForUsername(user.getUsername()),
                loadManagedUserState(user.getUsername()),
                loadManagedUserClaims(user.getUsername())
        );
    }

    public TokenResponse mintTokenForApiClient(ApiClient apiClient) {
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

    public TokenResponse mintTokenResponse(Authentication authentication) {
        return mintTokenResponse(
                authentication.getName(),
                extractRoles(authentication),
                loadManagedUserState(authentication.getName()),
                loadManagedUserClaims(authentication.getName())
        );
    }

    public TokenResponse mintTokenResponse(
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

    public ManagedUserState loadManagedUserState(String username) {
        return appUserRepository.findByUsername(username)
                .map(user -> new ManagedUserState(
                        user.isPasswordChangeRequired(),
                        user.getPasswordChangedAt(),
                        user.getTokenVersion()))
                .orElseGet(() -> new ManagedUserState(false, Instant.EPOCH, 0L));
    }

    public String generateAndStoreRefreshTokenForAppUser(AppUser user) {
        RawRefreshToken raw = newRawRefreshToken();
        refreshTokenRepository.save(new RefreshToken(raw.hash(), user, raw.expiresAt()));
        return raw.value();
    }

    public String generateAndStoreRefreshTokenForApiClient(ApiClient apiClient) {
        RawRefreshToken raw = newRawRefreshToken();
        refreshTokenRepository.save(new RefreshToken(raw.hash(), apiClient, raw.expiresAt()));
        return raw.value();
    }

    public Optional<String> generateAndStoreRefreshTokenForUsername(String username) {
        return appUserRepository.findByUsername(username)
                .map(this::generateAndStoreRefreshTokenForAppUser);
    }

    @Transactional
    public RefreshOutcome rotateRefreshToken(String rawRefreshCookie) {
        String tokenHash = sha256Hex(rawRefreshCookie);
        RefreshToken existing = refreshTokenRepository.findByTokenHash(tokenHash).orElse(null);
        if (existing == null) {
            return RefreshOutcome.failure(AuthEventFailureReason.TOKEN_EXPIRED, AuthAuditService.UNKNOWN_USERNAME);
        }

        SessionValidityPolicy.Result sessionValidity = validateRefreshSubject(existing);
        if (!sessionValidity.valid()) {
            return RefreshOutcome.failure(
                    sessionValidityPolicy.toAuthEventFailureReason(sessionValidity.reason()),
                    refreshSubjectUsername(existing)
            );
        }

        if (existing.isRevoked()) {
            return RefreshOutcome.failure(AuthEventFailureReason.TOKEN_REVOKED, refreshSubjectUsername(existing));
        }
        if (existing.getExpiresAt().isBefore(Instant.now())) {
            return RefreshOutcome.failure(AuthEventFailureReason.TOKEN_EXPIRED, refreshSubjectUsername(existing));
        }

        existing.revoke();
        refreshTokenRepository.save(existing);

        if (existing.getAppUser() != null) {
            AppUser user = existing.getAppUser();
            return RefreshOutcome.success(
                    mintTokenForAppUser(user),
                    generateAndStoreRefreshTokenForAppUser(user),
                    refreshSubjectUsername(existing),
                    user.getId(),
                    null
            );
        }
        if (existing.getApiClient() != null) {
            ApiClient apiClient = existing.getApiClient();
            return RefreshOutcome.success(
                    mintTokenForApiClient(apiClient),
                    generateAndStoreRefreshTokenForApiClient(apiClient),
                    refreshSubjectUsername(existing),
                    null,
                    apiClient.getId()
            );
        }
        return RefreshOutcome.failure(AuthEventFailureReason.OTHER, AuthAuditService.UNKNOWN_USERNAME);
    }

    @Transactional
    public RevokeOutcome revokeRefreshToken(String rawRefreshCookie) {
        String tokenHash = sha256Hex(rawRefreshCookie);
        RefreshToken refreshToken = refreshTokenRepository.findByTokenHash(tokenHash).orElse(null);
        if (refreshToken == null) {
            return RevokeOutcome.empty();
        }
        String subjectUsername = refreshSubjectUsername(refreshToken);
        UUID userId = refreshToken.getAppUser() != null ? refreshToken.getAppUser().getId() : null;
        if (!refreshToken.isRevoked()) {
            refreshToken.revoke();
            refreshTokenRepository.save(refreshToken);
        }
        return new RevokeOutcome(subjectUsername, userId);
    }

    public static String refreshSubjectUsername(RefreshToken refreshToken) {
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

    private SessionValidityPolicy.Result validateRefreshSubject(RefreshToken refreshToken) {
        AppUser appUser = refreshToken.getAppUser();
        if (appUser != null) {
            return appUserRepository.findByUsername(appUser.getUsername())
                    .map(user -> sessionValidityPolicy.validate(
                            SessionValidityPolicy.SessionClaims.statusOnly(),
                            SessionValidityPolicy.SubjectSnapshot.of(SessionValidityPolicy.appUserSnapshot(user))
                    ))
                    .orElse(SessionValidityPolicy.Result.invalid(SessionValidityPolicy.InvalidReason.SUBJECT_MISSING));
        }

        ApiClient apiClient = refreshToken.getApiClient();
        if (apiClient != null) {
            return apiClientRepository.findByClientId(apiClient.getClientId())
                    .map(client -> sessionValidityPolicy.validate(
                            SessionValidityPolicy.SessionClaims.statusOnly(),
                            SessionValidityPolicy.SubjectSnapshot.of(SessionValidityPolicy.apiClientSnapshot(client))
                    ))
                    .orElse(SessionValidityPolicy.Result.invalid(SessionValidityPolicy.InvalidReason.SUBJECT_MISSING));
        }

        return SessionValidityPolicy.Result.invalid(SessionValidityPolicy.InvalidReason.SUBJECT_MISSING);
    }

    private List<String> loadRolesForUsername(String username) {
        return appUserRepository.findByUsername(username)
                .map(user -> user.getRoles().stream()
                        .map(role -> role.getCode().name())
                        .toList())
                .orElseGet(List::of);
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

    private RawRefreshToken newRawRefreshToken() {
        byte[] randomBytes = new byte[32];
        SECURE_RANDOM.nextBytes(randomBytes);
        String value = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
        String hash = sha256Hex(value);
        Instant expiresAt = Instant.now().plus(securityProperties.getJwt().getRefreshTtl());
        return new RawRefreshToken(value, hash, expiresAt);
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

    private record RawRefreshToken(String value, String hash, Instant expiresAt) {
    }

    public record ManagedUserState(boolean passwordChangeRequired, Instant passwordChangedAt, long tokenVersion) {
    }

    public record RefreshOutcome(
            boolean success,
            AuthEventFailureReason failureReason,
            String subjectUsername,
            TokenResponse tokenResponse,
            String newRawRefreshToken,
            UUID userId,
            UUID apiClientId
    ) {
        public static RefreshOutcome success(
                TokenResponse tokenResponse,
                String newRawRefreshToken,
                String subjectUsername,
                UUID userId,
                UUID apiClientId
        ) {
            return new RefreshOutcome(
                    true,
                    null,
                    subjectUsername,
                    tokenResponse,
                    newRawRefreshToken,
                    userId,
                    apiClientId
            );
        }

        public static RefreshOutcome failure(AuthEventFailureReason failureReason, String subjectUsername) {
            return new RefreshOutcome(false, failureReason, subjectUsername, null, null, null, null);
        }
    }

    public record RevokeOutcome(String subjectUsername, UUID userId) {
        public static RevokeOutcome empty() {
            return new RevokeOutcome(null, null);
        }

        public boolean found() {
            return subjectUsername != null;
        }
    }
}
