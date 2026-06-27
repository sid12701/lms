package com.bhawana.lms.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bhawana.lms.common.api.error.PayloadTooLargeException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class LspApiPayloadSizeFilterTest {

    private final LspApiPayloadSizeFilter filter =
            new LspApiPayloadSizeFilter(new ObjectMapper().findAndRegisterModules());

    @Test
    void declaredContentLengthOverCapIsRejectedBeforeDispatch() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/lsp/loan-applications") {
            @Override
            public long getContentLengthLong() {
                return LspApiPayloadSizeFilter.MAX_JSON_BYTES + 1;
            }
        };
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(413, response.getStatus());
        assertTrue(response.getContentAsString().contains("PAYLOAD_TOO_LARGE"));
        assertNull(chain.getRequest(), "oversized request must not reach the chain");
    }

    @Test
    void chunkedBodyOverCapIsEnforcedWhileReading() throws Exception {
        byte[] oversized = new byte[(int) LspApiPayloadSizeFilter.MAX_JSON_BYTES + 16];
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/lsp/loan-applications") {
            @Override
            public long getContentLengthLong() {
                return -1; // chunked: no declared length
            }
        };
        request.setContent(oversized);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        ServletInputStream body = ((HttpServletRequest) chain.getRequest()).getInputStream();
        assertThrows(PayloadTooLargeException.class, body::readAllBytes);
    }

    @Test
    void bodyWithinCapPassesThroughAndStaysReadable() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/lsp/loan-applications");
        request.setContent("{\"ok\":true}".getBytes());
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        ServletInputStream body = ((HttpServletRequest) chain.getRequest()).getInputStream();
        assertEquals("{\"ok\":true}", new String(body.readAllBytes()));
    }

    @Test
    void nonLspRequestIsNotWrapped() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/ops/loan-applications");
        request.setContent("{\"ok\":true}".getBytes());
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertSame(request, chain.getRequest(), "non-LSP requests must pass through untouched");
    }
}
