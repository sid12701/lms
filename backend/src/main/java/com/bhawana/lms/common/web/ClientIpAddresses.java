package com.bhawana.lms.common.web;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Resolves the client IP for allowlist and audit checks.
 *
 * <p>Honours {@code X-Forwarded-For} (first hop) and {@code X-Real-IP} when present so
 * enforcement works behind reverse proxies; falls back to {@link HttpServletRequest#getRemoteAddr()}.
 */
public final class ClientIpAddresses {

    private ClientIpAddresses() {}

    public static String resolve(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            String first = (comma >= 0 ? forwarded.substring(0, comma) : forwarded).trim();
            if (!first.isEmpty()) {
                return first;
            }
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }
}
