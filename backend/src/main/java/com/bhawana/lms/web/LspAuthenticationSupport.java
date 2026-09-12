package com.bhawana.lms.web;

import com.bhawana.lms.security.MachinePrincipalLspResolver;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;

final class LspAuthenticationSupport {

    private LspAuthenticationSupport() {
    }

    static UUID authenticatedLspId(Authentication authentication) {
        UUID lspId = tryAuthenticatedLspId(authentication);
        if (lspId != null) {
            return lspId;
        }
        throw new AccessDeniedException("Authenticated LSP context is missing.");
    }

    static UUID tryAuthenticatedLspId(Authentication authentication) {
        if (authentication.getPrincipal() instanceof Jwt jwt) {
            return MachinePrincipalLspResolver.resolveLspId(jwt).orElse(null);
        }
        return null;
    }
}
