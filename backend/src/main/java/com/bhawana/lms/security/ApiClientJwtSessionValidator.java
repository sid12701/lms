package com.bhawana.lms.security;

import com.bhawana.lms.domain.ApiClient;
import com.bhawana.lms.repo.ApiClientRepository;
import com.bhawana.lms.tenant.TenantScopedExecution;
import java.util.Optional;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Validates LSP API client JWTs on every request using {@code tvLsp} and
 * {@code tvApiClient} claims (#63).
 */
@Component
public class ApiClientJwtSessionValidator implements OAuth2TokenValidator<Jwt> {

    public static final String AUTH_TYPE_CLAIM = "authType";
    public static final String AUTH_TYPE_API_CLIENT = "API_CLIENT";
    public static final String TV_LSP_CLAIM = "tvLsp";
    public static final String TV_API_CLIENT_CLAIM = "tvApiClient";

    private final ApiClientRepository apiClientRepository;
    private final AuthPrincipalCache authPrincipalCache;
    private final SessionValidityPolicy sessionValidityPolicy;

    public ApiClientJwtSessionValidator(
            ApiClientRepository apiClientRepository,
            AuthPrincipalCache authPrincipalCache,
            SessionValidityPolicy sessionValidityPolicy
    ) {
        this.apiClientRepository = apiClientRepository;
        this.authPrincipalCache = authPrincipalCache;
        this.sessionValidityPolicy = sessionValidityPolicy;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        if (!AUTH_TYPE_API_CLIENT.equals(jwt.getClaimAsString(AUTH_TYPE_CLAIM))) {
            return OAuth2TokenValidatorResult.success();
        }

        String clientId = jwt.getSubject();
        if (clientId == null || clientId.isBlank()) {
            return failure("API_CLIENT_TOKEN_REVOKED", "API client subject is missing.");
        }

        return TenantScopedExecution.callAsAdmin(() -> {
            Optional<AuthPrincipalCache.ApiClientSnapshot> snapshotOptional = authPrincipalCache.getApiClient(
                    clientId,
                    () -> apiClientRepository.findByClientId(clientId.trim()).map(SessionValidityPolicy::apiClientSnapshot)
            );
            if (snapshotOptional.isEmpty()) {
                return failure("API_CLIENT_TOKEN_REVOKED", "API client no longer exists.");
            }

            SessionValidityPolicy.Result result = sessionValidityPolicy.validate(
                    SessionValidityPolicy.SessionClaims.forApiClient(jwt),
                    SessionValidityPolicy.SubjectSnapshot.of(snapshotOptional.get())
            );
            if (result.valid()) {
                return OAuth2TokenValidatorResult.success();
            }
            return sessionValidityPolicy.toOAuth2Failure(result.reason());
        });
    }

    private static OAuth2TokenValidatorResult failure(String code, String description) {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error(code, description, null));
    }
}
