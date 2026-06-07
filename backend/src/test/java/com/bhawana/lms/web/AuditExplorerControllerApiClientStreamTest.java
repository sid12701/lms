package com.bhawana.lms.web;

import com.bhawana.lms.support.TenantContextTestExecutionListener;
import org.springframework.test.context.TestExecutionListeners;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
class AuditExplorerControllerApiClientStreamTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void apiClientStreamSurfacesClientUpdatedRow() throws Exception {
        CreatedClientFixture fixture = createActiveClient();

        mockMvc.perform(put("/api/v1/internal/admin/api-clients/{id}", fixture.id())
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "description", "Explorer audit coverage",
                                "status", "DISABLED"
                        ))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/internal/admin/audit-events")
                        .with(systemAdmin())
                        .queryParam("streams", "API_CLIENT")
                        .queryParam("actorUsername", "ops.admin")
                        .queryParam("paginationDetails", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].stream").value("API_CLIENT"))
                .andExpect(jsonPath("$.items[0].action").value("CLIENT_DISABLED"))
                .andExpect(jsonPath("$.items[0].detail.apiClientId").value(fixture.id()))
                .andExpect(jsonPath("$.items[0].lspId").value(fixture.lspId()));
    }

    @Test
    void apiClientAndAppUserStreamsExcludeApplicationRows() throws Exception {
        CreatedClientFixture fixture = createActiveClient();
        mockMvc.perform(put("/api/v1/internal/admin/api-clients/{id}", fixture.id())
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "description", "Second update",
                                "status", "ACTIVE"
                        ))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/internal/admin/audit-events")
                        .with(systemAdmin())
                        .queryParam("streams", "API_CLIENT")
                        .queryParam("paginationDetails", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].stream", hasItem("API_CLIENT")));
    }

    private CreatedClientFixture createActiveClient() throws Exception {
        String lspCode = "AEX" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        MvcResult lspResult = mockMvc.perform(post("/api/v1/internal/admin/lsps")
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", lspCode,
                                "name", "Explorer LSP",
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
                                "name", "Explorer Client",
                                "description", "Initial",
                                "lspId", lspId,
                                "status", "ACTIVE"
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode createdJson = objectMapper.readTree(createdResult.getResponse().getContentAsString());
        return new CreatedClientFixture(createdJson.get("id").asText(), lspId, createdJson.get("clientId").asText());
    }

    private record CreatedClientFixture(String id, String lspId, String clientId) {
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor systemAdmin() {
        return jwt().jwt(token -> token.subject("ops.admin").claim("roles", List.of("SYSTEM_ADMIN")))
                .authorities(() -> "ROLE_SYSTEM_ADMIN");
    }
}
