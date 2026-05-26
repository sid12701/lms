package com.bhawana.lms.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.nullValue;

import com.bhawana.lms.repo.ApiClientAuditEventRepository;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApiClientAdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ApiClientAuditEventRepository apiClientAuditEventRepository;

    @Test
    void systemAdminCanCreateAndListApiClients() throws Exception {
        String lspCode = "APX" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();

        MvcResult lspResult = mockMvc.perform(post("/api/v1/internal/admin/lsps")
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", lspCode,
                                "name", "Apex Finance",
                                "status", "ACTIVE"
                        ))))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode lspJson = objectMapper.readTree(lspResult.getResponse().getContentAsString());
        String lspId = lspJson.get("id").asText();

        MvcResult createdResult = mockMvc.perform(post("/api/v1/internal/admin/api-clients")
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Apex Portal",
                                "description", "Portal integration client",
                                "lspId", lspId,
                                "status", "ACTIVE"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clientId").isString())
                .andExpect(jsonPath("$.clientSecret").isString())
                .andExpect(jsonPath("$.name").value("Apex Portal"))
                .andExpect(jsonPath("$.lspName").value("Apex Finance"))
                .andExpect(jsonPath("$.lastUsedAt").value(nullValue()))
                .andReturn();

        JsonNode createdJson = objectMapper.readTree(createdResult.getResponse().getContentAsString());
        String clientId = createdJson.get("clientId").asText();

        mockMvc.perform(get("/api/v1/internal/admin/api-clients").with(systemAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].clientId").value(clientId))
                .andExpect(jsonPath("$[0].name").value("Apex Portal"))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$[0].lastUsedAt").value(nullValue()))
                .andExpect(jsonPath("$[0].clientSecret").doesNotExist());
    }

    @Test
    void systemAdminCanUpdateApiClientMetadataAndAllowlist() throws Exception {
        CreatedClientFixture fixture = createActiveClient();

        mockMvc.perform(put("/api/v1/internal/admin/api-clients/{id}", fixture.id())
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "description", "Updated integration client",
                                "status", "DISABLED",
                                "ipAllowlist", List.of("10.0.0.0/8", "192.168.1.10/32")
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("Updated integration client"))
                .andExpect(jsonPath("$.status").value("INACTIVE"))
                .andExpect(jsonPath("$.ipAllowlist", hasSize(2)))
                .andExpect(jsonPath("$.clientSecret").doesNotExist());

        mockMvc.perform(get("/api/v1/internal/admin/api-clients").with(systemAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].ipAllowlist", hasSize(2)));

        assertTrue(apiClientAuditEventRepository.count() >= 1);
    }

    @Test
    void rotateSecretHonoursGracePeriodForPreviousSecret() throws Exception {
        CreatedClientFixture fixture = createActiveClient();
        String originalSecret = fixture.clientSecret();

        MvcResult rotateResult = mockMvc.perform(post("/api/v1/internal/admin/api-clients/{id}/rotate-secret", fixture.id())
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("graceSeconds", 300))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clientSecret").isString())
                .andExpect(jsonPath("$.oldSecretValidUntil").isString())
                .andReturn();

        JsonNode rotated = objectMapper.readTree(rotateResult.getResponse().getContentAsString());
        String newSecret = rotated.get("clientSecret").asText();

        issueClientCredentialsToken(fixture.clientId(), originalSecret);
        issueClientCredentialsToken(fixture.clientId(), newSecret);

        mockMvc.perform(post("/api/v1/internal/admin/api-clients/{id}/rotate-secret", fixture.id())
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("graceSeconds", 0))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AuthController.ClientCredentialsRequest(
                                fixture.clientId(),
                                originalSecret
                        ))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void nonSystemAdminCannotAccessApiClientEndpoints() throws Exception {
        mockMvc.perform(get("/api/v1/internal/admin/api-clients").with(opsUser()))
                .andExpect(status().isForbidden());
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor systemAdmin() {
        return jwt().jwt(jwt -> jwt.subject("ops.admin").claim("roles", List.of("SYSTEM_ADMIN")))
                .authorities(() -> "ROLE_SYSTEM_ADMIN");
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor opsUser() {
        return jwt().jwt(jwt -> jwt.subject("ops.user").claim("roles", List.of("OPS_USER")))
                .authorities(() -> "ROLE_OPS_USER");
    }

    private CreatedClientFixture createActiveClient() throws Exception {
        String lspCode = "APX" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();

        MvcResult lspResult = mockMvc.perform(post("/api/v1/internal/admin/lsps")
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", lspCode,
                                "name", "Apex Finance",
                                "status", "ACTIVE"
                        ))))
                .andExpect(status().isOk())
                .andReturn();

        String lspId = objectMapper.readTree(lspResult.getResponse().getContentAsString()).get("id").asText();

        MvcResult createdResult = mockMvc.perform(post("/api/v1/internal/admin/api-clients")
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Apex Portal",
                                "description", "Portal integration client",
                                "lspId", lspId,
                                "status", "ACTIVE"
                        ))))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode createdJson = objectMapper.readTree(createdResult.getResponse().getContentAsString());
        return new CreatedClientFixture(
                createdJson.get("id").asText(),
                createdJson.get("clientId").asText(),
                createdJson.get("clientSecret").asText()
        );
    }

    private void issueClientCredentialsToken(String clientId, String clientSecret) throws Exception {
        mockMvc.perform(post("/api/v1/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AuthController.ClientCredentialsRequest(
                                clientId,
                                clientSecret
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isString());
    }

    private record CreatedClientFixture(String id, String clientId, String clientSecret) {
    }
}
