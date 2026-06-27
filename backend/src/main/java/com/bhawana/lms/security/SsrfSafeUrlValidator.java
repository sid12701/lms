package com.bhawana.lms.security;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SsrfSafeUrlValidator {

    private static final Logger log = LoggerFactory.getLogger(SsrfSafeUrlValidator.class);

    private SsrfSafeUrlValidator() {
    }

    /**
     * Strict validation for the moment of egress (webhook delivery): the host must resolve and must
     * not map to a private/reserved address. Use this immediately before opening a connection.
     */
    public static void validate(String url) {
        validate(url, true);
    }

    /**
     * Registration-time validation (e.g. saving a webhook subscription). Validates scheme, host and
     * blocks resolvable private/reserved addresses, but does NOT require the host to resolve right
     * now — a partner endpoint may be temporarily unresolvable when configured. SSRF is still enforced
     * at delivery time by {@link #validate(String)}, which re-resolves immediately before connecting
     * (so this is not a TOCTOU weakening).
     */
    public static void validateRegistrationTarget(String url) {
        validate(url, false);
    }

    private static void validate(String url, boolean requireResolvableHost) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("URL must not be blank.");
        }

        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid URL: " + url, exception);
        }

        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("URL must use http or https scheme: " + url);
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("URL must have a valid host: " + url);
        }

        InetAddress resolved;
        try {
            resolved = InetAddress.getByName(host);
        } catch (UnknownHostException exception) {
            if (requireResolvableHost) {
                throw new IllegalArgumentException("URL host could not be resolved: " + host);
            }
            // Cannot classify an unresolvable host; defer SSRF enforcement to delivery time.
            return;
        }

        if (isPrivateOrReserved(resolved)) {
            log.warn("ssrf_blocked url={} resolvedIp={}", url, resolved.getHostAddress());
            throw new IllegalArgumentException(
                    "URL resolves to a private or reserved IP address and is not allowed: " + host
            );
        }
    }

    private static boolean isPrivateOrReserved(InetAddress address) {
        return address.isLoopbackAddress()
                || address.isSiteLocalAddress()
                || address.isLinkLocalAddress()
                || address.isAnyLocalAddress()
                || isCarrierGradeNat(address);
    }

    private static boolean isCarrierGradeNat(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int first = bytes[0] & 0xFF;
            int second = bytes[1] & 0xFF;
            return first == 100 && second >= 64 && second <= 127;
        }
        return false;
    }
}
