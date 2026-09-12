package com.bhawana.lms.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;

/**
 * Embedded-server support: exercises the real {@link KeyStrategy} rate-limiter IP path.
 *
 * <p>Deliberately annotation-free so component scanning (the full app scans
 * {@code com.bhawana.lms}) never registers anything from this file. The minimal test
 * application lives in the {@code edgetest} test package, outside the app scan, so its
 * {@code @EnableAutoConfiguration} exclusions cannot leak into full-context tests.
 */
public final class EdgeIpEmbeddedSupport {

    private EdgeIpEmbeddedSupport() {}

    /**
     * Returns the IP suffix the real {@link KeyStrategy#IP} rate rule would bucket on, or
     * {@code "<none>"} when no bucket resolves. Shares {@code ClientIpAddresses.resolve} with
     * the auth/allowlist/audit path by construction (see {@link KeyStrategy}).
     */
    public static String rateBucketIp(HttpServletRequest request) {
        RateLimitRule rule = new RateLimitRule();
        rule.setId("auth-login");
        rule.setPath("/api/v1/auth/login");
        rule.setMethods(List.of("POST"));
        rule.setKey(KeyStrategy.IP);
        rule.setPermitsPerMinute(10);
        List<RateLimitBucketSpec> buckets = rule.getKey().resolveBuckets(rule, request, null);
        if (buckets.isEmpty()) {
            return "<none>";
        }
        String key = buckets.get(0).bucketKey();
        return key.startsWith("auth-login:ip:") ? key.substring("auth-login:ip:".length()) : key;
    }
}
