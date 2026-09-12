package com.bhawana.lms.service;

import com.bhawana.lms.common.api.TokenResponse;
import com.bhawana.lms.domain.AppUser;
import com.bhawana.lms.domain.AuthEventFailureReason;
import com.bhawana.lms.domain.RefreshToken;
import com.bhawana.lms.domain.UserStatus;
import com.bhawana.lms.repo.AppUserRepository;
import com.bhawana.lms.repo.RefreshTokenRepository;
import com.bhawana.lms.security.ApiClientJwtSessionValidator;
import com.bhawana.lms.security.SecurityProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Token minting plus the refresh routing probe.
 *
 * <p>Every human credential lifecycle (login issuance, refresh rotation, logout,
 * password-change issuance, all-user revocation) is fenced in
 * {@link HumanSessionService} (principal lock first, single committing transaction).
 * This service owns no human lifecycle of its own: the pre-existing unfenced
 * rotate/revoke raw-refresh helpers are removed, and human access minting is a
 * package-private typed-spec minter callable only from the fenced coordinator in this
 * package. Test fixtures mint through the test helper in test sources, never through a
 * production backdoor. Machine access minting is package-private and callable only from the
 * fenced coordinator in this package (machine client-credentials path).
 */
@Service
public class AuthTokenService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final JwtEncoder jwtEncoder;
    private final SecurityProperties securityProperties;
    private final AppUserRepository appUserRepository;
    private final RefreshTokenRepository refreshTokenRepository;

    public AuthTokenService(
            JwtEncoder jwtEncoder,
            SecurityProperties securityProperties,
            AppUserRepository appUserRepository,
            RefreshTokenRepository refreshTokenRepository
    ) {
        this.jwtEncoder = jwtEncoder;
        this.securityProperties = securityProperties;
        this.appUserRepository = appUserRepository;
        this.refreshTokenRepository = refreshTokenRepository;
    }

    /**
     * The only human access minter. Package-private so only the fenced coordinator
     * in this package can mint; the immutable typed spec carries every claim value, so
     * no caller can override identity, audience, type, version or session claims with
     * arbitrary maps. Every minted human token binds a real session family id.
     */
    TokenResponse mintHumanFamilyToken(HumanFamilyTokenSpec spec) {
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plus(securityProperties.getJwt().getTtl());

        JwtClaimsSet.Builder claimsBuilder = JwtClaimsSet.builder()
                .issuer(securityProperties.getJwt().getIssuer())
                .subject(spec.username())
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .id(UUID.randomUUID().toString())
                // Explicit human audience, validated separately from any Entra audience.
                .audience(List.of(securityProperties.getJwt().getHumanAudience()))
                .claim("roles", List.copyOf(spec.roles()))
                .claim("pwdchg", spec.passwordChangeRequired())
                .claim("pwdv", spec.passwordChangedAtMillis())
                .claim("tv", spec.tokenVersion())
                // Session family binding, checked per request against the family row.
                .claim("sid", spec.familyId().toString());
        if (spec.lspId() != null) {
            claimsBuilder.claim("lspId", spec.lspId());
            claimsBuilder.claim("lspCode", spec.lspCode());
            claimsBuilder.claim("lspName", spec.lspName());
        }

        JwsHeader jwsHeader = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(jwsHeader, claimsBuilder.build())).getTokenValue();
        return new TokenResponse(
                token,
                "Bearer",
                expiresAt.getEpochSecond() - issuedAt.getEpochSecond(),
                spec.passwordChangeRequired()
        );
    }

    /**
     * Machine-token minting with the explicit machine audience. Kept separate from the
     * human path so human/machine surface separation is structural rather than trusting signed
     * roles alone.
     */
    TokenResponse mintMachineTokenResponse(
            String clientId,
            String clientName,
            String lspId,
            String lspCode,
            long lspTokenVersion,
            long apiClientTokenVersion
    ) {
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plus(securityProperties.getJwt().getTtl());

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(securityProperties.getJwt().getIssuer())
                .subject(clientId)
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .id(UUID.randomUUID().toString())
                .audience(List.of(securityProperties.getJwt().getMachineAudience()))
                .claim("roles", List.of("LSP_API_CLIENT"))
                .claim(ApiClientJwtSessionValidator.AUTH_TYPE_CLAIM,
                        ApiClientJwtSessionValidator.AUTH_TYPE_API_CLIENT)
                .claim("clientId", clientId)
                .claim("clientName", clientName)
                .claim("lspId", lspId)
                .claim("lspCode", lspCode)
                .claim(ApiClientJwtSessionValidator.TV_LSP_CLAIM, lspTokenVersion)
                .claim(ApiClientJwtSessionValidator.TV_API_CLIENT_CLAIM, apiClientTokenVersion)
                .build();

        JwsHeader jwsHeader = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(jwsHeader, claims)).getTokenValue();
        return new TokenResponse(
                token,
                "Bearer",
                expiresAt.getEpochSecond() - issuedAt.getEpochSecond(),
                false
        );
    }

    public ManagedUserState loadManagedUserState(String username) {
        // No missing/inactive-user fallback: human tokens require an active existing
        // managed principal, so minting for an unknown or inactive subject fails closed instead
        // of minting a token that later validates through a fallback. Use-time validators
        // re-check status independently; this is the mint-time gate.
        AppUser user = requireMintableUser(username);
        return new ManagedUserState(
                user.isPasswordChangeRequired(),
                user.getPasswordChangedAt(),
                user.getTokenVersion());
    }

    private AppUser requireMintableUser(String username) {
        AppUser user = appUserRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalStateException(
                        "Cannot mint a token for unknown managed user."));
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new IllegalStateException(
                    "Cannot mint a token for an inactive managed user.");
        }
        return user;
    }

    /**
     * Refresh routing probe: detached scalar classification of a presented refresh cookie,
     * executed outside any fencing transaction. Returns only ids/kind/subject — never
     * managed entities — so fencing transactions re-lock everything fresh inside.
     * Human callers route HUMAN/UNKNOWN to the fenced family service; the machine
     * (API_CLIENT) branch is fenced in the same coordinator with api_client-first locks.
     */
    @Transactional(readOnly = true)
    public RefreshSubjectProbe classifyRefreshSubject(String rawRefreshCookie) {
        if (rawRefreshCookie == null || rawRefreshCookie.isBlank()) {
            return new RefreshSubjectProbe(
                    RefreshSubjectKind.UNKNOWN, null, null, AuthAuditService.ANONYMOUS_USERNAME);
        }
        String tokenHash = sha256Hex(rawRefreshCookie.trim());
        RefreshToken row = refreshTokenRepository.findByTokenHash(tokenHash).orElse(null);
        if (row == null) {
            return new RefreshSubjectProbe(
                    RefreshSubjectKind.UNKNOWN, null, null, AuthAuditService.UNKNOWN_USERNAME);
        }
        if (row.getApiClient() != null) {
            return new RefreshSubjectProbe(
                    RefreshSubjectKind.MACHINE,
                    row.getApiClient().getId(),
                    null,
                    row.getApiClient().getClientId());
        }
        if (row.getAppUser() != null) {
            UUID familyId = row.getFamily() != null ? row.getFamily().getId() : null;
            return new RefreshSubjectProbe(
                    RefreshSubjectKind.HUMAN,
                    row.getAppUser().getId(),
                    familyId,
                    row.getAppUser().getUsername());
        }
        return new RefreshSubjectProbe(
                RefreshSubjectKind.UNKNOWN, null, null, AuthAuditService.UNKNOWN_USERNAME);
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

    private record RawRefreshToken(String value, String hash, Instant expiresAt) {
    }

    public record ManagedUserState(boolean passwordChangeRequired, Instant passwordChangedAt, long tokenVersion) {
    }

    /**
     * Immutable typed mint spec. Every human JWT claim is sourced here; there is no
     * arbitrary claim map to override identity, audience, versions or session binding.
     */
    record HumanFamilyTokenSpec(
            String username,
            List<String> roles,
            boolean passwordChangeRequired,
            long passwordChangedAtMillis,
            long tokenVersion,
            String lspId,
            String lspCode,
            String lspName,
            UUID familyId
    ) {
    }

    public record RefreshOutcome(
            boolean success,
            AuthEventFailureReason failureReason,
            String subjectUsername,
            TokenResponse tokenResponse,
            String newRawRefreshToken,
            UUID userId,
            UUID apiClientId,
            boolean failureAuditedInTx
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
                    apiClientId,
                    false
            );
        }

        public static RefreshOutcome failure(AuthEventFailureReason failureReason, String subjectUsername) {
            return new RefreshOutcome(false, failureReason, subjectUsername, null, null, null, null, false);
        }

        /**
         * Failure whose audit row was already written inside the committing fencing
         * transaction (family reuse revocation). Callers must not write a second failure
         * audit outside.
         */
        public static RefreshOutcome failureAudited(AuthEventFailureReason failureReason, String subjectUsername) {
            return new RefreshOutcome(false, failureReason, subjectUsername, null, null, null, null, true);
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

    /** Refresh routing classification for refresh cookies (detached scalars, see classifier). */
    public enum RefreshSubjectKind {
        HUMAN,
        MACHINE,
        UNKNOWN
    }

    public record RefreshSubjectProbe(
            RefreshSubjectKind kind,
            UUID principalId,
            UUID familyId,
            String subject
    ) {
    }
}
