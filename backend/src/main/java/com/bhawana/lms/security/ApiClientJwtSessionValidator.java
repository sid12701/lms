package com.bhawana.lms.security;

import com.bhawana.lms.domain.ApiClient;
import com.bhawana.lms.domain.ApiClientStatus;
import com.bhawana.lms.domain.Lsp;
import com.bhawana.lms.domain.LspStatus;
import com.bhawana.lms.repo.ApiClientRepository;
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

    public ApiClientJwtSessionValidator(ApiClientRepository apiClientRepository) {
        this.apiClientRepository = apiClientRepository;
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

        Optional<ApiClient> apiClientOptional = apiClientRepository.findByClientId(clientId.trim());
        if (apiClientOptional.isEmpty()) {
            return failure("API_CLIENT_TOKEN_REVOKED", "API client no longer exists.");
        }

        ApiClient apiClient = apiClientOptional.get();
        Lsp lsp = apiClient.getLsp();

        long tokenLspVersion = longClaim(jwt, TV_LSP_CLAIM);
        if (tokenLspVersion != lsp.getTokenVersion()) {
            return failure("LSP_TOKEN_REVOKED", "LSP session is no longer valid.");
        }

        long tokenClientVersion = longClaim(jwt, TV_API_CLIENT_CLAIM);
        if (tokenClientVersion != apiClient.getTokenVersion()) {
            return failure("API_CLIENT_TOKEN_REVOKED", "API client session is no longer valid.");
        }

        if (lsp.getStatus() != LspStatus.ACTIVE) {
            return failure("LSP_INACTIVE", "LSP is not active.");
        }

        if (apiClient.getStatus() != ApiClientStatus.ACTIVE) {
            return failure("API_CLIENT_INACTIVE", "API client is not active.");
        }

        return OAuth2TokenValidatorResult.success();
    }

    private static long longClaim(Jwt jwt, String claimName) {
        Long claim = jwt.getClaim(claimName);
        return claim == null ? 0L : claim.longValue();
    }

    private static OAuth2TokenValidatorResult failure(String code, String description) {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error(code, description, null));
    }
}
