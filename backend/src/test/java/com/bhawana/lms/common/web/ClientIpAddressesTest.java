package com.bhawana.lms.common.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

class ClientIpAddressesTest {

  @Test
  void prefersFirstXForwardedForHop() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getHeader("X-Forwarded-For")).thenReturn("203.0.113.10, 10.0.0.1");
    when(request.getRemoteAddr()).thenReturn("127.0.0.1");

    assertEquals("203.0.113.10", ClientIpAddresses.resolve(request));
  }

  @Test
  void fallsBackToRemoteAddrWhenNoProxyHeaders() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getRemoteAddr()).thenReturn("10.0.0.50");

    assertEquals("10.0.0.50", ClientIpAddresses.resolve(request));
  }

  @Test
  void usesXRealIpWhenForwardedForMissing() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getHeader("X-Real-IP")).thenReturn("198.51.100.2");
    when(request.getRemoteAddr()).thenReturn("127.0.0.1");

    assertEquals("198.51.100.2", ClientIpAddresses.resolve(request));
  }
}
