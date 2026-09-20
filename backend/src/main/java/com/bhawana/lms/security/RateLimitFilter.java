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
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Distributed rate limiting via Bucket4j over Redis.
 *
 * <p>Outage policy (M10): the rate-limit store is an availability control, not a financial
 * control — it is deliberately independent of the idempotency/locking machinery. When Redis
 * cannot be reached or a command exceeds its deadline, each rule's
 * {@link RateLimitRule.StoreFailurePolicy} decides: credential-attack-sensitive routes
 * (login/token/refresh/password) FAIL CLOSED with a bounded 503 + Retry-After; other matched
 * routes FAIL OPEN so a Redis blip cannot take the whole API down. Both paths are counted in
 * the {@code lms.rate_limit.store.failures} meter.
 */
@ConditionalOnProperty(name = "app.rate-limit.enabled", havingValue = "true", matchIfMissing = true)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);
    private static final Duration STORE_FAILURE_LOG_INTERVAL = Duration.ofMinutes(1);

    private final Supplier<Optional<ProxyManager<String>>> proxyManagerSupplier;
    private final ObjectMapper objectMapper;
    private final RateLimitProperties properties;
    private final ObjectProvider<OpsAlertEmitters> opsAlertEmittersProvider;
    private final MeterRegistry meterRegistry;

    /** Throttles store-outage warnings so a Redis partition does not flood the log per request. */
    private volatile long lastStoreFailureLogNanos;

    public RateLimitFilter(
            Supplier<Optional<ProxyManager<String>>> proxyManagerSupplier,
            ObjectMapper objectMapper,
            RateLimitProperties properties,
            ObjectProvider<OpsAlertEmitters> opsAlertEmittersProvider,
            MeterRegistry meterRegistry
    ) {
        this.proxyManagerSupplier = proxyManagerSupplier;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.opsAlertEmittersProvider = opsAlertEmittersProvider;
        this.meterRegistry = meterRegistry;
    }

    /** Convenience for tests that already hold a {@link ProxyManager}. */
    public RateLimitFilter(
            ProxyManager<String> proxyManager,
            ObjectMapper objectMapper,
            RateLimitProperties properties,
            ObjectProvider<OpsAlertEmitters> opsAlertEmittersProvider,
            MeterRegistry meterRegistry
    ) {
        this(() -> Optional.of(proxyManager), objectMapper, properties, opsAlertEmittersProvider, meterRegistry);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        RateLimitDecision decision = evaluate(request);
        if (decision.outcome() == Outcome.REJECTED) {
            response.setHeader("Retry-After", Long.toString(decision.retryAfterSeconds()));
            log.warn(
                    "Rate limit exceeded for bucket {} — retry after {}s",
                    decision.bucketKey(),
                    decision.retryAfterSeconds()
            );
            OpsAlertEmitters alertEmitters = opsAlertEmittersProvider.getIfAvailable();
            if (alertEmitters != null) {
                try {
                    alertEmitters.emitRateLimitBreach(
                            decision.bucketKey(),
                            request.getRequestURI(),
                            decision.retryAfterSeconds()
                    );
                } catch (RuntimeException exception) {
                    // The 429 is already correct and must be returned regardless — alerting is
                    // observability, not part of the HTTP contract. But log it loud: this was
                    // debug-level, and it hid a permission error that silently suppressed
                    // RATE_LIMIT_BREACH for every LSP-keyed rule until someone read the SQL grants.
                    log.warn(
                            "Rate limit breach alert failed for bucket {} on {} — the 429 was still returned",
                            decision.bucketKey(),
                            request.getRequestURI(),
                            exception
                    );
                }
            }
            writeApiError(
                    response,
                    429,
                    "RATE_LIMIT_EXCEEDED",
                    "Too many requests. Please retry after " + decision.retryAfterSeconds() + " seconds.",
                    request.getRequestURI()
            );
            return;
        }
        if (decision.outcome() == Outcome.STORE_UNAVAILABLE) {
            // Bounded fail-closed (M10): credential-attack-sensitive routes must not accept
            // attempts while the limiter is blind. Retry-After gives honest clients a backoff.
            response.setHeader("Retry-After", Long.toString(decision.retryAfterSeconds()));
            writeApiError(
                    response,
                    503,
                    "RATE_LIMIT_UNAVAILABLE",
                    "Rate limiting is temporarily unavailable. Please retry later.",
                    request.getRequestURI()
            );
            return;
        }

        filterChain.doFilter(request, response);
    }

    private RateLimitDecision evaluate(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String requestUri = request.getRequestURI();
        String method = request.getMethod();

        for (RateLimitRule rule : properties.getRules()) {
            if (!RateLimitRuleMatcher.matches(rule, requestUri, method)) {
                continue;
            }
            List<RateLimitBucketSpec> buckets = rule.getKey().resolveBuckets(rule, request, authentication);
            if (buckets.isEmpty()) {
                return RateLimitDecision.proceed();
            }

            Optional<ProxyManager<String>> store = proxyManagerSupplier.get();
            if (store.isEmpty()) {
                return storeUnavailable(rule, requestUri, null);
            }
            long retryAfterSeconds = 0L;
            String rejectingBucketKey = null;
            try {
                for (RateLimitBucketSpec bucketSpec : buckets) {
                    BucketProxy bucket = store.get().builder()
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
            } catch (RuntimeException storeException) {
                return storeUnavailable(rule, requestUri, storeException);
            }
            if (rejectingBucketKey != null) {
                return RateLimitDecision.rejected(rejectingBucketKey, retryAfterSeconds);
            }
            return RateLimitDecision.proceed();
        }
        return RateLimitDecision.proceed();
    }

    /**
     * Applies the rule's declared outage policy (M10). FAIL_CLOSED yields a bounded 503;
     * FAIL_OPEN lets the request proceed. Either way the failure is counted — outages must be
     * visible in metrics, not silently absorbed.
     */
    private RateLimitDecision storeUnavailable(
            RateLimitRule rule,
            String requestUri,
            RuntimeException cause
    ) {
        RateLimitRule.StoreFailurePolicy policy = rule.getOnStoreFailure();
        meterRegistry.counter(
                "lms.rate_limit.store.failures",
                "rule", rule.getId(),
                "policy", policy.name()
        ).increment();

        // Warn at most once a minute: an outage on a hot route would otherwise produce a log
        // line per request.
        long now = System.nanoTime();
        long lastLogged = lastStoreFailureLogNanos;
        if (now - lastLogged >= STORE_FAILURE_LOG_INTERVAL.toNanos()
                && lastStoreFailureLogNanosCompareAndSet(lastLogged, now)) {
            log.warn(
                    "rate_limit_store_unavailable rule={} policy={} uri={} — applying {}: {}",
                    rule.getId(),
                    policy,
                    requestUri,
                    policy == RateLimitRule.StoreFailurePolicy.FAIL_CLOSED
                            ? "bounded 503 + Retry-After"
                            : "allowing request (fail open)",
                    cause == null ? "no Redis connection" : cause.toString()
            );
        }

        if (policy == RateLimitRule.StoreFailurePolicy.FAIL_CLOSED) {
            return RateLimitDecision.storeUnavailable(properties.getUnavailableRetryAfterSeconds());
        }
        return RateLimitDecision.proceed();
    }

    private boolean lastStoreFailureLogNanosCompareAndSet(long expected, long value) {
        // Good-enough throttle: a rare double-log under contention is harmless.
        if (lastStoreFailureLogNanos == expected) {
            lastStoreFailureLogNanos = value;
            return true;
        }
        return false;
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

    private enum Outcome {
        PROCEED,
        REJECTED,
        STORE_UNAVAILABLE
    }

    private record RateLimitDecision(Outcome outcome, String bucketKey, long retryAfterSeconds) {

        static RateLimitDecision proceed() {
            return new RateLimitDecision(Outcome.PROCEED, null, 0L);
        }

        static RateLimitDecision rejected(String bucketKey, long retryAfterSeconds) {
            return new RateLimitDecision(Outcome.REJECTED, bucketKey, retryAfterSeconds);
        }

        static RateLimitDecision storeUnavailable(long retryAfterSeconds) {
            return new RateLimitDecision(Outcome.STORE_UNAVAILABLE, null, retryAfterSeconds);
        }
    }
}
