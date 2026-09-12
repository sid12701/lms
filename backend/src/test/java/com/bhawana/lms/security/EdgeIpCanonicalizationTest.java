package com.bhawana.lms.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bhawana.lms.common.web.AmbiguousForwardingException;
import com.bhawana.lms.common.web.ClientIpAddresses;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for strict single-XFF trust-chain evaluation. No container involved; the
 * embedded-server tests prove the same code over real HTTP.
 */
class EdgeIpCanonicalizationTest {

  private static EdgeProperties trust(String... cidrs) {
    EdgeProperties properties = new EdgeProperties();
    properties.setTrustedProxies(List.of(cidrs));
    return properties;
  }

  private static String resolve(String peer, List<String> xffLines, EdgeProperties properties) {
    return ClientIpAddresses.resolveCanonical(
            peer, xffLines, properties::isTrusted, properties.getMaxForwardedHops());
  }

  private static void rejects(String peer, List<String> xffLines, EdgeProperties properties) {
    assertThrows(
            AmbiguousForwardingException.class,
            () -> resolve(peer, xffLines, properties));
  }

  @Test
  void untrustedPeerIgnoresAllForwardingInput() {
    EdgeProperties properties = trust("10.0.0.0/24");
    // Malformed, duplicate, and overlong input is still ignored: attribution stays peer.
    assertEquals("192.0.2.1", resolve("192.0.2.1", List.of("203.0.113.7"), properties));
    assertEquals("192.0.2.1", resolve("192.0.2.1", List.of("garbage!!!"), properties));
    assertEquals("192.0.2.1", resolve("192.0.2.1", List.of("a", "b"), properties));
    assertEquals("192.0.2.1", resolve("192.0.2.1", null, properties));
  }

  @Test
  void emptyTrustAlwaysYieldsPeer() {
    EdgeProperties properties = trust();
    assertEquals("192.0.2.1", resolve("192.0.2.1", List.of("203.0.113.7"), properties));
  }

  @Test
  void privateRangePeerIsNotImplicitlyTrusted() {
    EdgeProperties properties = trust();
    assertEquals("10.0.0.5", resolve("10.0.0.5", List.of("203.0.113.7"), properties));
  }

  @Test
  void trustedPeerWithNoHeaderIsRejected() {
    // The contract always sets exactly one line; absence from a trusted peer means
    // misconfiguration or bypass, and must not attribute the possibly allowlisted proxy.
    EdgeProperties properties = trust("10.0.0.0/24");
    rejects("10.0.0.9", null, properties);
    rejects("10.0.0.9", List.of(), properties);
  }

  @Test
  void trustedPeerHonorsSingleForwardedHop() {
    EdgeProperties properties = trust("10.0.0.0/24");
    assertEquals("203.0.113.7", resolve("10.0.0.9", List.of("203.0.113.7"), properties));
  }

  @Test
  void multiHopChainWalksRightToLeftPastTrustedProxies() {
    EdgeProperties properties = trust("10.0.0.0/24", "198.51.100.0/24");
    assertEquals(
            "203.0.113.7",
            resolve("10.0.0.9", List.of("203.0.113.7, 198.51.100.4"), properties));
  }

  @Test
  void spoofedLeftmostHopCannotOverrideRightmostUntrusted() {
    EdgeProperties properties = trust("10.0.0.0/24");
    assertEquals(
            "198.51.100.9",
            resolve("10.0.0.9", List.of("203.0.113.7, 198.51.100.9"), properties));
  }

  @Test
  void allTrustedHopsYieldLeftmost() {
    EdgeProperties properties = trust("10.0.0.0/24", "198.51.100.0/24");
    assertEquals(
            "203.0.113.7",
            resolve("10.0.0.9", List.of("203.0.113.7, 198.51.100.4"), properties));
  }

  @Test
  void malformedTrustedInputIsRejectedNotAttributedToPeer() {
    EdgeProperties properties = trust("10.0.0.0/24");
    rejects("10.0.0.9", List.of("203.0.113.7, not-an-ip!!!"), properties);
    rejects("10.0.0.9", List.of("203.0.113.7,, 198.51.100.4"), properties);
    rejects("10.0.0.9", List.of("203.0.113.7,"), properties);
    rejects("10.0.0.9", List.of("evil.example.com"), properties);
    rejects("10.0.0.9", List.of("203.0.113.7:1234"), properties);
    rejects("10.0.0.9", List.of("unknown"), properties);
    rejects("10.0.0.9", List.of("  "), properties);
  }

  @Test
  void duplicateHeaderLinesAreRejectedForTrustedPeer() {
    EdgeProperties properties = trust("10.0.0.0/24");
    rejects("10.0.0.9", List.of("203.0.113.7", "203.0.113.7"), properties);
    rejects("10.0.0.9", List.of("203.0.113.7", ""), properties);
  }

  @Test
  void overlongChainIsRejectedForTrustedPeer() {
    EdgeProperties properties = trust("10.0.0.0/24");
    properties.setMaxForwardedHops(2);
    rejects(
            "10.0.0.9", List.of("203.0.113.1, 203.0.113.2, 203.0.113.3"), properties);
  }

  @Test
  void chainAtExactlyMaxHopsIsHonored() {
    EdgeProperties properties = trust("10.0.0.0/24");
    properties.setMaxForwardedHops(2);
    // Two hops is within the bound, so the chain is evaluated: the peer is trusted and
    // the rightmost untrusted hop wins.
    assertEquals(
            "203.0.113.2",
            resolve("10.0.0.9", List.of("203.0.113.1, 203.0.113.2"), properties));
  }

  @Test
  void ipv6ClientAndTrustedIpv6Proxy() {
    EdgeProperties properties = trust("2001:db8::/32");
    String canonical = resolve("2001:db8::99", List.of("2001:db8:1::7"), properties);
    assertEquals(ClientIpAddresses.canonicalize("2001:db8:1::7"), canonical);
  }

  @Test
  void nullTrustPredicateFailsClosed() {
    assertEquals(
            "192.0.2.1",
            ClientIpAddresses.resolveCanonical("192.0.2.1", List.of("203.0.113.7"), null, 8));
  }

  @Test
  void invalidTrustConfigFailsStartup() {
    assertThrows(IllegalStateException.class, () -> trust("not-a-cidr"));
    assertThrows(IllegalStateException.class, () -> trust("10.0.0.0/33"));
    assertThrows(IllegalStateException.class, () -> trust("2001:db8::/129"));
    assertThrows(IllegalStateException.class, () -> trust("10.0.0.0/abc"));
    assertThrows(IllegalStateException.class, () -> trust("10.0.0.0/24/24"));
  }

  @Test
  void hostnameTrustEntriesAreRejectedWithoutDns() {
    // Must throw before any resolver is consulted: "localhost" may resolve locally and
    // "example.test" must never be looked up.
    assertThrows(IllegalStateException.class, () -> trust("localhost"));
    assertThrows(IllegalStateException.class, () -> trust("example.test"));
    assertThrows(IllegalStateException.class, () -> trust("proxy.internal/24"));
  }

  @Test
  void validIpv4AndIpv6CidrsAndHostsAreAccepted() {
    EdgeProperties properties = trust("10.0.0.0/24", "2001:db8::/32", "203.0.113.7", "::1/128");
    assertTrue(properties.isTrusted("10.0.0.9"));
    assertTrue(properties.isTrusted("2001:db8::99"));
    assertTrue(properties.isTrusted("203.0.113.7"));
    assertFalse(properties.isTrusted("203.0.113.8"));
    assertFalse(properties.isTrusted("192.0.2.1"));
    assertFalse(properties.isTrusted(null));
    assertFalse(properties.isTrusted("  "));
    assertFalse(properties.isTrusted("not-an-ip"));
    assertFalse(properties.isTrusted("example.test"));
  }

  @Test
  void invalidMaxHopsFailsStartup() {
    EdgeProperties zero = new EdgeProperties();
    zero.setMaxForwardedHops(0);
    assertThrows(IllegalStateException.class, zero::validate);

    EdgeProperties huge = new EdgeProperties();
    huge.setMaxForwardedHops(33);
    assertThrows(IllegalStateException.class, huge::validate);
  }

  @Test
  void blankTrustEntriesAreDropped() {
    EdgeProperties properties = new EdgeProperties();
    properties.setTrustedProxies(List.of("  ", ""));
    assertEquals("192.0.2.1", resolve("192.0.2.1", List.of("203.0.113.7"), properties));
  }
}
