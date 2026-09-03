package com.bhawana.lms.security;

import com.bhawana.lms.domain.AppUser;
import com.bhawana.lms.repo.AppUserRepository;
import com.bhawana.lms.tenant.TenantScopedExecution;
import java.util.Optional;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

@Component
public class ManagedUserJwtPrincipalResolver {

    private final AppUserRepository appUserRepository;
    private final AuthPrincipalCache authPrincipalCache;
    private final SessionValidityPolicy sessionValidityPolicy;

    public ManagedUserJwtPrincipalResolver(
            AppUserRepository appUserRepository,
            AuthPrincipalCache authPrincipalCache,
            SessionValidityPolicy sessionValidityPolicy
    ) {
        this.appUserRepository = appUserRepository;
        this.authPrincipalCache = authPrincipalCache;
        this.sessionValidityPolicy = sessionValidityPolicy;
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
        return resolve(jwt)
                .map(principal -> validateManagedUserSession(principal.jwt(), principal.snapshot()))
                .orElse(OAuth2TokenValidatorResult.success());
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
        SessionValidityPolicy.Result result = sessionValidityPolicy.validate(
                SessionValidityPolicy.SessionClaims.forManagedUser(jwt),
                SessionValidityPolicy.SubjectSnapshot.of(snapshot)
        );
        if (result.valid()) {
            return OAuth2TokenValidatorResult.success();
        }
        return sessionValidityPolicy.toOAuth2Failure(result.reason());
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
