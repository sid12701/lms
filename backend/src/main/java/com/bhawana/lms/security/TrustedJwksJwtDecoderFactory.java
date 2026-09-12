package com.bhawana.lms.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import java.time.Duration;
import org.springframework.cache.Cache;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.client.RestTemplate;

/**
 * Build an RS256 decoder against a single configured JWKS location. Token-supplied
 * {@code jku}/{@code x5u} never choose network destinations.
 */
public final class TrustedJwksJwtDecoderFactory {

    private TrustedJwksJwtDecoderFactory() {
    }

    public static JwtDecoder build(
            SecurityProperties.EntraMachineIdentity config,
            EntraMachineJwtValidator entraMachineJwtValidator
    ) {
        if (!config.isFiniteJwksTimeoutConfiguration()) {
            throw new IllegalArgumentException("JWKS requires finite positive connect/read timeouts.");
        }
        RestTemplate restOperations = jwksRestOperations(config);
        Cache cache = new ExpiringConcurrentMapCache("entra-jwks", effectiveCacheTtl(config));
        JWKSource<SecurityContext> jwkSource = buildJwkSource(config, restOperations, cache);

        ConfigurableJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, jwkSource));

        NimbusJwtDecoder decoder = new NimbusJwtDecoder(processor);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(config.getIssuer()),
                JwtSecurityBeans.requireBoundedExpiry(),
                entraMachineJwtValidator
        ));
        return decoder;
    }

    public static Duration effectiveCacheTtl(SecurityProperties.EntraMachineIdentity config) {
        return config.getJwksCacheTtl() == null ? Duration.ofMinutes(10) : config.getJwksCacheTtl();
    }

    private static JWKSource<SecurityContext> buildJwkSource(
            SecurityProperties.EntraMachineIdentity config,
            RestTemplate restOperations,
            Cache cache
    ) {
        TrustedRemoteJwkSetSource<SecurityContext> remote = new TrustedRemoteJwkSetSource<>(
                restOperations,
                cache,
                config.getJwksUri(),
                config.getUnknownKidMinInterval());
        return JWKSourceBuilder.create(remote)
                .cache(false)
                .rateLimited(false)
                .refreshAheadCache(false)
                .build();
    }

    private static RestTemplate jwksRestOperations(SecurityProperties.EntraMachineIdentity config) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Math.toIntExact(config.getConnectTimeout().toMillis()));
        factory.setReadTimeout(Math.toIntExact(config.getReadTimeout().toMillis()));
        return new RestTemplate(factory);
    }
}
