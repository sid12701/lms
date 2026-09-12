package com.bhawana.lms.common.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

/**
 * Forwarding headers are untrusted by default. {@link ClientIpAddresses#resolve} prefers
 * the filter-computed canonical attribute and otherwise falls back to the socket peer,
 * ignoring {@code X-Forwarded-For}/{@code X-Real-IP}. Header-trust evaluation lives in
 * {@code resolveCanonical} (see the edge canonicalization tests) and the embedded-server
 * tests; MockHttpServletRequest alone cannot prove container behavior.
 */
class ClientIpAddressesTest {

  @Test
  void prefersCanonicalAttributeOverEverything() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getAttribute(ClientIpAddresses.CANONICAL_CLIENT_IP_ATTRIBUTE)).thenReturn("203.0.113.10");
    when(request.getHeader("X-Forwarded-For")).thenReturn("198.51.100.9");
    when(request.getRemoteAddr()).thenReturn("127.0.0.1");

    assertEquals("203.0.113.10", ClientIpAddresses.resolve(request));
  }

  @Test
  void ignoresForwardingHeadersWithoutCanonicalAttribute() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getHeader("X-Forwarded-For")).thenReturn("203.0.113.10, 10.0.0.1");
    when(request.getHeader("X-Real-IP")).thenReturn("198.51.100.2");
    when(request.getRemoteAddr()).thenReturn("10.0.0.50");

    assertEquals("10.0.0.50", ClientIpAddresses.resolve(request));
  }

  @Test
  void fallsBackToRemoteAddrWhenNoProxyHeaders() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getRemoteAddr()).thenReturn("10.0.0.50");

    assertEquals("10.0.0.50", ClientIpAddresses.resolve(request));
  }

  @Test
  void blankAttributeFallsBackToPeer() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getAttribute(ClientIpAddresses.CANONICAL_CLIENT_IP_ATTRIBUTE)).thenReturn("  ");
    when(request.getRemoteAddr()).thenReturn("10.0.0.50");

    assertEquals("10.0.0.50", ClientIpAddresses.resolve(request));
  }

  @Test
  void nullPeerResolvesToNull() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getRemoteAddr()).thenReturn(null);

    assertNull(ClientIpAddresses.resolve(request));
  }
}
