package com.bhawana.lms.web;

import com.bhawana.lms.support.TenantContextTestExecutionListener;
import org.springframework.test.context.TestExecutionListeners;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bhawana.lms.support.IntegrationTestDatabaseCleaner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
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
class LspAdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private IntegrationTestDatabaseCleaner integrationTestDatabaseCleaner;

    @BeforeEach
    void setUp() {
        integrationTestDatabaseCleaner.cleanIntegrationTestData();
    }

    @Test
    void systemAdminCanCreateListAndUpdateWebhookSubscription() throws Exception {
        MvcResult createResult = mockMvc.perform(post("/api/v1/internal/admin/lsps")
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", "APEX-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                                "name", "Apex Finance",
                                "status", "ACTIVE"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.webhookSubscription.enabled").value(false))
                .andExpect(jsonPath("$.webhookSubscription.eventTypes.length()").value(0))
                .andReturn();

        JsonNode created = objectMapper.readTree(createResult.getResponse().getContentAsString());
        String lspId = created.get("id").asText();

        mockMvc.perform(put("/api/v1/internal/admin/lsps/{lspId}/webhook-subscription", lspId)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "enabled", true,
                                "endpointUrl", "https://partner.example.com/webhooks/lms",
                                "signingSecret", "whsec_test_123",
                                "eventTypes", List.of("LOAN_CREATED", "LOAN_STATUS_CHANGED")
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.webhookSubscription.enabled").value(true))
                .andExpect(jsonPath("$.webhookSubscription.endpointUrl").value("https://partner.example.com/webhooks/lms"))
                .andExpect(jsonPath("$.webhookSubscription.signingSecret").value("whsec_test_123"))
                .andExpect(jsonPath("$.webhookSubscription.eventTypes.length()").value(2));

        mockMvc.perform(get("/api/v1/internal/admin/lsps")
                        .with(systemAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(lspId))
                .andExpect(jsonPath("$[0].webhookSubscription.enabled").value(true))
                .andExpect(jsonPath("$[0].webhookSubscription.eventTypes[0]").value("LOAN_CREATED"));
    }

    @Test
    void enabledWebhookSubscriptionRequiresUrlSecretAndEvents() throws Exception {
        String lspId = createLsp();

        mockMvc.perform(put("/api/v1/internal/admin/lsps/{lspId}/webhook-subscription", lspId)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "enabled", true,
                                "endpointUrl", "",
                                "signingSecret", "",
                                "eventTypes", List.of()
                        ))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("WEBHOOK_ENDPOINT_REQUIRED"));
    }

    @Test
    void opsUserCannotAccessLspAdminEndpoints() throws Exception {
        mockMvc.perform(get("/api/v1/internal/admin/lsps").with(opsUser()))
                .andExpect(status().isForbidden());
    }

    @Test
    void opsUserCanReadLightweightLspOptions() throws Exception {
        String lspId = createLsp();
        createLsp("INACTIVE");

        mockMvc.perform(get("/api/v1/internal/admin/lsp-options")
                        .with(opsUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(lspId))
                .andExpect(jsonPath("$[0].code").exists())
                .andExpect(jsonPath("$[0].name").value("North Finance"))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$[0].portfolioSummary").doesNotExist())
                .andExpect(jsonPath("$[0].webhookSubscription").doesNotExist());
    }

    @Test
    void systemAdminCanViewLspDetailWithUsersAndPortfolioSummary() throws Exception {
        String lspId = createLsp();

        mockMvc.perform(post("/api/v1/internal/admin/users")
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "north.viewer",
                                "email", "north.viewer@example.com",
                                "password", "TempPass123!",
                                "status", "ACTIVE",
                                "lspId", lspId,
                                "roles", List.of("LSP_UI_READ")
                        ))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/internal/admin/lsps/{lspId}", lspId)
                        .with(systemAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(lspId))
                .andExpect(jsonPath("$.userCount").value(1))
                .andExpect(jsonPath("$.portfolioSummary.loanApplicationCount").value(0))
                .andExpect(jsonPath("$.users[0].username").value("north.viewer"))
                .andExpect(jsonPath("$.users[0].roles[0]").value("LSP_UI_READ"));
    }

    private String createLsp() throws Exception {
        return createLsp("ACTIVE");
    }

    private String createLsp(String status) throws Exception {
        MvcResult createResult = mockMvc.perform(post("/api/v1/internal/admin/lsps")
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", "NORTH-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                                "name", "North Finance",
                                "status", status
                        ))))
                .andExpect(status().isOk())
                .andReturn();

        return objectMapper.readTree(createResult.getResponse().getContentAsString()).get("id").asText();
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor systemAdmin() {
        return jwt().jwt(token -> token.subject("ops.admin").claim("roles", List.of("SYSTEM_ADMIN")))
                .authorities(() -> "ROLE_SYSTEM_ADMIN");
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor opsUser() {
        return jwt().jwt(token -> token.subject("ops.user").claim("roles", List.of("OPS_USER")))
                .authorities(() -> "ROLE_OPS_USER");
    }
}
