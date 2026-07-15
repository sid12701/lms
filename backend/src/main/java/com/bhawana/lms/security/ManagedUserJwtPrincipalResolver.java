package com.bhawana.lms.security;

import com.bhawana.lms.domain.AppUser;
import com.bhawana.lms.repo.AppUserRepository;
import com.bhawana.lms.tenant.TenantScopedExecution;
import java.util.Optional;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

@Component
public class ManagedUserJwtPrincipalResolver {

    private final AppUserRepository appUserRepository;
    private final AuthPrincipalCache authPrincipalCache;

    public ManagedUserJwtPrincipalResolver(
            AppUserRepository appUserRepository,
            AuthPrincipalCache authPrincipalCache
    ) {
        this.appUserRepository = appUserRepository;
        this.authPrincipalCache = authPrincipalCache;
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
                                .map(ManagedUserJwtPrincipalResolver::toSnapshot)
                )
                .map(snapshot -> new ResolvedManagedUserPrincipal(username, snapshot, jwt)));
    }

    public OAuth2TokenValidatorResult validateSession(Jwt jwt) {
        return resolve(jwt)
                .map(ResolvedManagedUserPrincipal::validateSession)
                .orElse(OAuth2TokenValidatorResult.success());
    }

    public boolean passwordChangeRequired(Jwt jwt) {
        return resolve(jwt)
                .map(ResolvedManagedUserPrincipal::passwordChangeRequired)
                .orElse(false);
    }

    private static AuthPrincipalCache.AppUserSnapshot toSnapshot(AppUser appUser) {
        return new AuthPrincipalCache.AppUserSnapshot(
                appUser.getTokenVersion(),
                appUser.getPasswordChangedAt().toEpochMilli(),
                appUser.isPasswordChangeRequired(),
                appUser.getStatus()
        );
    }

    public record ResolvedManagedUserPrincipal(
            String username,
            AuthPrincipalCache.AppUserSnapshot snapshot,
            Jwt jwt
    ) {
        OAuth2TokenValidatorResult validateSession() {
            Long tokenPasswordVersion = jwt.getClaim("pwdv");
            if (tokenPasswordVersion == null
                    || tokenPasswordVersion.longValue() != snapshot.passwordChangedAtMillis()) {
                return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                        "invalid_token",
                        "Password has changed",
                        null
                ));
            }

            Long tokenSessionVersion = jwt.getClaim("tv");
            long currentSessionVersion = snapshot.tokenVersion();
            long effectiveTokenSessionVersion = tokenSessionVersion == null ? 0L : tokenSessionVersion.longValue();
            if (effectiveTokenSessionVersion != currentSessionVersion) {
                return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                        "invalid_token",
                        "Session is no longer valid",
                        null
                ));
            }

            return OAuth2TokenValidatorResult.success();
        }

        boolean passwordChangeRequired() {
            return snapshot.passwordChangeRequired();
        }
    }
}
