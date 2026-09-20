package com.bhawana.lms.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bhawana.lms.common.api.ApiError;
import com.bhawana.lms.service.OpsAlertEmitters;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.distributed.BucketProxy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.distributed.proxy.RemoteBucketBuilder;
import io.lettuce.core.RedisConnectionException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * M10 outage policy: when the rate-limit store is unreachable or commands fail, a rule's
 * {@code on-store-failure} decides the outcome — FAIL_CLOSED answers a bounded 503 with
 * Retry-After (credential-attack-sensitive routes), FAIL_OPEN lets the request through.
 * Both count a {@code lms.rate_limit.store.failures} metric so an outage is observable.
 */
@ExtendWith(MockitoExtension.class)
class RateLimitFilterOutagePolicyTest {

    @Mock
    private ObjectProvider<OpsAlertEmitters> opsAlertEmittersProvider;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

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
        rule.setPermitsPerMinute(10);
        rule.setOnStoreFailure(policy);
        return rule;
    }

    private RateLimitFilter filter(
            Supplier<Optional<ProxyManager<String>>> store,
            RateLimitRule rule
    ) {
        RateLimitProperties properties = new RateLimitProperties();
        properties.setUnavailableRetryAfterSeconds(30);
        properties.setRules(List.of(rule));
        return new RateLimitFilter(store, objectMapper, properties, opsAlertEmittersProvider, meterRegistry);
    }

    @Test
    void failClosedRouteReturns503WhenStoreUnreachable() throws Exception {
        RateLimitRule rule = rule("auth-login", "/api/v1/auth/login", RateLimitRule.StoreFailurePolicy.FAIL_CLOSED);
        RateLimitFilter filter = filter(Optional::empty, rule);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.setRemoteAddr("203.0.113.10");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeader("Retry-After")).isEqualTo("30");
        ApiError body = objectMapper.readValue(response.getContentAsString(), ApiError.class);
        assertThat(body.code()).isEqualTo("RATE_LIMIT_UNAVAILABLE");
        verify(chain, never()).doFilter(any(), any());
        assertThat(storeFailureCount("auth-login", "FAIL_CLOSED")).isEqualTo(1.0);
    }

    @Test
    void failOpenRouteProceedsWhenStoreUnreachable() throws Exception {
        RateLimitRule rule = rule("reports", "/api/v1/internal/reports/**", RateLimitRule.StoreFailurePolicy.FAIL_OPEN);
        RateLimitFilter filter = filter(Optional::empty, rule);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/internal/reports/x");
        request.setRemoteAddr("203.0.113.10");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(storeFailureCount("reports", "FAIL_OPEN")).isEqualTo(1.0);
    }

    @Test
    void failClosedRouteReturns503WhenStoreCommandFails() throws Exception {
        RateLimitRule rule = rule("auth-token", "/api/v1/auth/token", RateLimitRule.StoreFailurePolicy.FAIL_CLOSED);
        ProxyManager<String> proxyManager = failingProxyManager();
        RateLimitFilter filter = filter(() -> Optional.of(proxyManager), rule);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/token");
        request.setRemoteAddr("203.0.113.10");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeader("Retry-After")).isEqualTo("30");
        ApiError body = objectMapper.readValue(response.getContentAsString(), ApiError.class);
        assertThat(body.code()).isEqualTo("RATE_LIMIT_UNAVAILABLE");
        verify(chain, never()).doFilter(any(), any());
        assertThat(storeFailureCount("auth-token", "FAIL_CLOSED")).isEqualTo(1.0);
    }

    @Test
    void failOpenRouteProceedsWhenStoreCommandFails() throws Exception {
        RateLimitRule rule = rule("lsp-loan-events", "/api/v1/lsp/loan-events", RateLimitRule.StoreFailurePolicy.FAIL_OPEN);
        ProxyManager<String> proxyManager = failingProxyManager();
        RateLimitFilter filter = filter(() -> Optional.of(proxyManager), rule);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/lsp/loan-events");
        request.setRemoteAddr("203.0.113.10");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(storeFailureCount("lsp-loan-events", "FAIL_OPEN")).isEqualTo(1.0);
    }

    @Test
    void unmatchedRouteProceedsWithoutTouchingTheStore() throws Exception {
        RateLimitRule rule = rule("auth-login", "/api/v1/auth/login", RateLimitRule.StoreFailurePolicy.FAIL_CLOSED);
        RateLimitFilter filter = filter(Optional::empty, rule);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/internal/reports/portfolio-mis");
        request.setRemoteAddr("203.0.113.10");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(storeFailureCount("auth-login", "FAIL_CLOSED")).isZero();
    }

    private double storeFailureCount(String ruleId, String policy) {
        var counter = meterRegistry.find("lms.rate_limit.store.failures")
                .tags("rule", ruleId, "policy", policy)
                .counter();
        return counter == null ? 0.0 : counter.count();
    }

    @SuppressWarnings("unchecked")
    private static ProxyManager<String> failingProxyManager() {
        ProxyManager<String> proxyManager = mock(ProxyManager.class);
        RemoteBucketBuilder<String> builder = mock(RemoteBucketBuilder.class);
        BucketProxy bucket = mock(BucketProxy.class);
        when(proxyManager.builder()).thenReturn(builder);
        when(builder.build(anyString(), ArgumentMatchers.<Supplier<BucketConfiguration>>any()))
                .thenReturn(bucket);
        when(bucket.tryConsumeAndReturnRemaining(eq(1L)))
                .thenThrow(new RedisConnectionException("simulated Redis outage"));
        return proxyManager;
    }
}
