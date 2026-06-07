package com.bhawana.lms.security;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitRuleMatcherTest {

    @Test
    void matchesAuthLoginPost() {
        RateLimitRule rule = rule("/api/v1/auth/login", "POST");
        assertThat(RateLimitRuleMatcher.matches(rule, "/api/v1/auth/login", "POST")).isTrue();
        assertThat(RateLimitRuleMatcher.matches(rule, "/api/v1/auth/login", "GET")).isFalse();
    }

    @Test
    void matchesWildcardAdminResetPassword() {
        RateLimitRule rule = rule("/api/v1/internal/admin/users/*/reset-password", "POST");
        assertThat(RateLimitRuleMatcher.matches(
                rule,
                "/api/v1/internal/admin/users/11111111-1111-1111-1111-111111111111/reset-password",
                "POST"
        )).isTrue();
    }

    @Test
    void matchesReportsGlob() {
        RateLimitRule rule = rule("/api/v1/internal/reports/**", "GET");
        assertThat(RateLimitRuleMatcher.matches(rule, "/api/v1/internal/reports/portfolio-mis", "GET")).isTrue();
        assertThat(RateLimitRuleMatcher.matches(rule, "/api/v1/internal/reports/portfolio-mis/preview", "GET")).isTrue();
    }

    private static RateLimitRule rule(String path, String method) {
        RateLimitRule rule = new RateLimitRule();
        rule.setId("test");
        rule.setPath(path);
        rule.setMethods(List.of(method));
        rule.setKey(KeyStrategy.IP);
        rule.setPermitsPerMinute(10);
        return rule;
    }
}
