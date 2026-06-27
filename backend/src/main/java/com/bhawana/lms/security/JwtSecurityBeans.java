package com.bhawana.lms.security;

import com.bhawana.lms.domain.AppUser;
import com.bhawana.lms.repo.AppUserRepository;
import com.bhawana.lms.tenant.TenantScopedExecution;
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
            AppUserRepository appUserRepository,
            ApiClientJwtSessionValidator apiClientJwtSessionValidator,
            SecurityProperties securityProperties
    ) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(jwtSigningKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(securityProperties.getJwt().getIssuer()),
                managedUserSessionValidator(appUserRepository),
                apiClientJwtSessionValidator
        ));
        return decoder;
    }

    @Bean
    Converter<Jwt, ? extends AbstractAuthenticationToken> jwtAuthenticationConverter(AppUserRepository appUserRepository) {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(grantedAuthoritiesConverter(appUserRepository));
        return converter;
    }

    private OAuth2TokenValidator<Jwt> managedUserSessionValidator(AppUserRepository appUserRepository) {
        return jwt -> {
            if (ApiClientJwtSessionValidator.AUTH_TYPE_API_CLIENT.equals(
                    jwt.getClaimAsString(ApiClientJwtSessionValidator.AUTH_TYPE_CLAIM)
            )) {
                return OAuth2TokenValidatorResult.success();
            }

            String username = jwt.getSubject();
            if (username == null || username.isBlank()) {
                return OAuth2TokenValidatorResult.success();
            }

            return TenantScopedExecution.callAsAdmin(() -> appUserRepository.findByUsername(username)
                    .map(appUser -> {
                        Long tokenPasswordVersion = jwt.getClaim("pwdv");
                        long currentPasswordVersion = appUser.getPasswordChangedAt().toEpochMilli();
                        if (tokenPasswordVersion == null || tokenPasswordVersion.longValue() != currentPasswordVersion) {
                            return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                                    "invalid_token",
                                    "Password has changed",
                                    null
                            ));
                        }

                        Long tokenSessionVersion = jwt.getClaim("tv");
                        long currentSessionVersion = appUser.getTokenVersion();
                        long effectiveTokenSessionVersion = tokenSessionVersion == null ? 0L : tokenSessionVersion.longValue();
                        if (effectiveTokenSessionVersion != currentSessionVersion) {
                            return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                                    "invalid_token",
                                    "Session is no longer valid",
                                    null
                            ));
                        }

                        return OAuth2TokenValidatorResult.success();
                    })
                    .orElseGet(OAuth2TokenValidatorResult::success));
        };
    }

    private Converter<Jwt, Collection<GrantedAuthority>> grantedAuthoritiesConverter(AppUserRepository appUserRepository) {
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

            TenantScopedExecution.runAsAdmin(() -> appUserRepository.findByUsername(jwt.getSubject())
                    .filter(AppUser::isPasswordChangeRequired)
                    .ifPresent(appUser -> authorities.add(new SimpleGrantedAuthority("ROLE_PASSWORD_CHANGE_REQUIRED"))));

            return authorities;
        };
    }
}
