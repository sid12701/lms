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
import jakarta.servlet.http.Cookie;
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

/**
 * Issue #63 — LSP hard-disable kill chain (token version + admin status API).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestExecutionListeners(
        value = TenantContextTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS
)
class LspStatusKillChainIntegrationTest {

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
    void disablingLspCausesExistingApiClientTokenTo401OnNextRequest() throws Exception {
        String lspId = createLsp("ACTIVE");
        ProductFixture product = createProduct("ACTIVE");
        mapProductToLsp(product.id(), lspId);
        JsonNode apiClient = createApiClient(lspId, "Kill-chain client");
        String accessToken = issueClientCredentialsToken(
                apiClient.get("clientId").asText(),
                apiClient.get("clientSecret").asText()
        );

        mockMvc.perform(get("/api/v1/lsp/products")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/v1/internal/admin/lsps/{lspId}/status", lspId)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "status", "INACTIVE",
                                "reason", "SECURITY_INCIDENT",
                                "note", "Compromised credentials during pen test."
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INACTIVE"));

        mockMvc.perform(get("/api/v1/lsp/products")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("LSP_TOKEN_REVOKED"));
    }

    @Test
    void disableEndpointRequiresSystemAdminAndRejectsWithoutReason() throws Exception {
        String lspId = createLsp("ACTIVE");

        mockMvc.perform(put("/api/v1/internal/admin/lsps/{lspId}/status", lspId)
                        .with(opsUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "status", "INACTIVE",
                                "reason", "SECURITY_INCIDENT",
                                "note", "Should be forbidden."
                        ))))
                .andExpect(status().isForbidden());

        mockMvc.perform(put("/api/v1/internal/admin/lsps/{lspId}/status", lspId)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "status", "INACTIVE",
                                "note", "Missing reason."
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REASON_REQUIRED"));

        mockMvc.perform(put("/api/v1/internal/admin/lsps/{lspId}/status", lspId)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "status", "INACTIVE",
                                "reason", "NOT_A_REAL_REASON",
                                "note", "Invalid reason."
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REASON"));
    }

    @Test
    void disableWritesAuditRowAndFiresAlert() throws Exception {
        String lspId = createLsp("ACTIVE");
        createApiClient(lspId, "Audited client");

        mockMvc.perform(put("/api/v1/internal/admin/lsps/{lspId}/status", lspId)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "status", "INACTIVE",
                                "reason", "COMPLIANCE",
                                "note", "Regulatory hold."
                        ))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/internal/admin/lsps/{lspId}/audit-events", lspId)
                        .with(systemAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].action").value("LSP_DISABLED"))
                .andExpect(jsonPath("$[0].reason").value("COMPLIANCE"))
                .andExpect(jsonPath("$[0].cascadedClientCount").value(1));

        mockMvc.perform(put("/api/v1/internal/admin/lsps/{lspId}/status", lspId)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "status", "INACTIVE",
                                "reason", "COMPLIANCE",
                                "note", "Duplicate disable."
                        ))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("STATUS_UNCHANGED"));

        mockMvc.perform(get("/api/v1/internal/admin/lsps/{lspId}/audit-events", lspId)
                        .with(systemAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        MvcResult alertsResult = mockMvc.perform(get("/api/v1/internal/alerts")
                        .with(systemAdmin()))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode alerts = objectMapper.readTree(alertsResult.getResponse().getContentAsString());
        long disabledAlerts = 0;
        String disabledSeverity = null;
        for (JsonNode alert : alerts) {
            if ("LSP_DISABLED".equals(alert.get("type").asText()) && lspId.equals(alert.get("subjectId").asText())) {
                disabledAlerts++;
                disabledSeverity = alert.get("severity").asText();
            }
        }
        org.junit.jupiter.api.Assertions.assertEquals(1, disabledAlerts);
        org.junit.jupiter.api.Assertions.assertEquals("HIGH", disabledSeverity);
    }

    @Test
    void disableCascadesInactiveStatusAndTokenDeathToEveryApiClient() throws Exception {
        String lspId = createLsp("ACTIVE");
        JsonNode firstClient = createApiClient(lspId, "Client A");
        JsonNode secondClient = createApiClient(lspId, "Client B");
        String firstToken = issueClientCredentialsToken(
                firstClient.get("clientId").asText(),
                firstClient.get("clientSecret").asText()
        );
        String secondToken = issueClientCredentialsToken(
                secondClient.get("clientId").asText(),
                secondClient.get("clientSecret").asText()
        );

        mockMvc.perform(put("/api/v1/internal/admin/lsps/{lspId}/status", lspId)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "status", "INACTIVE",
                                "reason", "OPERATIONAL",
                                "note", "Maintenance window."
                        ))))
                .andExpect(status().isOk());

        MvcResult clientsResult = mockMvc.perform(get("/api/v1/internal/admin/api-clients")
                        .with(systemAdmin()))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode clients = objectMapper.readTree(clientsResult.getResponse().getContentAsString());
        long inactiveForLsp = 0;
        for (JsonNode client : clients) {
            if (lspId.equals(client.get("lspId").asText()) && "INACTIVE".equals(client.get("status").asText())) {
                inactiveForLsp++;
            }
        }
        org.junit.jupiter.api.Assertions.assertEquals(2, inactiveForLsp);

        mockMvc.perform(get("/api/v1/lsp/products").header("Authorization", "Bearer " + firstToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("LSP_TOKEN_REVOKED"));

        mockMvc.perform(get("/api/v1/lsp/products").header("Authorization", "Bearer " + secondToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("LSP_TOKEN_REVOKED"));
    }

    @Test
    void disablingOneLspDoesNotAffectOtherLsps() throws Exception {
        String lspA = createLsp("ACTIVE");
        String lspB = createLsp("ACTIVE");
        ProductFixture product = createProduct("ACTIVE");
        mapProductToLsp(product.id(), lspA);
        mapProductToLsp(product.id(), lspB);

        JsonNode clientB = createApiClient(lspB, "Healthy LSP client");
        String tokenB = issueClientCredentialsToken(
                clientB.get("clientId").asText(),
                clientB.get("clientSecret").asText()
        );

        mockMvc.perform(put("/api/v1/internal/admin/lsps/{lspId}/status", lspA)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "status", "INACTIVE",
                                "reason", "SECURITY_INCIDENT",
                                "note", "Only A is compromised."
                        ))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/lsp/products").header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk());
    }

    @Test
    void reActivationDoesNotReviveExistingTokens() throws Exception {
        String lspId = createLsp("ACTIVE");
        ProductFixture product = createProduct("ACTIVE");
        mapProductToLsp(product.id(), lspId);
        JsonNode apiClient = createApiClient(lspId, "Recovery client");
        String accessToken = issueClientCredentialsToken(
                apiClient.get("clientId").asText(),
                apiClient.get("clientSecret").asText()
        );

        disableLsp(lspId, "OFFBOARDING", "Temporary offboard.");

        mockMvc.perform(get("/api/v1/lsp/products").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(put("/api/v1/internal/admin/lsps/{lspId}/status", lspId)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "status", "ACTIVE",
                                "reason", "OPERATIONAL",
                                "note", "Partner cleared to resume."
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        mockMvc.perform(get("/api/v1/lsp/products").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("LSP_TOKEN_REVOKED"));
    }

    @Test
    void reActivationPlusCredentialRotationIssuesWorkingToken() throws Exception {
        String lspId = createLsp("ACTIVE");
        ProductFixture product = createProduct("ACTIVE");
        mapProductToLsp(product.id(), lspId);
        JsonNode apiClient = createApiClient(lspId, "Rotate client");
        String clientId = apiClient.get("clientId").asText();
        String oldSecret = apiClient.get("clientSecret").asText();
        String deadToken = issueClientCredentialsToken(clientId, oldSecret);

        disableLsp(lspId, "SECURITY_INCIDENT", "Rotate credentials.");
        reactivateLsp(lspId);

        MvcResult rotateResult = mockMvc.perform(post("/api/v1/internal/admin/api-clients/{id}/rotate-secret", apiClient.get("id").asText())
                        .with(systemAdmin()))
                .andExpect(status().isOk())
                .andReturn();
        String newSecret = objectMapper.readTree(rotateResult.getResponse().getContentAsString())
                .get("clientSecret")
                .asText();

        String newToken = issueClientCredentialsToken(clientId, newSecret);
        mockMvc.perform(get("/api/v1/lsp/products").header("Authorization", "Bearer " + newToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/lsp/products").header("Authorization", "Bearer " + deadToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void apiClientRefreshTokenPathRejectsInactiveLsp() throws Exception {
        String lspId = createLsp("ACTIVE");
        JsonNode apiClient = createApiClient(lspId, "Refresh client");
        MvcResult tokenResult = mockMvc.perform(post("/api/v1/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AuthApiResponses.ClientCredentialsRequest(
                                apiClient.get("clientId").asText(),
                                apiClient.get("clientSecret").asText()
                        ))))
                .andExpect(status().isOk())
                .andReturn();

        Cookie refreshCookie = tokenResult.getResponse().getCookie("lms-refresh");
        disableLsp(lspId, "SECURITY_INCIDENT", "Kill refresh path.");

        mockMvc.perform(post("/api/v1/auth/refresh").cookie(refreshCookie))
                .andExpect(status().isUnauthorized());
    }

    private void disableLsp(String lspId, String reason, String note) throws Exception {
        mockMvc.perform(put("/api/v1/internal/admin/lsps/{lspId}/status", lspId)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "status", "INACTIVE",
                                "reason", reason,
                                "note", note
                        ))))
                .andExpect(status().isOk());
    }

    private void reactivateLsp(String lspId) throws Exception {
        mockMvc.perform(put("/api/v1/internal/admin/lsps/{lspId}/status", lspId)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "status", "ACTIVE",
                                "reason", "OPERATIONAL",
                                "note", "Cleared for service."
                        ))))
                .andExpect(status().isOk());
    }

    private String createLsp(String status) throws Exception {
        MvcResult createResult = mockMvc.perform(post("/api/v1/internal/admin/lsps")
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", "LSP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                                "name", "Kill-chain LSP",
                                "status", status
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(createResult.getResponse().getContentAsString()).get("id").asText();
    }

    private JsonNode createApiClient(String lspId, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/internal/admin/api-clients")
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", name,
                                "lspId", lspId,
                                "status", "ACTIVE"
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String issueClientCredentialsToken(String clientId, String clientSecret) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AuthApiResponses.ClientCredentialsRequest(
                                clientId,
                                clientSecret
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }

    private ProductFixture createProduct(String status) throws Exception {
        String code = "PRODUCT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        MvcResult createResult = mockMvc.perform(post("/api/v1/internal/admin/products")
                        .with(productAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", code,
                                "name", "Product " + code,
                                "minPrincipal", 5000,
                                "maxPrincipal", 250000,
                                "interestRate", 18.5,
                                "processingFeeRate", 2.25,
                                "minTenureMonths", 6,
                                "maxTenureMonths", 24,
                                "status", status
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode created = objectMapper.readTree(createResult.getResponse().getContentAsString());
        return new ProductFixture(created.get("id").asText());
    }

    private void mapProductToLsp(String productId, String lspId) throws Exception {
        mockMvc.perform(put("/api/v1/internal/admin/product-lsp-mappings/{productId}", productId)
                        .with(productAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("lspIds", List.of(lspId)))))
                .andExpect(status().isOk());
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor systemAdmin() {
        return jwt().jwt(token -> token.subject("ops.admin").claim("roles", List.of("SYSTEM_ADMIN")))
                .authorities(() -> "ROLE_SYSTEM_ADMIN");
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor opsUser() {
        return jwt().jwt(token -> token.subject("ops.user").claim("roles", List.of("OPS_USER")))
                .authorities(() -> "ROLE_OPS_USER");
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor productAdmin() {
        return jwt().jwt(token -> token.subject("product.admin").claim("roles", List.of("PRODUCT_ADMIN")))
                .authorities(() -> "ROLE_PRODUCT_ADMIN");
    }

    private record ProductFixture(String id) {
    }
}
