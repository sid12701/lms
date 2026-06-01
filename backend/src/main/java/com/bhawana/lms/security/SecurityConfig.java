package com.bhawana.lms.security;

import com.bhawana.lms.common.api.ApiError;
import com.bhawana.lms.common.correlation.CorrelationIdHolder;
import com.bhawana.lms.common.web.PaginationResponseBuilder;
import com.bhawana.lms.domain.AppRole;
import com.bhawana.lms.domain.AppUser;
import com.bhawana.lms.domain.UserStatus;
import com.bhawana.lms.repo.AppUserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.header.writers.XXssProtectionHeaderWriter;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    UserDetailsService userDetailsService(
            SecurityProperties securityProperties,
            AppUserRepository appUserRepository,
            PasswordEncoder passwordEncoder
    ) {
        SecurityProperties.BootstrapUser bootstrapUser = securityProperties.getBootstrapUser();
        UserDetails bootstrapUserDetails = bootstrapUserDetails(bootstrapUser, passwordEncoder);

        return username -> {
            return appUserRepository.findByUsername(username)
                    .map(SecurityConfig::appUserDetails)
                    .orElseGet(() -> {
                        if (bootstrapUser.getUsername().equalsIgnoreCase(username)) {
                            return bootstrapUserDetails;
                        }
                        throw new UsernameNotFoundException(username);
                    });
        };
    }

    @Bean
    AuthenticationManager authenticationManager(UserDetailsService userDetailsService, PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(provider);
    }

    @Bean
    SecretKey jwtSigningKey(SecurityProperties securityProperties) {
        byte[] keyBytes = securityProperties.getJwt().getSecret().getBytes(StandardCharsets.UTF_8);
        return new SecretKeySpec(keyBytes, "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey jwtSigningKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(jwtSigningKey));
    }

    @Bean
    JwtDecoder jwtDecoder(
            SecretKey jwtSigningKey,
            AppUserRepository appUserRepository,
            ApiClientJwtSessionValidator apiClientJwtSessionValidator,
            SecurityProperties securityProperties
    ) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(jwtSigningKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(securityProperties.getJwt().getIssuer()),
                managedUserSessionValidator(appUserRepository),
                apiClientJwtSessionValidator
        ));
        return decoder;
    }

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            Converter<Jwt, ? extends AbstractAuthenticationToken> jwtAuthenticationConverter,
            ObjectMapper objectMapper,
            ObjectProvider<RateLimitFilter> rateLimitFilterProvider,
            LspSurfaceIpAllowlistFilter lspSurfaceIpAllowlistFilter
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
                                "/swagger-ui.html",
                                "/swagger-ui/**",
                                "/v3/api-docs/**",
                                "/api/v1/auth/login",
                                "/api/v1/auth/token",
                                "/api/v1/auth/refresh",
                                "/api/v1/auth/logout"
                        ).permitAll()
                        .requestMatchers("/api/v1/auth/password").authenticated()
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
                .addFilterAfter(lspSurfaceIpAllowlistFilter, org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter.class);

        if (rateLimitFilter != null) {
            http.addFilterAfter(rateLimitFilter, org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter.class);
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

    @Bean
    Converter<Jwt, ? extends AbstractAuthenticationToken> jwtAuthenticationConverter(AppUserRepository appUserRepository) {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(grantedAuthoritiesConverter(appUserRepository));
        return converter;
    }

    private OAuth2TokenValidator<Jwt> managedUserSessionValidator(AppUserRepository appUserRepository) {
        return jwt -> {
            if (ApiClientJwtSessionValidator.AUTH_TYPE_API_CLIENT.equals(
                    jwt.getClaimAsString(ApiClientJwtSessionValidator.AUTH_TYPE_CLAIM)
            )) {
                return OAuth2TokenValidatorResult.success();
            }

            String username = jwt.getSubject();
            if (username == null || username.isBlank()) {
                return OAuth2TokenValidatorResult.success();
            }

            return appUserRepository.findByUsername(username)
                    .map(appUser -> {
                        Long tokenPasswordVersion = jwt.getClaim("pwdv");
                        long currentPasswordVersion = appUser.getPasswordChangedAt().toEpochMilli();
                        if (tokenPasswordVersion == null || tokenPasswordVersion.longValue() != currentPasswordVersion) {
                            return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                                    "invalid_token",
                                    "Password has changed",
                                    null
                            ));
                        }

                        Long tokenSessionVersion = jwt.getClaim("tv");
                        long currentSessionVersion = appUser.getTokenVersion();
                        long effectiveTokenSessionVersion = tokenSessionVersion == null ? 0L : tokenSessionVersion.longValue();
                        if (effectiveTokenSessionVersion != currentSessionVersion) {
                            return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                                    "invalid_token",
                                    "Session is no longer valid",
                                    null
                            ));
                        }

                        return OAuth2TokenValidatorResult.success();
                    })
                    .orElseGet(OAuth2TokenValidatorResult::success);
        };
    }

    private Converter<Jwt, Collection<GrantedAuthority>> grantedAuthoritiesConverter(AppUserRepository appUserRepository) {
        return jwt -> {
            List<GrantedAuthority> authorities = new ArrayList<>();
            List<String> roles = jwt.getClaimAsStringList("roles");
            if (roles != null) {
                authorities.addAll(roles.stream()
                        .map(role -> role.startsWith("ROLE_") ? role : "ROLE_" + role)
                        .map(SimpleGrantedAuthority::new)
                        .map(GrantedAuthority.class::cast)
                        .toList());
            }

            appUserRepository.findByUsername(jwt.getSubject())
                    .filter(AppUser::isPasswordChangeRequired)
                    .ifPresent(appUser -> authorities.add(new SimpleGrantedAuthority("ROLE_PASSWORD_CHANGE_REQUIRED")));

            return authorities;
        };
    }

    private static UserDetails bootstrapUserDetails(SecurityProperties.BootstrapUser bootstrapUser, PasswordEncoder passwordEncoder) {
        List<String> roles = bootstrapUser.getRoles().stream()
                .map(role -> role.startsWith("ROLE_") ? role.substring("ROLE_".length()) : role)
                .toList();

        return User.builder()
                .username(bootstrapUser.getUsername())
                .password(passwordEncoder.encode(bootstrapUser.getPassword()))
                .roles(roles.toArray(String[]::new))
                .disabled(false)
                .accountExpired(false)
                .accountLocked(false)
                .credentialsExpired(false)
                .build();
    }

    private static UserDetails appUserDetails(AppUser appUser) {
        List<String> roles = appUser.getRoles().stream()
                .map(AppRole::getCode)
                .map(Enum::name)
                .toList();

        boolean enabled = appUser.getStatus() == UserStatus.ACTIVE;
        return User.builder()
                .username(appUser.getUsername())
                .password(appUser.getPasswordHash())
                .roles(roles.toArray(String[]::new))
                .disabled(!enabled)
                .accountExpired(false)
                .accountLocked(false)
                .credentialsExpired(false)
                .build();
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
