package com.bhawana.lms.support;

import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Test helper: sets the simulated socket peer on MockMvc requests.
 *
 * <p>Forwarding headers are untrusted by default (fail closed), so tests that need a request
 * to originate from a given client IP must set the peer via {@code request.setRemoteAddr(..)}
 * rather than sending {@code X-Forwarded-For}. Header-trust behavior itself is covered by the
 * embedded-server edge tests with explicit trusted-proxy configuration.
 */
public final class IpTestSupport {

    private IpTestSupport() {}

    public static RequestPostProcessor remoteAddr(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }
}
