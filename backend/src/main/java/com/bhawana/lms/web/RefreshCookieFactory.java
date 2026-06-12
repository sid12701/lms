package com.bhawana.lms.web;

import com.bhawana.lms.security.SecurityProperties;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

@Component
public class RefreshCookieFactory {

    public static final String COOKIE_NAME = "lms-refresh";
    private static final String COOKIE_PATH = "/api/v1/auth";

    private final SecurityProperties securityProperties;

    public RefreshCookieFactory(SecurityProperties securityProperties) {
        this.securityProperties = securityProperties;
    }

    public ResponseCookie build(String rawToken, long maxAgeSeconds) {
        return ResponseCookie.from(COOKIE_NAME, rawToken)
                .httpOnly(true)
                .secure(securityProperties.getJwt().isSecureCookies())
                .sameSite("Strict")
                .path(COOKIE_PATH)
                .maxAge(maxAgeSeconds)
                .build();
    }

    public long refreshTtlSeconds() {
        return securityProperties.getJwt().getRefreshTtl().getSeconds();
    }
}
