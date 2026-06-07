package com.bhawana.lms.security;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.AntPathMatcher;

public enum KeyStrategy {
    IP {
        @Override
        List<RateLimitBucketSpec> resolveBuckets(
                RateLimitRule rule,
                HttpServletRequest request,
                Authentication authentication
        ) {
            String ip = clientIp(request);
            if (ip == null) {
                return List.of();
            }
            return List.of(bucket(rule, "ip:" + ip, rule.getPermitsPerMinute()));
        }
    },
    SUBJECT {
        @Override
        List<RateLimitBucketSpec> resolveBuckets(
                RateLimitRule rule,
                HttpServletRequest request,
                Authentication authentication
        ) {
            String subject = resolveSubject(request, authentication);
            if (subject == null) {
                return List.of();
            }
            return List.of(bucket(rule, "subject:" + subject, rule.getPermitsPerMinute()));
        }
    },
    LSP {
        @Override
        List<RateLimitBucketSpec> resolveBuckets(
                RateLimitRule rule,
                HttpServletRequest request,
                Authentication authentication
        ) {
            UUID lspId = extractLspId(authentication);
            if (lspId == null) {
                return List.of();
            }
            return List.of(bucket(rule, "lsp:" + lspId, rule.getPermitsPerMinute()));
        }
    },
    IP_AND_SUBJECT {
        @Override
        List<RateLimitBucketSpec> resolveBuckets(
                RateLimitRule rule,
                HttpServletRequest request,
                Authentication authentication
        ) {
            String ip = clientIp(request);
            String subject = resolveSubject(request, authentication);
            if (ip == null || subject == null) {
                return List.of();
            }
            int permits = rule.getPermitsPerMinute();
            return List.of(
                    bucket(rule, "ip:" + ip, permits),
                    bucket(rule, "subject:" + subject, permits)
            );
        }
    },
    SUBJECT_AND_APPLICATION {
        @Override
        List<RateLimitBucketSpec> resolveBuckets(
                RateLimitRule rule,
                HttpServletRequest request,
                Authentication authentication
        ) {
            String subject = resolveSubject(request, authentication);
            String applicationId = extractPathVariable(request.getRequestURI(), rule.getPath(), "applicationId");
            if (subject == null || applicationId == null) {
                return List.of();
            }
            return List.of(
                    bucket(rule, "subject:" + subject, rule.getPermitsSubject()),
                    bucket(rule, "application:" + applicationId, rule.getPermitsApplication())
            );
        }
    };

    abstract List<RateLimitBucketSpec> resolveBuckets(
            RateLimitRule rule,
            HttpServletRequest request,
            Authentication authentication
    );

    private static RateLimitBucketSpec bucket(RateLimitRule rule, String suffix, int permitsPerMinute) {
        return new RateLimitBucketSpec(rule.getId() + ":" + suffix, permitsPerMinute);
    }

    private static String clientIp(HttpServletRequest request) {
        String ip = request.getRemoteAddr();
        if (ip == null || ip.isBlank()) {
            return null;
        }
        return ip;
    }

    private static String resolveSubject(HttpServletRequest request, Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
            String subject = jwt.getSubject();
            if (subject != null && !subject.isBlank()) {
                return subject;
            }
        }
        String refreshCookie = readCookie(request, "lms-refresh");
        if (refreshCookie != null && !refreshCookie.isBlank()) {
            return "refresh:" + sha256Hex(refreshCookie);
        }
        return null;
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

    private static String extractPathVariable(String requestUri, String rulePath, String variableName) {
        String path = stripQuery(requestUri);
        AntPathMatcher matcher = new AntPathMatcher();
        if (!matcher.match(rulePath, path)) {
            return null;
        }
        Map<String, String> variables = matcher.extractUriTemplateVariables(rulePath, path);
        return variables.get(variableName);
    }

    private static String stripQuery(String requestUri) {
        int queryIndex = requestUri.indexOf('?');
        return queryIndex < 0 ? requestUri : requestUri.substring(0, queryIndex);
    }

    private static String readCookie(HttpServletRequest request, String name) {
        if (request.getCookies() == null) {
            return null;
        }
        for (var cookie : request.getCookies()) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 not available.", exception);
        }
    }
}
