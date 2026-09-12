package com.bhawana.lms.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jwt.Jwt;

class MachinePrincipalLspResolverTest {
    @Test
    void convertedPrincipalKeepsItsOwnMappingWhenAnotherContextConvertsSameExternalToken() {
        UUID lspA = UUID.randomUUID();
        UUID lspB = UUID.randomUUID();
        Jwt external = externalToken(lspB);
        var mappingA = mapping(external, lspA, "local-client-a");
        var mappingB = mapping(external, lspB, "local-client-b");
        var beans = new JwtSecurityBeans();
        var converterA = beans.jwtAuthenticationConverter(null, null, mappingA);
        var converterB = beans.jwtAuthenticationConverter(null, null, mappingB);
        var authenticationA = converterA.convert(external);
        var authenticationB = converterB.convert(external);

        assertEquals(Optional.of(lspA), MachinePrincipalLspResolver.resolveLspId(
                (Jwt) authenticationA.getPrincipal()));
        assertEquals(Optional.of(lspB), MachinePrincipalLspResolver.resolveLspId(
                (Jwt) authenticationB.getPrincipal()));
        assertEquals("local-client-a", authenticationA.getName());
        assertEquals(List.of("ROLE_LSP_API_CLIENT"), authenticationA.getAuthorities().stream()
                .map(authority -> authority.getAuthority()).toList());
        verify(mappingA).resolve(external);
        verify(mappingB).resolve(external);
    }

    @Test
    void unconvertedExternalJwtCannotSupplyItsOwnTenantEvenWithForgedLocalClaims() {
        Jwt external = externalToken(UUID.randomUUID());
        assertTrue(MachinePrincipalLspResolver.resolveLspId(external).isEmpty());
        assertTrue(MachinePrincipalLspResolver.isEntraMachineTokenStatic(external));
    }

    @Test
    void mappingRemovedBeforeConversionFailsAuthentication() {
        Jwt external = externalToken(UUID.randomUUID());
        var mapping = mock(EntraMachineIdentityMappingService.class);
        when(mapping.isEntraMachineToken(external)).thenReturn(true);
        when(mapping.resolve(external)).thenReturn(Optional.empty());
        var converter = new JwtSecurityBeans().jwtAuthenticationConverter(null, null, mapping);
        assertThrows(OAuth2AuthenticationException.class, () -> converter.convert(external));
    }

    @Test
    void localHumanAndMachineKeepTheirMintedLspClaim() {
        UUID lsp = UUID.randomUUID();
        for (String authType : List.of("HUMAN", "API_CLIENT")) {
            Jwt local = Jwt.withTokenValue("local")
                    .header("alg", "HS256").subject("local-principal")
                    .claim("authType", authType).claim("lspId", lsp.toString()).build();
            assertEquals(Optional.of(lsp), MachinePrincipalLspResolver.resolveLspId(local));
        }
    }

    private static EntraMachineIdentityMappingService mapping(Jwt jwt, UUID lsp, String clientId) {
        var mapping = mock(EntraMachineIdentityMappingService.class);
        when(mapping.isEntraMachineToken(jwt)).thenReturn(true);
        when(mapping.resolve(jwt)).thenReturn(Optional.of(
                new EntraMachineIdentityMappingService.ResolvedEntraMachinePrincipal(
                        clientId, UUID.randomUUID(), lsp, "LSP", "Client")));
        return mapping;
    }

    private static Jwt externalToken(UUID callerSuppliedLsp) {
        Instant now = Instant.now();
        return Jwt.withTokenValue("external")
                .header("alg", "RS256").issuer("https://login.microsoftonline.com/tenant/v2.0")
                .subject("external-app").issuedAt(now).expiresAt(now.plusSeconds(60))
                .claim("idtyp", "app").claim("authType", "HUMAN")
                .claim("roles", List.of("SYSTEM_ADMIN"))
                .claim("lspId", callerSuppliedLsp.toString()).build();
    }
}
