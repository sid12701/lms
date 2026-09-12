package com.bhawana.lms.security;

import java.util.List;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Post-signature Entra v2 app-only policy — trusted tenant/issuer/audience, {@code idtyp=app},
 * no delegated {@code scp}, required app role and a verified local mapping. Skips local HS256 tokens.
 */
@Component
public class EntraMachineJwtValidator implements OAuth2TokenValidator<Jwt> {

    public static final String IDTYP_APP = "app";

    private final SecurityProperties securityProperties;
    private final EntraMachineIdentityMappingService mappingService;

    public EntraMachineJwtValidator(
            SecurityProperties securityProperties,
            EntraMachineIdentityMappingService mappingService
    ) {
        this.securityProperties = securityProperties;
        this.mappingService = mappingService;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        if (!securityProperties.getEntraMachineIdentity().isEnabled()) {
            return OAuth2TokenValidatorResult.success();
        }
        if (!mappingService.isEntraMachineToken(jwt)) {
            return OAuth2TokenValidatorResult.success();
        }
        SecurityProperties.EntraMachineIdentity config = securityProperties.getEntraMachineIdentity();
        String tenantId = jwt.getClaimAsString("tid");
        if (tenantId == null || !config.getTrustedTenantId().equalsIgnoreCase(tenantId.trim())) {
            return failure("invalid_token", "Untrusted Entra tenant.");
        }
        String issuer = jwt.getClaimAsString("iss");
        if (!config.getIssuer().equals(issuer)) {
            return failure("invalid_token", "Untrusted Entra issuer.");
        }
        List<String> audience = jwt.getAudience();
        if (audience == null || !audience.contains(config.getApiAudience())) {
            return failure("invalid_token", "Entra token audience is not valid for this API.");
        }
        String idtyp = jwt.getClaimAsString("idtyp");
        if (!IDTYP_APP.equals(idtyp)) {
            return failure("invalid_token", "Entra token must carry app-only identity (idtyp=app).");
        }
        String scp = jwt.getClaimAsString("scp");
        if (scp != null && !scp.isBlank()) {
            return failure("invalid_token", "Delegated Entra tokens (scp) are not accepted.");
        }
        List<String> roles = jwt.getClaimAsStringList("roles");
        if (roles == null || !roles.contains(config.getRequiredAppRole())) {
            return failure("invalid_token", "Required Entra app role is missing.");
        }
        if (mappingService.resolve(jwt).isEmpty()) {
            return failure("invalid_token", "Entra app is not mapped to an enabled local API client.");
        }
        return OAuth2TokenValidatorResult.success();
    }

    private static OAuth2TokenValidatorResult failure(String code, String description) {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error(code, description, null));
    }
}
