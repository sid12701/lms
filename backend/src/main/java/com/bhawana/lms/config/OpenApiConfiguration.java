package com.bhawana.lms.config;

import com.bhawana.lms.security.RateLimitProperties;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Contact;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI wiring (M13): the shared {@code bearerAuth} scheme, the partner-only
 * {@code /v3/api-docs/partner} group, and the global contract customizer that adds
 * per-operation security, the ApiError schema, and route-specific error/header
 * documentation to every document springdoc serves.
 *
 * <p>{@code /v3/api-docs} keeps describing the full internal + LSP surface (that is
 * the document exported to {@code openapi/openapi.json}); {@code /v3/api-docs/partner}
 * publishes only the supported LSP and auth routes for integrators. Both are behind
 * the security filter chain's authenticated {@code /v3/api-docs/**} rule.
 */
@Configuration
@OpenAPIDefinition(
        info = @Info(
                title = "Bhawana LMS API",
                version = "v1",
                description = "Foundation APIs for internal LMS operations and LSP integrations.",
                contact = @Contact(name = "Bhawana LMS")
        )
)
@SecurityScheme(
        name = "bearerAuth",
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT",
        in = SecuritySchemeIn.HEADER
)
public class OpenApiConfiguration {

    @Bean
    GroupedOpenApi partnerOpenApiGroup() {
        return GroupedOpenApi.builder()
                .group("partner")
                .pathsToMatch("/api/v1/lsp/**", "/api/v1/auth/**")
                .addOpenApiCustomizer(OpenApiContractCustomizer.partnerInfoCustomizer())
                .build();
    }

    @Bean
    GlobalOpenApiCustomizer openApiContractCustomizer(RateLimitProperties rateLimitProperties) {
        return new OpenApiContractCustomizer(rateLimitProperties);
    }
}
