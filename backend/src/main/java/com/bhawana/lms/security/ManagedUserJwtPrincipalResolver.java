package com.bhawana.lms.security;

import com.bhawana.lms.domain.AppUser;
import com.bhawana.lms.repo.AppUserRepository;
import com.bhawana.lms.repo.AuthSessionRepository;
import com.bhawana.lms.tenant.TenantScopedExecution;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Resolves human JWTs against the managed-user store with no missing-user fallback.
 *
 * <p>A human token (absent or {@code HUMAN} auth type) authenticates only when its subject
 * resolves to an existing managed principal whose session claims validate (active status,
 * password/token versions). Missing subjects, deleted users and unrecognized token types fail
 * closed here; API-client tokens defer to {@link ApiClientJwtSessionValidator} on its own branch.
 *
 * <p>Principal snapshots are cached for {@link AuthPrincipalCache#TTL_MILLIS} (30s): revocation
 * (delete/disable/version bump) is therefore effective within 30 seconds per instance, sooner
 * when the mutation path evicts explicitly (see SessionRevocationService, UserAdminService).
 * A deleted subject's token can never authenticate past that bound, and the explicit bootstrap
 * sync route re-checks actor liveness instead of trusting a cached snapshot.
 *
 * <p>Human access JWTs additionally carry {@code sid} (session family id). The sid is
 * checked by direct family revocation read on every request (immediate family-kill effect),
 * orthogonal to the cached tv/pwdv/status gates above. Legacy sid-less tokens fail closed
 * (forced reauth); a sid bound to another subject or to a revoked/unknown family fails
 * closed as well.
 */
@Component
public class ManagedUserJwtPrincipalResolver {

    /**
     * Explicit human token type. Minted human tokens carry no auth-type claim (the documented
     * human claim shape), so absent auth type also routes here; this value is accepted for
     * forward compatibility. Anything else except the API-client type is rejected.
     */
    public static final String AUTH_TYPE_HUMAN = "HUMAN";

    private final AppUserRepository appUserRepository;
    private final AuthPrincipalCache authPrincipalCache;
    private final SessionValidityPolicy sessionValidityPolicy;
    private final AuthSessionRepository authSessionRepository;

    public ManagedUserJwtPrincipalResolver(
            AppUserRepository appUserRepository,
            AuthPrincipalCache authPrincipalCache,
            SessionValidityPolicy sessionValidityPolicy,
            AuthSessionRepository authSessionRepository
    ) {
        this.appUserRepository = appUserRepository;
        this.authPrincipalCache = authPrincipalCache;
        this.sessionValidityPolicy = sessionValidityPolicy;
        this.authSessionRepository = authSessionRepository;
    }

    public Optional<ResolvedManagedUserPrincipal> resolve(Jwt jwt) {
        if (ApiClientJwtSessionValidator.AUTH_TYPE_API_CLIENT.equals(
                jwt.getClaimAsString(ApiClientJwtSessionValidator.AUTH_TYPE_CLAIM)
        )) {
            return Optional.empty();
        }

        String username = jwt.getSubject();
        if (username == null || username.isBlank()) {
            return Optional.empty();
        }

        return TenantScopedExecution.callAsAdmin(() -> authPrincipalCache.getAppUser(username, () ->
                        appUserRepository.findByUsername(username)
                                .map(SessionValidityPolicy::appUserSnapshot)
                )
                .map(snapshot -> new ResolvedManagedUserPrincipal(username, snapshot, jwt)));
    }

    public OAuth2TokenValidatorResult validateSession(Jwt jwt) {
        String authType = jwt.getClaimAsString(ApiClientJwtSessionValidator.AUTH_TYPE_CLAIM);
        if (ApiClientJwtSessionValidator.AUTH_TYPE_API_CLIENT.equals(authType)) {
            // Machine branch: owned by ApiClientJwtSessionValidator in the same validator chain.
            return OAuth2TokenValidatorResult.success();
        }
        if (authType != null && !AUTH_TYPE_HUMAN.equals(authType)) {
            return failure("invalid_token", "Unrecognized token type.");
        }
        return resolve(jwt)
                .map(principal -> validateManagedUserSession(principal.jwt(), principal.snapshot()))
                .orElseGet(() -> sessionValidityPolicy.toOAuth2Failure(
                        SessionValidityPolicy.InvalidReason.SUBJECT_MISSING));
    }

    public boolean passwordChangeRequired(Jwt jwt) {
        return resolve(jwt)
                .map(ResolvedManagedUserPrincipal::passwordChangeRequired)
                .orElse(false);
    }

    private OAuth2TokenValidatorResult validateManagedUserSession(
            Jwt jwt,
            AuthPrincipalCache.AppUserSnapshot snapshot
    ) {
        // The sid binds the bearer to its session family. Legacy sid-less tokens force
        // login; a sid that is unknown, revoked, or owned by another subject fails closed.
        // Direct family read (no principal-cache lag) so family logout/reuse takes effect
        // on the next per-request check; tv/pwdv/status gates below keep their documented
        // bounded-stale semantics.
        OAuth2TokenValidatorResult sidResult = validateSessionBinding(jwt);
        if (sidResult.hasErrors()) {
            return sidResult;
        }
        SessionValidityPolicy.Result result = sessionValidityPolicy.validate(
                SessionValidityPolicy.SessionClaims.forManagedUser(jwt),
                SessionValidityPolicy.SubjectSnapshot.of(snapshot)
        );
        if (result.valid()) {
            return OAuth2TokenValidatorResult.success();
        }
        return sessionValidityPolicy.toOAuth2Failure(result.reason());
    }

    private OAuth2TokenValidatorResult validateSessionBinding(Jwt jwt) {
        String sid = jwt.getClaimAsString("sid");
        if (sid == null || sid.isBlank()) {
            return failure("invalid_token", "Session is no longer valid.");
        }
        UUID familyId;
        try {
            familyId = UUID.fromString(sid.trim());
        } catch (IllegalArgumentException exception) {
            return failure("invalid_token", "Session is no longer valid.");
        }
        String username = jwt.getSubject();
        return TenantScopedExecution.callAsAdmin(() -> {
            var session = authSessionRepository.findById(familyId).orElse(null);
            if (session == null || session.isRevoked()) {
                return failure("invalid_token", "Session is no longer valid.");
            }
            var user = appUserRepository.findByUsername(username).orElse(null);
            if (user == null || !user.getId().equals(session.getUserId())) {
                return failure("invalid_token", "Session is no longer valid.");
            }
            return OAuth2TokenValidatorResult.success();
        });
    }

    private static OAuth2TokenValidatorResult failure(String code, String description) {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error(code, description, null));
    }

    public record ResolvedManagedUserPrincipal(
            String username,
            AuthPrincipalCache.AppUserSnapshot snapshot,
            Jwt jwt
    ) {
        boolean passwordChangeRequired() {
            return snapshot.passwordChangeRequired();
        }
    }
}
