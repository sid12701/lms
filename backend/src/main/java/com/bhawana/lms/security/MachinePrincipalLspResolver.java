package com.bhawana.lms.security;

import java.util.Optional;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;

/** Resolves LSP identity from the request's converted principal, without global Spring state. */
public final class MachinePrincipalLspResolver {

    private MachinePrincipalLspResolver() {
    }

    public static Optional<UUID> resolveLspId(Jwt jwt) {
        if (jwt instanceof VerifiedEntraMachineJwt verified) {
            return Optional.of(verified.principal().authoritativeLspId());
        }
        // External claims are never sufficient: only the injected authentication converter can
        // attach the verified local mapping. Local human/machine JWTs retain their minted lspId.
        if (looksLikeEntraMachineToken(jwt)) {
            return Optional.empty();
        }
        return parseUuidClaim(jwt.getClaimAsString("lspId"));
    }

    public static boolean isEntraMachineTokenStatic(Jwt jwt) {
        return jwt instanceof VerifiedEntraMachineJwt || looksLikeEntraMachineToken(jwt);
    }

    static boolean looksLikeEntraMachineToken(Jwt jwt) {
        Object algorithm = jwt.getHeaders().get("alg");
        if ("RS256".equals(algorithm)) {
            return true;
        }
        if (EntraMachineJwtValidator.IDTYP_APP.equals(jwt.getClaimAsString("idtyp"))) {
            return true;
        }
        String issuer = jwt.getClaimAsString("iss");
        return issuer != null && issuer.contains("login.microsoftonline.com");
    }

    static Optional<UUID> parseUuidClaim(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(raw.trim()));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }
}
