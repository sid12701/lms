package com.bhawana.lms.security;

import com.bhawana.lms.common.api.ApiError;
import com.bhawana.lms.common.api.PaginationResponseBuilder;
import com.bhawana.lms.common.correlation.CorrelationIdHolder;
import com.bhawana.lms.web.AuthenticationTenantScopeFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.XXssProtectionHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * HTTP security wiring: the stateless resource-server filter chain, route authorization, the
 * custom 401/403/428 JSON error writers, CORS, and registration of the LSP payload-size filter.
 */
@Configuration
public class SecurityFilterChainConfig {

    @Bean
    LspApiPayloadSizeFilter lspApiPayloadSizeFilter(ObjectMapper objectMapper) {
        return new LspApiPayloadSizeFilter(objectMapper);
    }

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            Converter<Jwt, ? extends AbstractAuthenticationToken> jwtAuthenticationConverter,
            ObjectMapper objectMapper,
            ObjectProvider<RateLimitFilter> rateLimitFilterProvider,
            LspSurfaceIpAllowlistFilter lspSurfaceIpAllowlistFilter,
            AuthenticationTenantScopeFilter authenticationTenantScopeFilter,
            LspApiPayloadSizeFilter lspApiPayloadSizeFilter
    ) throws Exception {
        RateLimitFilter rateLimitFilter = rateLimitFilterProvider.getIfAvailable();
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .headers(headers -> headers
                        .frameOptions(frame -> frame.deny())
                        .contentTypeOptions(Customizer.withDefaults())
                        .httpStrictTransportSecurity(hsts -> hsts
                                .maxAgeInSeconds(31536000)
                                .includeSubDomains(true))
                        .xssProtection(xss -> xss
                                .headerValue(XXssProtectionHeaderWriter.HeaderValue.ENABLED_MODE_BLOCK))
                        .contentSecurityPolicy(csp -> csp
                                .policyDirectives("default-src 'none'; frame-ancestors 'none'")))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(
                                "/actuator/health/**",
                                "/actuator/info",
                                "/error",
                                "/api/v1/auth/login",
                                "/api/v1/auth/token",
                                "/api/v1/auth/refresh",
                                "/api/v1/auth/logout"
                        ).permitAll()
                        // The OpenAPI document describes the full internal + LSP surface;
                        // serve it to authenticated principals only.
                        .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").authenticated()
                        .requestMatchers("/api/v1/auth/password").authenticated()
                        .requestMatchers("/api/v1/internal/system/context").authenticated()
                        .requestMatchers("/api/v1/**").access((authentication, context) -> {
                            var currentAuthentication = authentication.get();
                            boolean authenticated = currentAuthentication != null
                                    && currentAuthentication.isAuthenticated()
                                    && currentAuthentication.getAuthorities().stream()
                                    .noneMatch(authority -> "ROLE_ANONYMOUS".equals(authority.getAuthority()));
                            boolean passwordChangeRequired = currentAuthentication != null
                                    && currentAuthentication.getAuthorities().stream()
                                    .anyMatch(authority -> "ROLE_PASSWORD_CHANGE_REQUIRED".equals(authority.getAuthority()));
                            return new AuthorizationDecision(authenticated && !passwordChangeRequired);
                        })
                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))
                        .authenticationEntryPoint((request, response, authenticationException) -> {
                            ResolvedAuthError resolved = resolveAuthenticationError(authenticationException);
                            writeApiError(
                                    response,
                                    objectMapper,
                                    401,
                                    resolved.code(),
                                    resolved.message(),
                                    request.getRequestURI()
                            );
                        }))
                .exceptionHandling(ex -> ex
                        .accessDeniedHandler((request, response, accessDeniedException) -> {
                            boolean passwordChangeRequired = SecurityContextHolder.getContext().getAuthentication() != null
                                    && SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                                    .anyMatch(authority -> "ROLE_PASSWORD_CHANGE_REQUIRED".equals(authority.getAuthority()));
                            int status = passwordChangeRequired ? 428 : 403;
                            String code = passwordChangeRequired ? "PASSWORD_CHANGE_REQUIRED" : "ACCESS_DENIED";
                            String message = passwordChangeRequired
                                    ? "Password change is required before accessing internal routes"
                                    : "Access denied";

                            writeApiError(response, objectMapper, status, code, message, request.getRequestURI());
                        })
                        .authenticationEntryPoint((request, response, authenticationException) -> {
                            String code = "UNAUTHORIZED";
                            String message = "Authentication is required to access this resource.";
                            Throwable cause = authenticationException.getCause();
                            if (cause instanceof OAuth2AuthenticationException oauth2) {
                                String errorCode = oauth2.getError().getErrorCode();
                                if (errorCode != null && !errorCode.isBlank() && !"invalid_token".equals(errorCode)) {
                                    code = errorCode;
                                    message = oauth2.getError().getDescription();
                                }
                            }
                            writeApiError(response, objectMapper, 401, code, message, request.getRequestURI());
                        }))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .addFilterBefore(
                        lspApiPayloadSizeFilter,
                        org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter.class)
                .addFilterAfter(
                        authenticationTenantScopeFilter,
                        org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter.class)
                .addFilterAfter(lspSurfaceIpAllowlistFilter, org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter.class);

        if (rateLimitFilter != null) {
            http.addFilterAfter(
                    rateLimitFilter,
                    org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter.class);
        }

        return http.build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of(
                "http://localhost:5173",
                "http://127.0.0.1:5173",
                "http://localhost:4200",
                "http://127.0.0.1:4200"
        ));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key"));
        configuration.setExposedHeaders(List.of(
                "X-Correlation-Id",
                HttpHeaders.CONTENT_DISPOSITION,
                PaginationResponseBuilder.TOTAL_COUNT_HEADER,
                PaginationResponseBuilder.LIMIT_HEADER,
                PaginationResponseBuilder.OFFSET_HEADER
        ));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    private static ResolvedAuthError resolveAuthenticationError(AuthenticationException authenticationException) {
        Throwable current = authenticationException;
        while (current != null) {
            if (current instanceof OAuth2AuthenticationException oauth2) {
                OAuth2Error error = oauth2.getError();
                String code = error.getErrorCode();
                if (code != null && !code.isBlank() && !"invalid_token".equals(code)) {
                    return new ResolvedAuthError(code, error.getDescription());
                }
            }
            if (current instanceof JwtValidationException jwtValidation) {
                for (OAuth2Error error : jwtValidation.getErrors()) {
                    String code = error.getErrorCode();
                    if (code != null && !code.isBlank() && !"invalid_token".equals(code)) {
                        return new ResolvedAuthError(code, error.getDescription());
                    }
                }
            }
            current = current.getCause();
        }
        return new ResolvedAuthError(
                "UNAUTHORIZED",
                "Authentication is required to access this resource."
        );
    }

    private record ResolvedAuthError(String code, String message) {
    }

    private static void writeApiError(
            jakarta.servlet.http.HttpServletResponse response,
            ObjectMapper objectMapper,
            int status,
            String code,
            String message,
            String path
    ) throws java.io.IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        objectMapper.writeValue(response.getWriter(), ApiError.of(
                status,
                code,
                message,
                path,
                CorrelationIdHolder.get(),
                java.util.Map.of()
        ));
    }
}
