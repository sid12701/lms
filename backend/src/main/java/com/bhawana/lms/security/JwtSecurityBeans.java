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
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

/**
 * JWT signing/verification beans: the HMAC key, encoder/decoder, the resource-server
 * authentication converter, and the session validators that gate token acceptance (managed-user
 * password/session versioning and API-client session checks).
 */
@Configuration
public class JwtSecurityBeans {

    @Bean
    SecretKey jwtSigningKey(SecurityProperties securityProperties) {
        byte[] keyBytes = securityProperties.getJwt().getSecret().getBytes(StandardCharsets.UTF_8);
        return new SecretKeySpec(keyBytes, "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey jwtSigningKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(jwtSigningKey));
    }

    @Bean
    JwtDecoder jwtDecoder(
            SecretKey jwtSigningKey,
            ManagedUserJwtPrincipalResolver managedUserJwtPrincipalResolver,
            ApiClientJwtSessionValidator apiClientJwtSessionValidator,
            SecurityProperties securityProperties
    ) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(jwtSigningKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(securityProperties.getJwt().getIssuer()),
                managedUserSessionValidator(managedUserJwtPrincipalResolver),
                apiClientJwtSessionValidator
        ));
        return decoder;
    }

    @Bean
    Converter<Jwt, ? extends AbstractAuthenticationToken> jwtAuthenticationConverter(
            ManagedUserJwtPrincipalResolver managedUserJwtPrincipalResolver
    ) {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(grantedAuthoritiesConverter(managedUserJwtPrincipalResolver));
        return converter;
    }

    private OAuth2TokenValidator<Jwt> managedUserSessionValidator(
            ManagedUserJwtPrincipalResolver managedUserJwtPrincipalResolver
    ) {
        return managedUserJwtPrincipalResolver::validateSession;
    }

    private Converter<Jwt, Collection<GrantedAuthority>> grantedAuthoritiesConverter(
            ManagedUserJwtPrincipalResolver managedUserJwtPrincipalResolver
    ) {
        return jwt -> {
            List<GrantedAuthority> authorities = new ArrayList<>();
            List<String> roles = jwt.getClaimAsStringList("roles");
            if (roles != null) {
                authorities.addAll(roles.stream()
                        .map(role -> role.startsWith("ROLE_") ? role : "ROLE_" + role)
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
