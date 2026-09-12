package com.bhawana.lms.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import jakarta.validation.Validation;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.JwtException;

class TrustedJwksTransportTest {
    private static final String ISSUER = "https://trusted.example/tenant/v2.0";

    @Test
    void enabledConfigurationRejectsInfiniteOrUnrepresentableTimeouts() {
        var config = config("http://127.0.0.1/jwks");
        try (var validators = Validation.buildDefaultValidatorFactory()) {
            for (Duration invalid : new Duration[] {null, Duration.ZERO, Duration.ofNanos(1),
                    Duration.ofMillis(-1), Duration.ofMillis((long) Integer.MAX_VALUE + 1)}) {
                config.setConnectTimeout(invalid);
                assertFalse(validators.getValidator().validate(config).isEmpty());
                assertThrows(IllegalArgumentException.class,
                        () -> TrustedJwksJwtDecoderFactory.build(config, validator()));
                config.setConnectTimeout(Duration.ofMillis(200));
                config.setReadTimeout(invalid);
                assertFalse(validators.getValidator().validate(config).isEmpty());
                assertThrows(IllegalArgumentException.class,
                        () -> TrustedJwksJwtDecoderFactory.build(config, validator()));
                config.setReadTimeout(Duration.ofMillis(200));
            }
            assertTrue(validators.getValidator().validate(config).isEmpty());
        }
    }

    @Test
    void stalledHttpResponseTimesOutAndQueuedDecodesDoNotRefetch() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var hits = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/jwks", exchange -> {
            hits.incrementAndGet();
            entered.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        var executor = Executors.newFixedThreadPool(2);
        try {
            var decoder = TrustedJwksJwtDecoderFactory.build(config(uri(server)), validator());
            String token = signedToken();
            var first = executor.submit(() -> assertThrows(JwtException.class, () -> decoder.decode(token)));
            assertTrue(entered.await(2, TimeUnit.SECONDS), "real HTTP exchange must start");
            var queued = executor.submit(() -> assertThrows(JwtException.class, () -> decoder.decode(token)));
            first.get(2, TimeUnit.SECONDS);
            queued.get(2, TimeUnit.SECONDS);
            assertEquals(1, hits.get());
        } finally {
            release.countDown();
            executor.shutdownNow();
            server.stop(0);
        }
    }

    @Test
    void repeatedHttpOutageConsumesOneFetchBudget() throws Exception {
        var hits = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/jwks", exchange -> {
            hits.incrementAndGet();
            byte[] body = "unavailable".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(503, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        try {
            var decoder = TrustedJwksJwtDecoderFactory.build(config(uri(server)), validator());
            String token = signedToken();
            for (int attempt = 0; attempt < 5; attempt++) {
                assertThrows(JwtException.class, () -> decoder.decode(token));
            }
            assertEquals(1, hits.get(), "outage retries must respect the refresh interval");
        } finally {
            server.stop(0);
        }
    }

    private static SecurityProperties.EntraMachineIdentity config(String uri) {
        var config = new SecurityProperties.EntraMachineIdentity();
        config.setEnabled(true);
        config.setTrustedTenantId("tenant");
        config.setIssuer(ISSUER);
        config.setApiAudience("lms");
        config.setRequiredAppRole("access");
        config.setJwksUri(uri);
        config.setConnectTimeout(Duration.ofMillis(200));
        config.setReadTimeout(Duration.ofMillis(200));
        config.setUnknownKidMinInterval(Duration.ofSeconds(30));
        return config;
    }

    private static EntraMachineJwtValidator validator() {
        var validator = mock(EntraMachineJwtValidator.class);
        when(validator.validate(any())).thenReturn(OAuth2TokenValidatorResult.success());
        return validator;
    }

    private static String uri(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/jwks";
    }

    private static String signedToken() throws Exception {
        var key = new RSAKeyGenerator(2048).keyID("transport-test").generate();
        Instant now = Instant.now();
        var token = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
                new JWTClaimsSet.Builder().issuer(ISSUER).subject("app").audience("lms")
                        .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(60))).build());
        token.sign(new RSASSASigner(key));
        return token.serialize();
    }
}
