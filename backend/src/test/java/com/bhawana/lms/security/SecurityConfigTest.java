package com.bhawana.lms.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
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
}
