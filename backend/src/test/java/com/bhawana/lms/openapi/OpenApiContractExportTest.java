package com.bhawana.lms.openapi;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * OpenAPI contract snapshot used by the frontend {@code openapi-typescript} pipeline (F2).
 * Regenerate: {@code ./mvnw test -Dtest=OpenApiContractExportTest#exportOpenApiSnapshot -Dopenapi.export=true}
 *
 * <p>The document describes the full internal + LSP surface, so /v3/api-docs requires
 * authentication (see SecurityFilterChainConfig).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "springdoc.api-docs.enabled=true",
        "springdoc.swagger-ui.enabled=false"
})
class OpenApiContractExportTest {

    private static final Path OPENAPI_SNAPSHOT = Path.of("..", "openapi", "openapi.json");

    @Autowired
    private MockMvc mockMvc;

    @Test
    void apiDocsRequireAuthentication() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void apiDocsEndpointReturnsOpenApiDocument() throws Exception {
        mockMvc.perform(get("/v3/api-docs").with(systemAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi").exists())
                .andExpect(jsonPath("$.paths['/api/v1/internal/ops/loan-applications/{applicationId}']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/lsp/loan-applications']").exists());
    }

    @Test
    @EnabledIfSystemProperty(named = "openapi.export", matches = "true")
    void exportOpenApiSnapshot() throws Exception {
        MvcResult result = mockMvc.perform(get("/v3/api-docs").with(systemAdmin())).andReturn();
        Files.createDirectories(OPENAPI_SNAPSHOT.getParent());
        Files.writeString(OPENAPI_SNAPSHOT, result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor systemAdmin() {
        return jwt().jwt(token -> token.subject("ops.admin").claim("roles", List.of("SYSTEM_ADMIN")))
                .authorities(() -> "ROLE_SYSTEM_ADMIN");
    }
}
