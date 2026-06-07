package com.bhawana.lms.security;

import org.springframework.util.AntPathMatcher;

final class RateLimitRuleMatcher {

    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    private RateLimitRuleMatcher() {
    }

    static boolean matches(RateLimitRule rule, String requestUri, String method) {
        if (rule.getPath() == null || rule.getPath().isBlank()) {
            return false;
        }
        if (rule.getMethods() == null || rule.getMethods().isEmpty()) {
            return false;
        }
        String normalizedMethod = method == null ? "" : method.toUpperCase();
        boolean methodMatches = rule.getMethods().stream()
                .anyMatch(candidate -> candidate != null && candidate.equalsIgnoreCase(normalizedMethod));
        if (!methodMatches) {
            return false;
        }
        String path = stripQuery(requestUri);
        return PATH_MATCHER.match(rule.getPath(), path);
    }

    private static String stripQuery(String requestUri) {
        int queryIndex = requestUri.indexOf('?');
        return queryIndex < 0 ? requestUri : requestUri.substring(0, queryIndex);
    }
}
