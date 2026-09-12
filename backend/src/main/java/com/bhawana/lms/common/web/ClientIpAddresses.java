package com.bhawana.lms.common.web;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Canonical client IP resolution over a strict single-{@code X-Forwarded-For} contract.
 *
 * <p>Only {@code X-Forwarded-For} is ever evaluated, and only as exactly one header line
 * carrying bare IP literals. {@code X-Real-IP} and RFC 7239 {@code Forwarded} are never read:
 * the edge must strip them (with any client-supplied {@code X-Forwarded-For}) and set a
 * single {@code X-Forwarded-For} line itself. This keeps the implementation deliberately
 * small instead of growing a general header parser.
 *
 * <p>Headers are honoured only when an explicit trust chain has been established:
 * {@code server.forward-headers-strategy} must be {@code NONE} so
 * {@link HttpServletRequest#getRemoteAddr()} is the raw socket peer, and the peer must fall
 * inside the explicitly configured trusted proxy CIDRs. There is no implicit private-range
 * trust. When the peer is not a configured trusted proxy, all forwarding input is ignored
 * and the peer itself is the client IP.
 *
 * <p>When the peer IS trusted, forwarding input must be present and well-formed: a
 * missing {@code X-Forwarded-For} line, like malformed or ambiguous input (non-literal
 * hop, empty segment, chain longer than the configured bound, blank value, or duplicate
 * header lines), throws {@link AmbiguousForwardingException}: the caller must reject the
 * request (400) before protected routing. Attributing the proxy itself would authorize
 * an unidentified client as the proxy, which may be an allowlisted address. The
 * documented ingress contract always sets exactly one XFF line, so absence from a
 * trusted peer means misconfiguration or edge bypass, never a direct client.
 *
 * <p>The canonical IP is computed once per request by the edge resolution filter and stored
 * under {@link #CANONICAL_CLIENT_IP_ATTRIBUTE}; {@link #resolve(HttpServletRequest)} prefers
 * that attribute so authentication, LSP allowlist, rate limiting, and audit all share one
 * value. Outside the filter (for example mock-request unit tests) {@code resolve} falls back
 * to the canonicalized remote address and ignores forwarding headers.
 *
 * <p>No DNS lookups are performed anywhere: only IP literals are accepted.
 */
public final class ClientIpAddresses {

    /** Request attribute holding the canonical client IP set by the edge resolution filter. */
    public static final String CANONICAL_CLIENT_IP_ATTRIBUTE = "lms.canonicalClientIp";

    /** Header carrying the edge-asserted client chain. Exactly one line is accepted. */
    public static final String FORWARDED_FOR_HEADER = "X-Forwarded-For";

    private static final Pattern IPV4_LITERAL = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");
    private static final Pattern IPV6_LITERAL = Pattern.compile("[0-9a-fA-F:.]+");

    private ClientIpAddresses() {}

    /**
     * Resolves the canonical client IP shared by auth, allowlist, rate limiting, and audit.
     * Prefers the filter-computed attribute; otherwise canonicalizes the raw socket peer and
     * ignores forwarding headers.
     */
    public static String resolve(HttpServletRequest request) {
        Object canonical = request.getAttribute(CANONICAL_CLIENT_IP_ATTRIBUTE);
        if (canonical instanceof String value && !value.isBlank()) {
            return value;
        }
        return canonicalize(request.getRemoteAddr());
    }

    /**
     * Pure trust-chain evaluation used by the edge resolution filter.
     *
     * @param peer raw socket peer ({@code getRemoteAddr()} with native forwarding disabled)
     * @param xForwardedForLines every {@code X-Forwarded-For} header line in wire order, may
     *     be null or empty when the header is absent
     * @param isTrustedProxy predicate over canonical address text; fail-closed on false
     * @param maxHops bound on accepted forwarding hops; overlong trusted chains are rejected
     * @return canonical client IP; null only when the peer itself is blank
     * @throws AmbiguousForwardingException when the peer is trusted and forwarding input
     *     is absent, malformed, or ambiguous (duplicate lines, blank value, empty segment,
     *     non-literal hop, or overlong chain)
     */
    public static String resolveCanonical(
            String peer,
            List<String> xForwardedForLines,
            Predicate<String> isTrustedProxy,
            int maxHops
    ) {
        String canonicalPeer = canonicalize(peer);
        if (canonicalPeer == null) {
            return null;
        }
        Predicate<String> trust = isTrustedProxy != null ? isTrustedProxy : ignored -> false;
        int bound = maxHops < 1 ? 1 : Math.min(maxHops, 32);

        // Untrusted peer: every forwarding byte is attacker-controlled. Ignore it all —
        // including duplicates and malformed values — and attribute the socket peer.
        if (!trust.test(canonicalPeer)) {
            return canonicalPeer;
        }

        if (xForwardedForLines == null || xForwardedForLines.isEmpty()) {
            // A trusted edge always asserts exactly one line; absence means the proxy is
            // misconfigured or bypassed. Attributing the proxy itself would authorize an
            // unidentified client as a possibly allowlisted address: reject instead.
            throw new AmbiguousForwardingException(
                    "Missing X-Forwarded-For from a trusted peer is rejected.");
        }
        if (xForwardedForLines.size() > 1) {
            // A second field may carry the real client while the first stays
            // attacker-controlled (or vice versa); the fields are ambiguous as a set.
            throw new AmbiguousForwardingException(
                    "Multiple X-Forwarded-For header lines are ambiguous and rejected.");
        }
        String headerValue = xForwardedForLines.get(0);
        if (headerValue == null || headerValue.isBlank()) {
            throw new AmbiguousForwardingException(
                    "Blank X-Forwarded-For from a trusted peer is malformed and rejected.");
        }
        List<String> hops = splitHops(headerValue, bound);
        return walkRightToLeft(hops, canonicalPeer, trust);
    }

    private static String walkRightToLeft(
            List<String> hops, String canonicalPeer, Predicate<String> trust) {
        List<String> chain = new ArrayList<>(hops.size() + 1);
        for (String hop : hops) {
            // Hops were validated as literals by splitHops; canonicalize only normalizes.
            chain.add(canonicalize(hop));
        }
        chain.add(canonicalPeer);
        int index = chain.size() - 1;
        while (index > 0 && trust.test(chain.get(index))) {
            index--;
        }
        return chain.get(index);
    }

    private static List<String> splitHops(String headerValue, int bound) {
        String[] parts = headerValue.split(",", -1);
        if (parts.length > bound) {
            throw new AmbiguousForwardingException(
                    "X-Forwarded-For chain exceeds the configured bound of " + bound + " hops.");
        }
        List<String> hops = new ArrayList<>(parts.length);
        for (String part : parts) {
            String hop = part.trim();
            if (hop.isEmpty()) {
                throw new AmbiguousForwardingException(
                        "Empty X-Forwarded-For segment is malformed and rejected.");
            }
            if (!isIpLiteral(hop)) {
                // Hostnames would need DNS and ports/obfuscation are outside the strict
                // single-XFF bare-literal contract: reject, do not skip.
                throw new AmbiguousForwardingException(
                        "Non-literal X-Forwarded-For hop is malformed and rejected.");
            }
            hops.add(hop);
        }
        if (hops.isEmpty()) {
            throw new AmbiguousForwardingException(
                    "Empty X-Forwarded-For value is malformed and rejected.");
        }
        return hops;
    }

    /**
     * Normalizes an IP literal (IPv4/IPv6) to its canonical textual form without DNS. Hostnames
     * and malformed values yield the trimmed raw input (opaque, fails closed downstream) or
     * null for blank input. Use {@link #isIpLiteral(String)} when strictness is required.
     */
    public static String canonicalize(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return null;
        }
        try {
            if (isIpLiteral(value)) {
                // Literal input only: no DNS resolution is triggered for IP literals.
                InetAddress address = InetAddress.getByName(value);
                return address.getHostAddress();
            }
        } catch (UnknownHostException | SecurityException ignored) {
            // Fall through to the opaque raw value: fail closed downstream.
        }
        return value;
    }

    /**
     * Returns true only for IPv4/IPv6 literals. Hostnames (which would need DNS) are false.
     * Rejects ambiguous forms: out-of-range IPv4 octets, leading-zero octets (octal vs
     * decimal differs between parsers), multiple IPv6 compression markers, and zone ids.
     */
    public static boolean isIpLiteral(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        if (value.indexOf(':') >= 0) {
            return IPV6_LITERAL.matcher(value).matches() && isParsableIPv6(value);
        }
        if (!IPV4_LITERAL.matcher(value).matches()) {
            return false;
        }
        for (String octet : value.split("\\.", -1)) {
            int parsed;
            try {
                parsed = Integer.parseInt(octet);
            } catch (NumberFormatException notNumeric) {
                return false;
            }
            if (parsed < 0 || parsed > 255 || (octet.length() > 1 && octet.startsWith("0"))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isParsableIPv6(String value) {
        int compression = value.indexOf("::");
        if (compression >= 0 && value.indexOf("::", compression + 2) >= 0) {
            return false;
        }
        if (value.indexOf('%') >= 0) {
            return false;
        }
        try {
            return InetAddress.getByName(value) != null;
        } catch (UnknownHostException | SecurityException notAnAddress) {
            return false;
        }
    }
}
