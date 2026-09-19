package com.bhawana.lms.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bhawana.lms.common.api.PaginationResponseBuilder;
import com.bhawana.lms.common.correlation.CorrelationIdFilter;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.DefaultCorsProcessor;

class SecurityConfigTest {

    private static final String SPA_ORIGIN = "https://ops.bhawana.example";

    private static SecurityProperties securityProperties(String... allowedOrigins) {
        SecurityProperties properties = new SecurityProperties();
        properties.getCors().setAllowedOrigins(List.of(allowedOrigins));
        return properties;
    }

    private static CorsConfiguration corsConfiguration(String... allowedOrigins) {
        return new SecurityFilterChainConfig()
                .corsConfigurationSource(securityProperties(allowedOrigins))
                .getCorsConfiguration(new MockHttpServletRequest(
                        "GET",
                        "/api/v1/internal/ops/loan-applications/app-1/kyc-documents/PAN_CARD/content"
                ));
    }

    @Test
    void corsExposesContentDispositionSoUiCanPreserveDownloadFilenames() {
        CorsConfiguration configuration = corsConfiguration(SPA_ORIGIN);

        assertThat(configuration).isNotNull();
        assertThat(configuration.getExposedHeaders()).contains(HttpHeaders.CONTENT_DISPOSITION);
    }

    @Test
    void corsExposesRetryAfterPaginationAndCorrelationHeadersForCrossOriginSpa() {
        CorsConfiguration configuration = corsConfiguration(SPA_ORIGIN);

        // The SPA reads the retry contract (429 rate-limit / 409 IDEMPOTENCY_IN_PROGRESS),
        // pagination metadata and the correlation id off cross-origin responses; each must
        // be listed or the browser hides it from fetch.
        assertThat(configuration.getExposedHeaders()).contains(
                HttpHeaders.RETRY_AFTER,
                CorrelationIdFilter.HEADER_NAME,
                PaginationResponseBuilder.TOTAL_COUNT_HEADER,
                PaginationResponseBuilder.LIMIT_HEADER,
                PaginationResponseBuilder.OFFSET_HEADER
        );
    }

    @Test
    void allowedOriginPreflightSucceedsWithCredentials() throws Exception {
        CorsConfiguration configuration = corsConfiguration(SPA_ORIGIN);
        MockHttpServletRequest preflight = new MockHttpServletRequest(
                "OPTIONS", "/api/v1/internal/reports/portfolio-mis/summary");
        preflight.addHeader(HttpHeaders.ORIGIN, SPA_ORIGIN);
        preflight.addHeader(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET");
        preflight.addHeader(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization, idempotency-key");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean accepted = new DefaultCorsProcessor().processRequest(configuration, preflight, response);

        assertThat(accepted).isTrue();
        assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isEqualTo(SPA_ORIGIN);
        assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS)).isEqualTo("true");
    }

    @Test
    void credentialedRequestFromAllowedOriginSeesContractHeaders() throws Exception {
        CorsConfiguration configuration = corsConfiguration(SPA_ORIGIN);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/api/v1/internal/reports/portfolio-mis/summary");
        request.addHeader(HttpHeaders.ORIGIN, SPA_ORIGIN);
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean accepted = new DefaultCorsProcessor().processRequest(configuration, request, response);

        assertThat(accepted).isTrue();
        assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isEqualTo(SPA_ORIGIN);
        assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS)).isEqualTo("true");
        assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS))
                .contains(HttpHeaders.RETRY_AFTER)
                .contains(PaginationResponseBuilder.TOTAL_COUNT_HEADER)
                .contains(CorrelationIdFilter.HEADER_NAME);
    }

    @Test
    void unapprovedOriginPreflightFails() throws Exception {
        CorsConfiguration configuration = corsConfiguration(SPA_ORIGIN);
        MockHttpServletRequest preflight = new MockHttpServletRequest(
                "OPTIONS", "/api/v1/internal/reports/portfolio-mis/summary");
        preflight.addHeader(HttpHeaders.ORIGIN, "https://evil.example");
        preflight.addHeader(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean accepted = new DefaultCorsProcessor().processRequest(configuration, preflight, response);

        assertThat(accepted).isFalse();
        assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isNull();
    }

    @Test
    void emptyAllowlistFailsClosedOnEveryOrigin() throws Exception {
        // Unset/blank property outside local means no cross-origin browser access at all.
        CorsConfiguration configuration = corsConfiguration();
        assertThat(configuration.getAllowedOrigins()).isEmpty();

        MockHttpServletRequest preflight = new MockHttpServletRequest(
                "OPTIONS", "/api/v1/auth/refresh");
        preflight.addHeader(HttpHeaders.ORIGIN, SPA_ORIGIN);
        preflight.addHeader(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean accepted = new DefaultCorsProcessor().processRequest(configuration, preflight, response);

        assertThat(accepted).isFalse();
        assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isNull();
    }

    @Test
    void blankPlaceholderEntriesBindToNoOrigins() {
        // An unset env placeholder resolves to a blank entry, which must clean to "no
        // allowed origins" instead of a phantom blank origin (EdgeProperties convention).
        SecurityProperties properties = securityProperties("", "   ");
        assertThat(properties.getCors().getAllowedOrigins()).isEmpty();
    }

    @Test
    void wildcardOriginIsRejectedAtStartup() {
        // Credentialed CORS must never silently allow every origin.
        assertThatThrownBy(() -> new SecurityFilterChainConfig()
                .corsConfigurationSource(securityProperties("*")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.security.cors.allowed-origins");
    }

    @Test
    void authenticationManagerAuthenticatesKnownUserPassword() {
        PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
        String encodedPassword = passwordEncoder.encode("secret");
        UserDetailsService userDetailsService = username -> {
            if ("alice".equals(username)) {
                return User.builder()
                        .username("alice")
                        .password(encodedPassword)
                        .roles("USER")
                        .build();
            }
            throw new UsernameNotFoundException(username);
        };

        AuthenticationManager authenticationManager =
                new SecurityConfig().authenticationManager(userDetailsService, passwordEncoder);

        var authentication = authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated("alice", "secret")
        );

        assertThat(authentication.isAuthenticated()).isTrue();
        assertThat(authentication.getName()).isEqualTo("alice");
    }
}
