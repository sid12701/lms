package com.bhawana.lms.security;

import com.bhawana.lms.common.api.ApiError;
import com.bhawana.lms.common.api.error.PayloadTooLargeException;
import com.bhawana.lms.common.correlation.CorrelationIdHolder;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rejects oversized JSON bodies on the LSP API surface before controller dispatch.
 * Registered in {@link SecurityConfig} only (not a standalone servlet filter).
 *
 * <p>A declared {@code Content-Length} over the cap is rejected up front. Because chunked uploads
 * carry no {@code Content-Length}, the request body is also wrapped so the cap is enforced as the
 * stream is consumed downstream — this guards against under-declared or chunked bodies without
 * eagerly buffering anything (the stream is only read by an authenticated controller, so an
 * unauthenticated request is still rejected by the bearer filter before any body is touched).
 */
public class LspApiPayloadSizeFilter extends OncePerRequestFilter {

    static final long MAX_JSON_BYTES = 10L * 1024 * 1024;
    private static final String LSP_PREFIX = "/api/v1/lsp/";

    private final ObjectMapper objectMapper;

    public LspApiPayloadSizeFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod()) && !"PUT".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI();
        return path == null || !path.startsWith(LSP_PREFIX);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if (request.getContentLengthLong() > MAX_JSON_BYTES) {
            writePayloadTooLarge(response, request.getRequestURI());
            return;
        }
        filterChain.doFilter(new SizeLimitingRequestWrapper(request, MAX_JSON_BYTES), response);
    }

    private void writePayloadTooLarge(HttpServletResponse response, String requestUri) throws IOException {
        response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        ApiError body = ApiError.of(
                HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE,
                "PAYLOAD_TOO_LARGE",
                "Request body exceeds the maximum permitted size.",
                requestUri,
                CorrelationIdHolder.get(),
                Map.of()
        );
        objectMapper.writeValue(response.getOutputStream(), body);
    }

    /** Wraps the request so the body cannot be read past {@code maxBytes}. */
    private static final class SizeLimitingRequestWrapper extends HttpServletRequestWrapper {

        private final long maxBytes;

        SizeLimitingRequestWrapper(HttpServletRequest request, long maxBytes) {
            super(request);
            this.maxBytes = maxBytes;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            return new SizeLimitingServletInputStream(super.getInputStream(), maxBytes);
        }

        @Override
        public BufferedReader getReader() throws IOException {
            Charset charset = getCharacterEncoding() == null
                    ? StandardCharsets.UTF_8
                    : Charset.forName(getCharacterEncoding());
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }
    }

    /** Counts bytes as they are read and fails once the cap is exceeded. */
    private static final class SizeLimitingServletInputStream extends ServletInputStream {

        private final ServletInputStream delegate;
        private final long maxBytes;
        private long bytesRead;

        SizeLimitingServletInputStream(ServletInputStream delegate, long maxBytes) {
            this.delegate = delegate;
            this.maxBytes = maxBytes;
        }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value != -1) {
                count(1);
            }
            return value;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int read = delegate.read(b, off, len);
            if (read > 0) {
                count(read);
            }
            return read;
        }

        private void count(int read) {
            bytesRead += read;
            if (bytesRead > maxBytes) {
                throw new PayloadTooLargeException("Request body exceeds the maximum permitted size.");
            }
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            delegate.setReadListener(readListener);
        }

        @Override
        public int available() throws IOException {
            return delegate.available();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }
}
