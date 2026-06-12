package com.bhawana.lms.web;

import com.bhawana.lms.support.TenantContextTestExecutionListener;
import org.springframework.test.context.TestExecutionListeners;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bhawana.lms.domain.ApiClientAuditEvent;
import com.bhawana.lms.repo.ApiClientAuditEventRepository;
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
@TestExecutionListeners(
        value = TenantContextTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS
)
class ApiClientAdminControllerCreateAuditTest {

    private static final String CLIENT_IP = "203.0.113.88";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ApiClientAuditEventRepository apiClientAuditEventRepository;

    @Test
    void createClientWritesClientCreatedAuditRowWithActorIpAndSnapshot() throws Exception {
        String lspId = createLsp();
        long beforeCount = apiClientAuditEventRepository.count();

        MvcResult createdResult = mockMvc.perform(post("/api/v1/internal/admin/api-clients")
                        .with(systemAdmin())
                        .header("X-Forwarded-For", CLIENT_IP)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Audit Create Client",
                                "description", "Initial",
                                "lspId", lspId,
                                "status", "ACTIVE"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clientSecret").isString())
                .andReturn();

        JsonNode createdJson = objectMapper.readTree(createdResult.getResponse().getContentAsString());
        UUID apiClientId = UUID.fromString(createdJson.get("id").asText());
        String clientId = createdJson.get("clientId").asText();

        assertEquals(beforeCount + 1, apiClientAuditEventRepository.count());

        ApiClientAuditEvent auditEvent = apiClientAuditEventRepository.findAll().stream()
                .filter(row -> row.getApiClient().getId().equals(apiClientId))
                .max(java.util.Comparator.comparing(ApiClientAuditEvent::getCreatedAt))
                .orElseThrow();

        assertEquals("CLIENT_CREATED", auditEvent.getAction());
        assertEquals("ops.admin", auditEvent.getActorUsername());
        assertEquals(CLIENT_IP, auditEvent.getActorIp());
        assertFalse(auditEvent.getCorrelationId() == null || auditEvent.getCorrelationId().isBlank());

        JsonNode details = auditEvent.getDetailsJson();
        assertEquals(clientId, details.get("snapshot").get("clientId").asText());
        assertEquals("ACTIVE", details.get("snapshot").get("status").asText());
        assertFalse(details.toString().contains(createdJson.get("clientSecret").asText()));
    }

    @Test
    void updateClientWithoutStatusChangeWritesClientUpdatedWithActorIp() throws Exception {
        CreatedClientFixture fixture = createClient();

        mockMvc.perform(put("/api/v1/internal/admin/api-clients/{id}", fixture.id())
                        .with(systemAdmin())
                        .header("X-Forwarded-For", CLIENT_IP)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Renamed client",
                                "status", "ACTIVE"
                        ))))
                .andExpect(status().isOk());

        ApiClientAuditEvent auditEvent = latestAuditFor(fixture.id());
        assertEquals("CLIENT_UPDATED", auditEvent.getAction());
        assertEquals(CLIENT_IP, auditEvent.getActorIp());
    }

    @Test
    void statusFlipActiveToInactiveWritesClientDisabledLabel() throws Exception {
        CreatedClientFixture fixture = createClient();

        mockMvc.perform(put("/api/v1/internal/admin/api-clients/{id}", fixture.id())
                        .with(systemAdmin())
                        .header("X-Forwarded-For", CLIENT_IP)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "status", "DISABLED"
                        ))))
                .andExpect(status().isOk());

        ApiClientAuditEvent auditEvent = latestAuditFor(fixture.id());
        assertEquals("CLIENT_DISABLED", auditEvent.getAction());
        assertEquals("ACTIVE", auditEvent.getDetailsJson().get("before").get("status").asText());
        assertEquals("INACTIVE", auditEvent.getDetailsJson().get("after").get("status").asText());
    }

    @Test
    void statusFlipInactiveToActiveWritesClientEnabledLabel() throws Exception {
        CreatedClientFixture fixture = createClient();

        mockMvc.perform(put("/api/v1/internal/admin/api-clients/{id}", fixture.id())
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("status", "DISABLED"))))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/v1/internal/admin/api-clients/{id}", fixture.id())
                        .with(systemAdmin())
                        .header("X-Forwarded-For", CLIENT_IP)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("status", "ACTIVE"))))
                .andExpect(status().isOk());

        ApiClientAuditEvent auditEvent = latestAuditFor(fixture.id());
        assertEquals("CLIENT_ENABLED", auditEvent.getAction());
        assertEquals(CLIENT_IP, auditEvent.getActorIp());
    }

    @Test
    void rotateSecretWritesActorIpAndNeverSerializesRawSecret() throws Exception {
        CreatedClientFixture fixture = createClient();

        MvcResult rotateResult = mockMvc.perform(post("/api/v1/internal/admin/api-clients/{id}/rotate-secret", fixture.id())
                        .with(systemAdmin())
                        .header("X-Forwarded-For", CLIENT_IP)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("graceSeconds", 0))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clientSecret").isString())
                .andReturn();

        String newSecret = objectMapper.readTree(rotateResult.getResponse().getContentAsString())
                .get("clientSecret")
                .asText();

        ApiClientAuditEvent auditEvent = latestAuditFor(fixture.id());
        assertEquals("SECRET_ROTATED", auditEvent.getAction());
        assertEquals(CLIENT_IP, auditEvent.getActorIp());
        String detailsText = auditEvent.getDetailsJson().toString();
        assertFalse(detailsText.contains(newSecret));
        assertFalse(detailsText.contains("$2a$"));
        assertFalse(detailsText.contains("$2b$"));
    }

    @Test
    void errorPathsLeaveAuditTableUntouched() throws Exception {
        long beforeCount = apiClientAuditEventRepository.count();

        mockMvc.perform(post("/api/v1/internal/admin/api-clients")
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Bad LSP",
                                "lspId", UUID.randomUUID().toString(),
                                "status", "ACTIVE"
                        ))))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/v1/internal/admin/api-clients/{id}/rotate-secret", UUID.randomUUID())
                        .with(systemAdmin()))
                .andExpect(status().isNotFound());

        mockMvc.perform(put("/api/v1/internal/admin/api-clients/{id}", UUID.randomUUID())
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("status", "ACTIVE"))))
                .andExpect(status().isNotFound());

        assertEquals(beforeCount, apiClientAuditEventRepository.count());
    }

    private ApiClientAuditEvent latestAuditFor(String apiClientUuid) {
        UUID apiClientId = UUID.fromString(apiClientUuid);
        return apiClientAuditEventRepository.findAll().stream()
                .filter(row -> row.getApiClient().getId().equals(apiClientId))
                .max(java.util.Comparator.comparing(ApiClientAuditEvent::getCreatedAt))
                .orElseThrow();
    }

    private String createLsp() throws Exception {
        String lspCode = "ACA" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        MvcResult lspResult = mockMvc.perform(post("/api/v1/internal/admin/lsps")
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", lspCode,
                                "name", "Audit LSP",
                                "status", "ACTIVE"
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(lspResult.getResponse().getContentAsString()).get("id").asText();
    }

    private CreatedClientFixture createClient() throws Exception {
        String lspId = createLsp();
        MvcResult createdResult = mockMvc.perform(post("/api/v1/internal/admin/api-clients")
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Fixture Client",
                                "lspId", lspId,
                                "status", "ACTIVE"
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode createdJson = objectMapper.readTree(createdResult.getResponse().getContentAsString());
        return new CreatedClientFixture(
                createdJson.get("id").asText(),
                createdJson.get("clientId").asText()
        );
    }

    private record CreatedClientFixture(String id, String clientId) {
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor systemAdmin() {
        return jwt().jwt(token -> token.subject("ops.admin").claim("roles", List.of("SYSTEM_ADMIN")))
                .authorities(() -> "ROLE_SYSTEM_ADMIN");
    }
}
