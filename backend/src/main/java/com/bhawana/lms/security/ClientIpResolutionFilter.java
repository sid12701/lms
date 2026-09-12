package com.bhawana.lms.security;

import com.bhawana.lms.common.api.ApiError;
import com.bhawana.lms.common.correlation.CorrelationIdHolder;
import com.bhawana.lms.common.web.AmbiguousForwardingException;
import com.bhawana.lms.common.web.ClientIpAddresses;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Edge IP resolution filter.
 *
 * <p>Computes the canonical client IP once per request — from the raw socket peer plus the
 * explicitly configured trusted proxy chain over the strict single-{@code X-Forwarded-For}
 * contract — and publishes it as a request attribute read by
 * {@link ClientIpAddresses#resolve(HttpServletRequest)}. Runs before authentication so the
 * auth path, LSP allowlist filter, rate limiter, and audit all observe the same value.
 *
 * <p>Runtime behavior is explicit: an untrusted peer's forwarding input is ignored and the
 * peer is attributed; a trusted peer must present exactly one well-formed
 * {@code X-Forwarded-For} line — absent, malformed, or ambiguous input is rejected with
 * 400 before protected routing. It is never silently attributed to the proxy, which may
 * itself be an allowlisted address.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ClientIpResolutionFilter extends OncePerRequestFilter {

    private final EdgeProperties edgeProperties;
    private final ObjectMapper objectMapper;

    public ClientIpResolutionFilter(EdgeProperties edgeProperties, ObjectMapper objectMapper) {
        this.edgeProperties = edgeProperties;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        final String canonical;
        try {
            List<String> forwardedForLines =
                    Collections.list(request.getHeaders(ClientIpAddresses.FORWARDED_FOR_HEADER));
            canonical = ClientIpAddresses.resolveCanonical(
                    request.getRemoteAddr(),
                    forwardedForLines,
                    edgeProperties::isTrusted,
                    edgeProperties.getMaxForwardedHops()
            );
        } catch (AmbiguousForwardingException rejected) {
            writeApiError(
                    response,
                    HttpServletResponse.SC_BAD_REQUEST,
                    "INVALID_FORWARDED_HEADER",
                    "Forwarding headers from the edge proxy are malformed or ambiguous: "
                            + rejected.getMessage()
                            + " The edge must send a single X-Forwarded-For line of bare IP literals.",
                    request.getRequestURI()
            );
            return;
        }
        if (canonical != null) {
            request.setAttribute(ClientIpAddresses.CANONICAL_CLIENT_IP_ATTRIBUTE, canonical);
        }
        filterChain.doFilter(request, response);
    }

    private void writeApiError(
            HttpServletResponse response,
            int status,
            String code,
            String message,
            String path
    ) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        objectMapper.writeValue(response.getWriter(), ApiError.of(
                status,
                code,
                message,
                path,
                CorrelationIdHolder.get(),
                Map.of()
        ));
    }
}
