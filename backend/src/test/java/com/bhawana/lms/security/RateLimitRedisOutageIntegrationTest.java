package com.bhawana.lms.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.bhawana.lms.service.OpsAlertEmitters;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SslOptions;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import java.io.File;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * M10 end-to-end proof against real Redis containers: the rate limiter now rides the
 * connection factory's native Lettuce client, so credentials, TLS and the command deadline
 * actually apply; an outage applies the per-route policy instead of failing the process; and
 * the limiter recovers when Redis returns — no instance restart.
 */
@Testcontainers(disabledWithoutDocker = true)
class RateLimitRedisOutageIntegrationTest {

    private static final String REDIS_PASSWORD = "m10-test-password";
    // Fixed binding so a restarted container lands on the same host port and the limiter can
    // genuinely reconnect rather than silently finding a moved endpoint.
    private static final int RECOVERY_HOST_PORT = 16979;
    private static final Duration COMMAND_TIMEOUT = Duration.ofMillis(300);
    private static final Duration RECONNECT_INTERVAL = Duration.ofMillis(200);
    private static final File TRUSTED_CA = new File("src/test/resources/redis-tls/ca.crt");

    /** TLS-only Redis with a required password — the authenticated+TLS acceptance path. */
    @Container
    private static final GenericContainer<?> AUTH_TLS_REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.2-alpine"))
                    .withExposedPorts(6379)
                    .withCopyFileToContainer(
                            MountableFile.forClasspathResource("redis-tls/ca.crt"), "/tls/ca.crt")
                    .withCopyFileToContainer(
                            MountableFile.forClasspathResource("redis-tls/redis.crt"), "/tls/redis.crt")
                    .withCopyFileToContainer(
                            MountableFile.forClasspathResource("redis-tls/redis.key", 0644), "/tls/redis.key")
                    .withCommand(
                            "redis-server",
                            "--port", "0",
                            "--tls-port", "6379",
                            "--tls-cert-file", "/tls/redis.crt",
                            "--tls-key-file", "/tls/redis.key",
                            "--tls-ca-cert-file", "/tls/ca.crt",
                            "--tls-auth-clients", "no",
                            "--requirepass", REDIS_PASSWORD);

    /** Plain Redis pinned to a fixed host port so it can be stopped/started in place. */
    private static final GenericContainer<?> RECOVERY_REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.2-alpine"))
                    .withExposedPorts(6379);

    static {
        RECOVERY_REDIS.setPortBindings(List.of(RECOVERY_HOST_PORT + ":6379"));
        RECOVERY_REDIS.start();
    }

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    @SuppressWarnings("unchecked")
    private final ObjectProvider<OpsAlertEmitters> opsAlertEmittersProvider = mock(ObjectProvider.class);

    @AfterEach
    void leaveRecoveryRedisRunning() {
        if (!RECOVERY_REDIS.isRunning()) {
            RECOVERY_REDIS.start();
        }
    }

    private LettuceConnectionFactory connectionFactory(
            GenericContainer<?> redis,
            String password,
            boolean tls
    ) {
        return connectionFactory(redis.getHost(), redis.getMappedPort(6379), password, tls);
    }

    /** Host/port variant for containers that may be stopped — getMappedPort needs a live one. */
    private LettuceConnectionFactory connectionFactory(
            String host,
            int port,
            String password,
            boolean tls
    ) {
        LettuceClientConfiguration.LettuceClientConfigurationBuilder configBuilder =
                LettuceClientConfiguration.builder().commandTimeout(COMMAND_TIMEOUT);
        if (tls) {
            ClientOptions clientOptions = ClientOptions.builder()
                    .sslOptions(SslOptions.builder()
                            .jdkSslProvider()
                            .trustManager(TRUSTED_CA)
                            .build())
                    .build();
            configBuilder.useSsl().and().clientOptions(clientOptions);
        }
        RedisStandaloneConfiguration standalone = new RedisStandaloneConfiguration(host, port);
        if (password != null) {
            standalone.setPassword(RedisPassword.of(password));
        }
        LettuceConnectionFactory factory =
                new LettuceConnectionFactory(standalone, configBuilder.build());
        factory.setShareNativeConnection(true);
        factory.afterPropertiesSet();
        factory.start();
        return factory;
    }

    private RateLimitFilter filter(Supplier<Optional<ProxyManager<String>>> store) {
        RateLimitProperties properties = new RateLimitProperties();
        properties.setUnavailableRetryAfterSeconds(30);
        properties.setRules(List.of(
                rule("auth-login", "/api/v1/auth/login", RateLimitRule.StoreFailurePolicy.FAIL_CLOSED),
                rule("reports", "/api/v1/internal/reports/**", RateLimitRule.StoreFailurePolicy.FAIL_OPEN)));
        return new RateLimitFilter(store, objectMapper, properties, opsAlertEmittersProvider, meterRegistry);
    }

    private static RateLimitRule rule(
            String id,
            String path,
            RateLimitRule.StoreFailurePolicy policy
    ) {
        RateLimitRule rule = new RateLimitRule();
        rule.setId(id);
        rule.setPath(path);
        rule.setMethods(List.of("POST"));
        rule.setKey(KeyStrategy.IP);
        rule.setPermitsPerMinute(1);
        rule.setOnStoreFailure(policy);
        return rule;
    }

    private static MockHttpServletRequest request(String path, String remoteAddr) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setRemoteAddr(remoteAddr);
        return request;
    }

    private static MockHttpServletResponse passThrough(
            RateLimitFilter filter,
            MockHttpServletRequest request
    ) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        filter.doFilterInternal(request, response, chain);
        return response;
    }

    @Test
    void authenticatedTlsRedisRoundTripsAndEnforcesTheLimit() throws Exception {
        LettuceConnectionFactory factory = connectionFactory(AUTH_TLS_REDIS, REDIS_PASSWORD, true);
        try {
            RateLimitRedisConnectionProvider provider =
                    new RateLimitRedisConnectionProvider(factory.getNativeClient(), RECONNECT_INTERVAL);
            RateLimitFilter filter = filter(provider::proxyManager);

            // First request over AUTH + TLS consumes the sole permit; the second proves the
            // limiter is really enforcing — a dead/wrong store would be 503 (fail-closed), not 429.
            MockHttpServletResponse first =
                    passThrough(filter, request("/api/v1/auth/login", "198.51.100.21"));
            MockHttpServletResponse second =
                    passThrough(filter, request("/api/v1/auth/login", "198.51.100.21"));
            assertThat(first.getStatus()).isEqualTo(200);
            assertThat(second.getStatus()).isEqualTo(429);
        } finally {
            factory.destroy();
        }
    }

    @Test
    void wrongCredentialsFailClosedWithinDeadline() throws Exception {
        LettuceConnectionFactory factory =
                connectionFactory(AUTH_TLS_REDIS, "wrong-password", true);
        try {
            RateLimitRedisConnectionProvider provider =
                    new RateLimitRedisConnectionProvider(factory.getNativeClient(), RECONNECT_INTERVAL);
            RateLimitFilter filter = filter(provider::proxyManager);

            long started = System.nanoTime();
            MockHttpServletResponse response =
                    passThrough(filter, request("/api/v1/auth/login", "198.51.100.22"));
            long elapsedMs = (System.nanoTime() - started) / 1_000_000;

            assertThat(response.getStatus()).isEqualTo(503);
            assertThat(response.getHeader("Retry-After")).isEqualTo("30");
            assertThat(elapsedMs)
                    .as("a credential failure must fail closed quickly, not stall the request")
                    .isLessThan(2_000);
            assertThat(meterRegistry.find("lms.rate_limit.store.failures")
                    .tags("rule", "auth-login", "policy", "FAIL_CLOSED").counter().count())
                    .isGreaterThanOrEqualTo(1.0);
        } finally {
            factory.destroy();
        }
    }

    @Test
    void outageAppliesPolicyAndRecoveryNeedsNoRestart() throws Exception {
        RECOVERY_REDIS.stop();

        LettuceConnectionFactory factory =
                connectionFactory("localhost", RECOVERY_HOST_PORT, null, false);
        try {
            RateLimitRedisConnectionProvider provider =
                    new RateLimitRedisConnectionProvider(factory.getNativeClient(), RECONNECT_INTERVAL);
            RateLimitFilter filter = filter(provider::proxyManager);

            // Startup outage: provider connects lazily and returns empty — the fail-closed
            // route answers 503, the fail-open route still proceeds.
            MockHttpServletResponse loginWhileDown =
                    passThrough(filter, request("/api/v1/auth/login", "198.51.100.23"));
            assertThat(loginWhileDown.getStatus()).isEqualTo(503);
            assertThat(loginWhileDown.getHeader("Retry-After")).isEqualTo("30");

            MockHttpServletResponse reportsWhileDown =
                    passThrough(filter, request("/api/v1/internal/reports/x", "198.51.100.23"));
            assertThat(reportsWhileDown.getStatus()).isEqualTo(200);

            // Redis returns: after the reconnect interval the same provider instance dials
            // again and the limiter is live — no application restart.
            RECOVERY_REDIS.start();
            assertThat(awaitStatus(filter, "/api/v1/auth/login", "198.51.100.23", 503, 10_000))
                    .as("provider must reconnect once Redis is back")
                    .isNotEqualTo(503);

            // Mid-run outage: the established connection's commands fail on the deadline and
            // the fail-closed route is bounded again.
            RECOVERY_REDIS.stop();
            assertThat(awaitStatus(filter, "/api/v1/auth/login", "198.51.100.23", 503, 10_000))
                    .as("fail-closed route must answer 503 while Redis is down")
                    .isEqualTo(503);

            // Recovery from a live-connection loss: Lettuce auto-reconnects the same
            // connection — still no restart.
            RECOVERY_REDIS.start();
            assertThat(awaitStatus(filter, "/api/v1/auth/login", "198.51.100.23", 503, 15_000))
                    .as("limiter must recover from a mid-run outage without a restart")
                    .isNotEqualTo(503);
        } finally {
            if (!RECOVERY_REDIS.isRunning()) {
                RECOVERY_REDIS.start();
            }
            factory.destroy();
        }
    }

    /** Repeatedly passes requests until the status changes from {@code whileStatus} or the
     *  budget elapses; returns the last observed status. */
    private int awaitStatus(
            RateLimitFilter filter,
            String path,
            String remoteAddr,
            int whileStatus,
            long budgetMillis
    ) throws Exception {
        long deadline = System.nanoTime() + budgetMillis * 1_000_000L;
        int status;
        do {
            status = passThrough(filter, request(path, remoteAddr)).getStatus();
            if (status != whileStatus) {
                return status;
            }
            Thread.sleep(150);
        } while (System.nanoTime() < deadline);
        return status;
    }
}
