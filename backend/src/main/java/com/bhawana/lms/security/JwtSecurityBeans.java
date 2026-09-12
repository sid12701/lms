package com.bhawana.lms.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * JWT signing/verification beans: the HMAC key, encoder/decoder, the resource-server
 * authentication converter, and the session validators that gate token acceptance (issuer,
 * audience, managed-user password/session versioning and API-client session checks).
 *
 * <p>Rollout contract — deliberately no key ring and no audience compatibility window:
 * <ul>
 *   <li>Only HS256 with the configured secret verifies; unexpected algorithms never verify
 *       (the decoder is pinned to HS256) and keys are never fetched from JWT-supplied URLs
 *       (secret comes from the deployment secret store via {@code APP_SECURITY_JWT_SECRET}).</li>
 *   <li>Replacing the signing secret (A to B) invalidates outstanding local access tokens;
 *       changing an audience rejects tokens carrying that obsolete audience. Clients
 *       re-authenticate (humans via password login, machines via client-credentials).
 *       There is no overlap in which A-signed or audience-less tokens still verify.</li>
 *   <li>Every human refresh family is bound to the signing-policy epoch. Replacing the
 *       signing secret, issuer or audience rejects old opaque refresh credentials and requires
 *       fresh password authentication. Machine refresh credentials are retired.</li>
 *   <li>Human and machine tokens share the local issuer but never an audience; validators
 *       enforce the separation per branch rather than trusting signed roles alone.</li>
 * </ul>
 */
@Configuration
public class JwtSecurityBeans {

    /**
     * Public so a coherent rotation test can wire a complete B policy stack (key +
     * encoder + decoder + epoch provider) through the actual production factory methods
     * instead of hand-rolled crypto.
     */
    @Bean
    public SecretKey jwtSigningKey(SecurityProperties securityProperties) {
        byte[] keyBytes = securityProperties.getJwt().getSecret().getBytes(StandardCharsets.UTF_8);
        return new SecretKeySpec(keyBytes, "HmacSHA256");
    }

    @Bean
    public JwtEncoder jwtEncoder(SecretKey jwtSigningKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(jwtSigningKey));
    }

    @Bean
    JwtDecoder jwtDecoder(
            SecretKey jwtSigningKey,
            ManagedUserJwtPrincipalResolver managedUserJwtPrincipalResolver,
            ApiClientJwtSessionValidator apiClientJwtSessionValidator,
            EntraMachineJwtValidator entraMachineJwtValidator,
            SecurityProperties securityProperties
    ) {
        JwtDecoder localDecoder = buildDecoder(
                jwtSigningKey,
                securityProperties,
                managedUserJwtPrincipalResolver,
                apiClientJwtSessionValidator,
                entraMachineJwtValidator);
        if (!securityProperties.getEntraMachineIdentity().isEnabled()) {
            return localDecoder;
        }
        JwtDecoder entraDecoder = TrustedJwksJwtDecoderFactory.build(
                securityProperties.getEntraMachineIdentity(),
                entraMachineJwtValidator);
        return new RoutingJwtDecoder(localDecoder, entraDecoder, securityProperties);
    }

    /**
     * The single factory for the production decoder chain. Tests that need a second chain
     * around another key (for example secret-rotation A/B checks) must call this factory rather
     * than rebuilding the validator list, so the A/B chains cannot drift from production and
     * cannot keep passing if a production validator is removed.
     */
    public static JwtDecoder buildDecoder(
            SecretKey jwtSigningKey,
            SecurityProperties securityProperties,
            ManagedUserJwtPrincipalResolver managedUserJwtPrincipalResolver,
            ApiClientJwtSessionValidator apiClientJwtSessionValidator,
            EntraMachineJwtValidator entraMachineJwtValidator
    ) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(jwtSigningKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(securityProperties.getJwt().getIssuer()),
                requireBoundedExpiry(),
                conflictingLocalAudiencesValidator(securityProperties),
                humanAudienceValidator(securityProperties),
                machineAudienceValidator(securityProperties),
                managedUserJwtPrincipalResolver::validateSession,
                apiClientJwtSessionValidator,
                entraMachineJwtValidator
        ));
        return decoder;
    }

    @Bean
    Converter<Jwt, ? extends AbstractAuthenticationToken> jwtAuthenticationConverter(
            ManagedUserJwtPrincipalResolver managedUserJwtPrincipalResolver,
            ApiClientJwtSessionValidator apiClientJwtSessionValidator,
            EntraMachineIdentityMappingService entraMachineIdentityMappingService
    ) {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(grantedAuthoritiesConverter(
                managedUserJwtPrincipalResolver,
                apiClientJwtSessionValidator));
        return jwt -> {
            if (entraMachineIdentityMappingService.isEntraMachineToken(jwt)) {
                var principal = entraMachineIdentityMappingService.resolve(jwt).orElseThrow(() ->
                        new OAuth2AuthenticationException(new OAuth2Error(
                                "invalid_token", "Entra app mapping is no longer valid.", null)));
                return new JwtAuthenticationToken(
                        new VerifiedEntraMachineJwt(jwt, principal),
                        List.of(new SimpleGrantedAuthority("ROLE_LSP_API_CLIENT")),
                        principal.localClientId());
            }
            return converter.convert(jwt);
        };
    }

    private static OAuth2TokenValidator<Jwt> humanAudienceValidator(SecurityProperties securityProperties) {
        return jwt -> JwtAudienceValidator.validateHumanAudience(
                jwt, securityProperties.getJwt().getHumanAudience());
    }

    private static OAuth2TokenValidator<Jwt> machineAudienceValidator(SecurityProperties securityProperties) {
        return jwt -> JwtAudienceValidator.validateMachineAudience(
                jwt, securityProperties.getJwt().getMachineAudience());
    }

    private static OAuth2TokenValidator<Jwt> conflictingLocalAudiencesValidator(
            SecurityProperties securityProperties
    ) {
        return jwt -> JwtAudienceValidator.validateNoConflictingLocalAudiences(
                jwt,
                securityProperties.getJwt().getHumanAudience(),
                securityProperties.getJwt().getMachineAudience());
    }

    /**
     * Local access-token contract: every locally minted access token carries a bounded expiry.
     * The default timestamp validator only checks expiry/not-before when those claims are
     * present, so a signed token without {@code exp} would otherwise verify indefinitely. This
     * validator requires {@code exp} to be present; the allowed clock skew and all other
     * timestamp behavior stay on the untouched default validator. Intended-audience checks are
     * not expanded: this gates only the local access-token expiry contract.
     */
    static OAuth2TokenValidator<Jwt> requireBoundedExpiry() {
        return jwt -> jwt.getExpiresAt() == null
                ? OAuth2TokenValidatorResult.failure(
                        new OAuth2Error("invalid_token", "Access token must carry an expiry.", null))
                : OAuth2TokenValidatorResult.success();
    }

    /**
     * Authority mapping gates on a resolvable principal per branch instead of trusting
     * signed roles alone. The decoder chain above owns liveness (status/version checks reject
     * stale tokens there); this converter only ensures no path that converts without the full
     * chain can mint authorities for an unresolvable subject: machine tokens grant exactly the
     * machine role only when the client session still validates, and human tokens grant their
     * signed roles only when the subject still resolves to a managed principal. Human tokens
     * additionally never receive {@code ROLE_LSP_API_CLIENT}: that typed exclusion (see
     * {@link HumanMachineSurfaceGuard}) keeps even pre-existing managed rows storing the machine
     * role off the machine API.
     */
    private Converter<Jwt, Collection<GrantedAuthority>> grantedAuthoritiesConverter(
            ManagedUserJwtPrincipalResolver managedUserJwtPrincipalResolver,
            ApiClientJwtSessionValidator apiClientJwtSessionValidator
    ) {
        return jwt -> {
            if (JwtAudienceValidator.isApiClientToken(jwt)) {
                if (apiClientJwtSessionValidator.validate(jwt).hasErrors()) {
                    return List.of();
                }
                return List.of(new SimpleGrantedAuthority("ROLE_LSP_API_CLIENT"));
            }

            if (managedUserJwtPrincipalResolver.resolve(jwt).isEmpty()) {
                return List.of();
            }
            List<GrantedAuthority> authorities = new ArrayList<>();
            List<String> roles = jwt.getClaimAsStringList("roles");
            if (roles != null) {
                authorities.addAll(roles.stream()
                        .map(role -> role.startsWith("ROLE_") ? role : "ROLE_" + role)
                        .filter(authority -> !"ROLE_LSP_API_CLIENT".equals(authority))
                        .map(SimpleGrantedAuthority::new)
                        .map(GrantedAuthority.class::cast)
                        .toList());
            }

            if (managedUserJwtPrincipalResolver.passwordChangeRequired(jwt)) {
                authorities.add(new SimpleGrantedAuthority("ROLE_PASSWORD_CHANGE_REQUIRED"));
            }

            return authorities;
        };
    }
}
