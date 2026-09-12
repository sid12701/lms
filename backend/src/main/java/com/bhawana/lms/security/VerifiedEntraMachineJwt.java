package com.bhawana.lms.security;

import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Request-local proof attached by the authentication converter after signature/policy validation
 * and an active local mapping lookup. External claims cannot construct this type.
 */
final class VerifiedEntraMachineJwt extends Jwt {
    private final EntraMachineIdentityMappingService.ResolvedEntraMachinePrincipal principal;

    VerifiedEntraMachineJwt(
            Jwt jwt,
            EntraMachineIdentityMappingService.ResolvedEntraMachinePrincipal principal
    ) {
        super(jwt.getTokenValue(), jwt.getIssuedAt(), jwt.getExpiresAt(), jwt.getHeaders(), jwt.getClaims());
        this.principal = principal;
    }

    EntraMachineIdentityMappingService.ResolvedEntraMachinePrincipal principal() {
        return principal;
    }
}
