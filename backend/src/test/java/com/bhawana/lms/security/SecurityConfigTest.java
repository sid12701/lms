package com.bhawana.lms.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.cors.CorsConfiguration;

class SecurityConfigTest {

    @Test
    void corsExposesContentDispositionSoUiCanPreserveDownloadFilenames() {
        CorsConfiguration configuration = new SecurityConfig()
                .corsConfigurationSource()
                .getCorsConfiguration(new MockHttpServletRequest(
                        "GET",
                        "/api/v1/internal/ops/loan-applications/app-1/kyc-documents/PAN_CARD/content"
                ));

        assertThat(configuration).isNotNull();
        assertThat(configuration.getExposedHeaders()).contains(HttpHeaders.CONTENT_DISPOSITION);
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
