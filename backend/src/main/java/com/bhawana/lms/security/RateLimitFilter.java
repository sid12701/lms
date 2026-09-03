package com.bhawana.lms.security;

import com.bhawana.lms.common.api.ApiError;
import com.bhawana.lms.common.correlation.CorrelationIdHolder;
import com.bhawana.lms.service.OpsAlertEmitters;
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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

@ConditionalOnProperty(name = "app.rate-limit.enabled", havingValue = "true", matchIfMissing = true)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private final ProxyManager<String> proxyManager;
    private final ObjectMapper objectMapper;
    private final RateLimitProperties properties;
    private final ObjectProvider<OpsAlertEmitters> opsAlertEmittersProvider;

    public RateLimitFilter(
            ProxyManager<String> rateLimitProxyManager,
            ObjectMapper objectMapper,
            RateLimitProperties properties,
            ObjectProvider<OpsAlertEmitters> opsAlertEmittersProvider
    ) {
        this.proxyManager = rateLimitProxyManager;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.opsAlertEmittersProvider = opsAlertEmittersProvider;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        RateLimitRejection rejection = evaluate(request);
        if (rejection != null) {
            response.setHeader("Retry-After", Long.toString(rejection.retryAfterSeconds()));
            log.warn(
                    "Rate limit exceeded for bucket {} — retry after {}s",
                    rejection.bucketKey(),
                    rejection.retryAfterSeconds()
            );
            OpsAlertEmitters alertEmitters = opsAlertEmittersProvider.getIfAvailable();
            if (alertEmitters != null) {
                try {
                    alertEmitters.emitRateLimitBreach(
                            rejection.bucketKey(),
                            request.getRequestURI(),
                            rejection.retryAfterSeconds()
                    );
                } catch (RuntimeException exception) {
                    // The 429 is already correct and must be returned regardless — alerting is
                    // observability, not part of the HTTP contract. But log it loud: this was
                    // debug-level, and it hid a permission error that silently suppressed
                    // RATE_LIMIT_BREACH for every LSP-keyed rule until someone read the SQL grants.
                    log.warn(
                            "Rate limit breach alert failed for bucket {} on {} — the 429 was still returned",
                            rejection.bucketKey(),
                            request.getRequestURI(),
                            exception
                    );
                }
            }
            writeApiError(
                    response,
                    429,
                    "RATE_LIMIT_EXCEEDED",
                    "Too many requests. Please retry after " + rejection.retryAfterSeconds() + " seconds.",
                    request.getRequestURI()
            );
            return;
        }

        filterChain.doFilter(request, response);
    }

    private RateLimitRejection evaluate(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String requestUri = request.getRequestURI();
        String method = request.getMethod();

        for (RateLimitRule rule : properties.getRules()) {
            if (!RateLimitRuleMatcher.matches(rule, requestUri, method)) {
                continue;
            }
            List<RateLimitBucketSpec> buckets = rule.getKey().resolveBuckets(rule, request, authentication);
            if (buckets.isEmpty()) {
                return null;
            }

            long retryAfterSeconds = 0L;
            String rejectingBucketKey = null;
            for (RateLimitBucketSpec bucketSpec : buckets) {
                BucketProxy bucket = proxyManager.builder()
                        .build(bucketSpec.bucketKey(), configurationSupplier(bucketSpec.permitsPerMinute()));
                ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
                if (!probe.isConsumed()) {
                    long waitSeconds = Math.max(1L, probe.getNanosToWaitForRefill() / 1_000_000_000L);
                    if (rejectingBucketKey == null || waitSeconds > retryAfterSeconds) {
                        rejectingBucketKey = bucketSpec.bucketKey();
                        retryAfterSeconds = waitSeconds;
                    }
                }
            }
            if (rejectingBucketKey != null) {
                return new RateLimitRejection(rejectingBucketKey, retryAfterSeconds);
            }
            return null;
        }
        return null;
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
        byte[] body = objectMapper.writeValueAsBytes(ApiError.of(
                status,
                code,
                message,
                path,
                CorrelationIdHolder.get(),
                java.util.Map.of()
        ));
        if (!response.isCommitted()) {
            response.resetBuffer();
        }
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
        response.getOutputStream().flush();
        response.flushBuffer();
    }

    private record RateLimitRejection(String bucketKey, long retryAfterSeconds) {
    }
}
