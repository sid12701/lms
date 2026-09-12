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
    private final EntraMachineIdentityMappingService entraMappingService;

    public ApiClientJwtSessionValidator(
            ApiClientRepository apiClientRepository,
            AuthPrincipalCache authPrincipalCache,
            SessionValidityPolicy sessionValidityPolicy,
            EntraMachineIdentityMappingService entraMappingService
    ) {
        this.apiClientRepository = apiClientRepository;
        this.authPrincipalCache = authPrincipalCache;
        this.sessionValidityPolicy = sessionValidityPolicy;
        this.entraMappingService = entraMappingService;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        if (entraMappingService.isEntraMachineToken(jwt)) {
            return entraMappingService.resolve(jwt)
                    .map(resolved -> OAuth2TokenValidatorResult.success())
                    .orElseGet(() -> failure("API_CLIENT_TOKEN_REVOKED", "Entra app mapping is not valid."));
        }
        String authType = jwt.getClaimAsString(AUTH_TYPE_CLAIM);
        if (authType == null || ManagedUserJwtPrincipalResolver.AUTH_TYPE_HUMAN.equals(authType)) {
            // Human branch: owned by ManagedUserJwtPrincipalResolver in the same validator chain.
            return OAuth2TokenValidatorResult.success();
        }
        if (!AUTH_TYPE_API_CLIENT.equals(authType)) {
            return failure("invalid_token", "Unrecognized token type.");
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
