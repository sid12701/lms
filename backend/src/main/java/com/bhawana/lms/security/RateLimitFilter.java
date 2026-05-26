package com.bhawana.lms.security;

import com.bhawana.lms.common.api.ApiError;
import com.bhawana.lms.common.correlation.CorrelationIdHolder;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.BucketProxy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.oauth2.jwt.Jwt;
import com.bhawana.lms.service.AlertRuleEvaluationService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@ConditionalOnProperty(name = "app.rate-limit.enabled", havingValue = "true", matchIfMissing = true)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);
    private static final String LSP_PATH_PREFIX = "/api/v1/lsp/";
    private static final String LOGIN_PATH = "/api/v1/auth/login";
    private static final String TOKEN_PATH = "/api/v1/auth/token";

    private final ProxyManager<String> proxyManager;
    private final ObjectMapper objectMapper;
    private final RateLimitProperties properties;
    private final ObjectProvider<AlertRuleEvaluationService> alertRuleEvaluationServiceProvider;

    public RateLimitFilter(
            ProxyManager<String> rateLimitProxyManager,
            ObjectMapper objectMapper,
            RateLimitProperties properties,
            ObjectProvider<AlertRuleEvaluationService> alertRuleEvaluationServiceProvider
    ) {
        this.proxyManager = rateLimitProxyManager;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.alertRuleEvaluationServiceProvider = alertRuleEvaluationServiceProvider;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        RateLimitTarget target = resolveTarget(request);
        if (target == null) {
            filterChain.doFilter(request, response);
            return;
        }

        BucketProxy bucket = proxyManager.builder().build(target.key(), configurationSupplier(target.permitsPerMinute()));
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            filterChain.doFilter(request, response);
            return;
        }

        long retryAfterSeconds = Math.max(1L, probe.getNanosToWaitForRefill() / 1_000_000_000L);
        response.setHeader("Retry-After", Long.toString(retryAfterSeconds));
        log.warn("Rate limit exceeded for bucket {} — retry after {}s", target.key(), retryAfterSeconds);
        AlertRuleEvaluationService alertRules = alertRuleEvaluationServiceProvider.getIfAvailable();
        if (alertRules != null) {
            alertRules.emitRateLimitBreach(target.key(), request.getRequestURI(), retryAfterSeconds);
        }
        writeApiError(
                response,
                429,
                "RATE_LIMIT_EXCEEDED",
                "Too many requests. Please retry after " + retryAfterSeconds + " seconds.",
                request.getRequestURI()
        );
    }

    private RateLimitTarget resolveTarget(HttpServletRequest request) {
        String path = request.getRequestURI();
        String method = request.getMethod();

        if ("POST".equalsIgnoreCase(method) && (LOGIN_PATH.equals(path) || TOKEN_PATH.equals(path))) {
            String ip = request.getRemoteAddr();
            if (ip == null || ip.isBlank()) {
                return null;
            }
            return new RateLimitTarget("auth:" + ip, properties.getAuthPerMinute());
        }

        if (path.startsWith(LSP_PATH_PREFIX) && isWriteMethod(method)) {
            UUID lspId = extractLspId(SecurityContextHolder.getContext().getAuthentication());
            if (lspId == null) {
                return null;
            }
            return new RateLimitTarget("lsp-write:" + lspId, properties.getLspWritePerMinute());
        }

        return null;
    }

    private static boolean isWriteMethod(String method) {
        return "POST".equalsIgnoreCase(method)
                || "PUT".equalsIgnoreCase(method)
                || "PATCH".equalsIgnoreCase(method)
                || "DELETE".equalsIgnoreCase(method);
    }

    private static UUID extractLspId(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            return null;
        }
        String rawLspId = jwt.getClaimAsString("lspId");
        if (rawLspId == null || rawLspId.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(rawLspId);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static Supplier<BucketConfiguration> configurationSupplier(int permitsPerMinute) {
        return () -> BucketConfiguration.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(permitsPerMinute)
                        .refillGreedy(permitsPerMinute, Duration.ofMinutes(1))
                        .build())
                .build();
    }

    private void writeApiError(
            HttpServletResponse response,
            int status,
            String code,
            String message,
            String path
    ) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        objectMapper.writeValue(response.getWriter(), ApiError.of(
                status,
                code,
                message,
                path,
                CorrelationIdHolder.get(),
                Map.of()
        ));
    }

    private record RateLimitTarget(String key, int permitsPerMinute) {
    }
}
