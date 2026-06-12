package com.bhawana.lms.security;

import com.bhawana.lms.common.api.ApiError;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.BucketProxy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.distributed.proxy.RemoteBucketBuilder;
import jakarta.servlet.FilterChain;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RateLimitFilterIntegrationTest {

    @Mock
    private ProxyManager<String> proxyManager;

    @Mock
    private RemoteBucketBuilder<String> remoteBucketBuilder;

    @Mock
    private ObjectProvider<com.bhawana.lms.service.OpsAlertEmitters> opsAlertEmittersProvider;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final Map<String, AtomicInteger> consumption = new ConcurrentHashMap<>();

    private RateLimitFilter filter;

    @BeforeEach
    void setUp() {
        consumption.clear();
    }

    private void stubBucketConsumption(int permits) {
        when(proxyManager.builder()).thenReturn(remoteBucketBuilder);
        when(remoteBucketBuilder.build(anyString(), ArgumentMatchers.<Supplier<BucketConfiguration>>any()))
                .thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            BucketProxy bucket = mock(BucketProxy.class);
            when(bucket.tryConsumeAndReturnRemaining(1)).thenAnswer(call -> {
                int used = consumption.computeIfAbsent(key, ignored -> new AtomicInteger()).incrementAndGet();
                if (used <= permits) {
                    return ConsumptionProbe.consumed((long) permits - used, permits);
                }
                ConsumptionProbe rejected = mock(ConsumptionProbe.class);
                when(rejected.isConsumed()).thenReturn(false);
                when(rejected.getNanosToWaitForRefill()).thenReturn(60_000_000_000L);
                return rejected;
            });
            return bucket;
        });
    }

    @Test
    void authLoginRuleReturns429AfterConfiguredPermits() throws Exception {
        stubBucketConsumption(2);
        RateLimitProperties properties = new RateLimitProperties();
        RateLimitRule rule = new RateLimitRule();
        rule.setId("auth-login");
        rule.setPath("/api/v1/auth/login");
        rule.setMethods(List.of("POST"));
        rule.setKey(KeyStrategy.IP);
        rule.setPermitsPerMinute(2);
        properties.setRules(List.of(rule));
        filter = new RateLimitFilter(
                proxyManager,
                objectMapper,
                properties,
                opsAlertEmittersProvider
        );

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.setRemoteAddr("203.0.113.10");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);
        filter.doFilterInternal(request, new MockHttpServletResponse(), chain);
        filter.doFilterInternal(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isNotBlank();
        ApiError body = objectMapper.readValue(response.getContentAsString(), ApiError.class);
        assertThat(body.code()).isEqualTo("RATE_LIMIT_EXCEEDED");
    }

    @Test
    void subjectKeyedRuleUsesJwtSubject() throws Exception {
        stubBucketConsumption(1);
        RateLimitProperties properties = new RateLimitProperties();
        RateLimitRule rule = new RateLimitRule();
        rule.setId("reports");
        rule.setPath("/api/v1/internal/reports/**");
        rule.setMethods(List.of("GET"));
        rule.setKey(KeyStrategy.SUBJECT);
        rule.setPermitsPerMinute(1);
        properties.setRules(List.of(rule));
        filter = new RateLimitFilter(
                proxyManager,
                objectMapper,
                properties,
                opsAlertEmittersProvider
        );

        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("ops.admin")
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(jwt, "token", List.of())
        );

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/internal/reports/portfolio-mis/summary");
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, new MockHttpServletResponse(), chain);
        MockHttpServletResponse blocked = new MockHttpServletResponse();
        filter.doFilterInternal(request, blocked, chain);

        assertThat(blocked.getStatus()).isEqualTo(429);
        verify(chain).doFilter(any(), any());
    }
}
