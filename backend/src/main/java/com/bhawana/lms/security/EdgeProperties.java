package com.bhawana.lms.security;

import com.bhawana.lms.common.web.ClientIpAddresses;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.security.web.util.matcher.IpAddressMatcher;

/**
 * Edge IP trust configuration.
 *
 * <p>No proxy hop is trusted implicitly — not even private ranges. The deployer must list every
 * reverse proxy / ingress that is allowed to assert client identity via the single
 * {@code X-Forwarded-For} line, as literal CIDR entries (for example {@code 10.0.0.0/24} or
 * {@code 2001:db8::/32}). A single host is a {@code /32} (IPv4) or {@code /128} (IPv6) entry.
 * An empty list (the default) means forwarding input is never trusted and the raw socket
 * peer is always the client IP.
 *
 * <p>Entries must be IP literals: hostnames are rejected at startup before any matcher is
 * built, so configuration can never trigger DNS resolution. Matchers are compiled once at
 * configuration time, never per request.
 *
 * <p>Binding: {@code app.edge.trusted-proxies} (env {@code APP_EDGE_TRUSTED_PROXIES}, comma
 * separated) and {@code app.edge.max-forwarded-hops} (env {@code APP_EDGE_MAX_FORWARDED_HOPS}).
 */
@ConfigurationProperties(prefix = "app.edge")
public class EdgeProperties {

    private List<String> trustedProxies = new ArrayList<>();
    private int maxForwardedHops = 8;
    private volatile List<IpAddressMatcher> trustedMatchers = List.of();

    public List<String> getTrustedProxies() {
        return trustedProxies;
    }

    public void setTrustedProxies(List<String> trustedProxies) {
        if (trustedProxies == null) {
            this.trustedProxies = new ArrayList<>();
        } else {
            // Drop blank entries so an unset env placeholder binds to "no trusted proxies"
            // instead of failing startup on a phantom entry.
            List<String> cleaned = new ArrayList<>();
            for (String entry : trustedProxies) {
                if (entry != null && !entry.isBlank()) {
                    cleaned.add(entry.trim());
                }
            }
            this.trustedProxies = cleaned;
        }
        compileMatchers();
    }

    public int getMaxForwardedHops() {
        return maxForwardedHops;
    }

    public void setMaxForwardedHops(int maxForwardedHops) {
        this.maxForwardedHops = maxForwardedHops;
    }

    @PostConstruct
    void validate() {
        if (maxForwardedHops < 1 || maxForwardedHops > 32) {
            throw new IllegalStateException(
                    "app.edge.max-forwarded-hops must be between 1 and 32, was " + maxForwardedHops + ".");
        }
        compileMatchers();
    }

    private synchronized void compileMatchers() {
        List<IpAddressMatcher> compiled = new ArrayList<>(trustedProxies.size());
        for (String entry : trustedProxies) {
            requireLiteralCidr(entry);
            try {
                compiled.add(new IpAddressMatcher(entry));
            } catch (IllegalArgumentException exception) {
                throw new IllegalStateException(
                        "app.edge.trusted-proxies entry has an illegal mask: '" + entry + "'.", exception);
            }
        }
        this.trustedMatchers = List.copyOf(compiled);
    }

    private static void requireLiteralCidr(String entry) {
        String host = entry;
        String prefix = null;
        int slash = entry.indexOf('/');
        if (slash >= 0) {
            host = entry.substring(0, slash);
            prefix = entry.substring(slash + 1);
            if (prefix.isEmpty() || entry.indexOf('/', slash + 1) >= 0) {
                throw new IllegalStateException(
                        "app.edge.trusted-proxies entry is not a valid CIDR: '" + entry + "'.");
            }
        }
        if (!ClientIpAddresses.isIpLiteral(host)) {
            // Checked BEFORE any matcher construction: entries such as "localhost" or
            // "example.test" would otherwise reach InetAddress resolution (DNS). Rejected.
            throw new IllegalStateException(
                    "app.edge.trusted-proxies entry must be a literal IP or CIDR (no hostnames, no DNS): '"
                            + entry + "'.");
        }
        if (prefix != null) {
            int bits;
            try {
                bits = Integer.parseInt(prefix);
            } catch (NumberFormatException notNumeric) {
                throw new IllegalStateException(
                        "app.edge.trusted-proxies entry has a non-numeric mask: '" + entry + "'.");
            }
            int maxBits = host.indexOf(':') >= 0 ? 128 : 32;
            if (bits < 0 || bits > maxBits) {
                throw new IllegalStateException(
                        "app.edge.trusted-proxies entry has an out-of-range mask: '" + entry + "'.");
            }
        }
    }

    /**
     * Returns true only when the given address is a literal matching a precompiled trusted
     * proxy CIDR. Null, blank, and non-literal inputs return false. No DNS is performed and
     * no matcher is allocated per call.
     */
    public boolean isTrusted(String ip) {
        if (ip == null || ip.isBlank()) {
            return false;
        }
        String candidate = ip.trim();
        if (!ClientIpAddresses.isIpLiteral(candidate)) {
            return false;
        }
        for (IpAddressMatcher matcher : trustedMatchers) {
            if (matcher.matches(candidate)) {
                return true;
            }
        }
        return false;
    }
}
