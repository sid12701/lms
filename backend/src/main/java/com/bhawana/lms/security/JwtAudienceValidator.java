package com.bhawana.lms.security;

import java.util.List;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Audience separation for locally minted tokens.
 *
 * <p>Human tokens must carry the configured human audience; machine (API-client) tokens must
 * carry the configured machine audience. Each validator owns exactly one branch and skips the
 * other, so human and machine tokens cannot cross surfaces even while both share the local
 * issuer, HS256 algorithm and signing key. Tokens minted before audiences existed carry no
 * audience and fail on their owning branch: there is deliberately no compatibility window —
 * rotation (secret or audience) forces reauthentication, documented on
 * {@link SecurityProperties.Jwt} and {@link JwtSecurityBeans}.
 *
 * <p>Audiences are validated separately from any future Entra audience. No promise is made
 * about which audience strings an external issuer may carry: trust separation for any
 * Entra-issued token must derive from issuer/algorithm/mapping validation, never from an
 * assumption that externally chosen audiences avoid these local values. These validators only
 * establish which locally minted branch a local-issuer token belongs to.
 */
public final class JwtAudienceValidator {

    private JwtAudienceValidator() {
    }

    public static OAuth2TokenValidatorResult validateHumanAudience(Jwt jwt, String humanAudience) {
        if (isApiClientToken(jwt)) {
            return OAuth2TokenValidatorResult.success();
        }
        if (audienceMatches(jwt, humanAudience)) {
            return OAuth2TokenValidatorResult.success();
        }
        return failure("invalid_token", "Token audience is not valid for human tokens.");
    }

    public static OAuth2TokenValidatorResult validateMachineAudience(Jwt jwt, String machineAudience) {
        if (!isApiClientToken(jwt)) {
            return OAuth2TokenValidatorResult.success();
        }
        if (audienceMatches(jwt, machineAudience)) {
            return OAuth2TokenValidatorResult.success();
        }
        return failure("invalid_token", "Token audience is not valid for machine tokens.");
    }

    /**
     * Rejects tokens carrying both configured local audiences: the human and machine audiences
     * denote distinct intended recipients, so a token addressed to both is inconsistent and must
     * not verify on either branch. When the two audiences are configured equal (itself a
     * fail-closed configuration error, see {@link SecurityProperties.Jwt}) this check stays
     * silent so a single misconfiguration does not mask itself as token failures.
     */
    public static OAuth2TokenValidatorResult validateNoConflictingLocalAudiences(
            Jwt jwt, String humanAudience, String machineAudience) {
        if (humanAudience == null || machineAudience == null || humanAudience.equals(machineAudience)) {
            return OAuth2TokenValidatorResult.success();
        }
        List<String> audience = jwt.getAudience();
        if (audience != null && audience.contains(humanAudience) && audience.contains(machineAudience)) {
            return failure("invalid_token", "Token must not carry both human and machine audiences.");
        }
        return OAuth2TokenValidatorResult.success();
    }

    static boolean isApiClientToken(Jwt jwt) {
        return ApiClientJwtSessionValidator.AUTH_TYPE_API_CLIENT.equals(
                jwt.getClaimAsString(ApiClientJwtSessionValidator.AUTH_TYPE_CLAIM));
    }

    private static boolean audienceMatches(Jwt jwt, String expectedAudience) {
        if (expectedAudience == null || expectedAudience.isBlank()) {
            return false;
        }
        List<String> audience = jwt.getAudience();
        return audience != null && audience.contains(expectedAudience);
    }

    private static OAuth2TokenValidatorResult failure(String code, String description) {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error(code, description, null));
    }
}
