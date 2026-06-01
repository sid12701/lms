package com.bhawana.lms.web;

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
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Issue #64 — LSP surface IP allowlists (UI vs API) with token-issuance enforcement.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class Issue64LspSurfaceIpAllowlistIntegrationTest {

  private static final String ALLOWED_IP = "10.0.0.50";
  private static final String BLOCKED_IP = "192.168.99.99";

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Test
  void apiClientTokenIssuanceFromNonAllowedIpIsRejectedWhenEnforcementIsOn() throws Exception {
    Seed seed = seedLspAndApiClient();
    addApiCidr(seed.lspId(), "10.0.0.0/24");
    enableApiEnforcement(seed.lspId());

    mockMvc
        .perform(
            post("/api/v1/auth/token")
                .with(remoteAddr(BLOCKED_IP))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        new AuthController.ClientCredentialsRequest(
                            seed.clientId(), seed.clientSecret()))))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error").value("API_CLIENT_IP_NOT_ALLOWED"));
  }

  @Test
  void apiClientTokenIssuanceFromAllowedIpSucceedsWhenEnforcementIsOn() throws Exception {
    Seed seed = seedLspAndApiClient();
    addApiCidr(seed.lspId(), "10.0.0.0/24");
    enableApiEnforcement(seed.lspId());

    mockMvc
        .perform(
            post("/api/v1/auth/token")
                .with(remoteAddr(ALLOWED_IP))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        new AuthController.ClientCredentialsRequest(
                            seed.clientId(), seed.clientSecret()))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accessToken").isString());
  }

  @Test
  void apiClientTokenUsedFromUiAllowedButApiDisallowedIpIsRejectedOnLspRoute() throws Exception {
    Seed seed = seedLspAndApiClient();
    addUiCidr(seed.lspId(), "192.168.1.0/24");
    addApiCidr(seed.lspId(), "10.0.0.0/24");

    String token =
        issueTokenFromIp(seed, ALLOWED_IP);

    mockMvc
        .perform(
            get("/api/v1/lsp/products")
                .with(remoteAddr("192.168.1.50"))
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error").value("IP_NOT_ALLOWED"));
  }

  @Test
  void adminCannotEnableApiEnforcementWhileApiListIsEmpty() throws Exception {
    Seed seed = seedLspAndApiClient();

    mockMvc
        .perform(
            put("/api/v1/internal/admin/lsps/{lspId}/allowlist-enforcement", seed.lspId())
                .with(systemAdmin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("enforceApi", true))))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.error").value("ALLOWLIST_EMPTY_CANNOT_ENFORCE"));
  }

  @Test
  void addingApiCidrTakesEffectOnNextTokenRequestWithoutWaitingForCacheTtl() throws Exception {
    Seed seed = seedLspAndApiClient();
    addApiCidr(seed.lspId(), "10.0.0.0/24");
    enableApiEnforcement(seed.lspId());

    mockMvc
        .perform(
            post("/api/v1/auth/token")
                .with(remoteAddr(ALLOWED_IP))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        new AuthController.ClientCredentialsRequest(
                            seed.clientId(), seed.clientSecret()))))
        .andExpect(status().isOk());
  }

  private String issueTokenFromIp(Seed seed, String remoteIp) throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/auth/token")
                    .with(remoteAddr(remoteIp))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            new AuthController.ClientCredentialsRequest(
                                seed.clientId(), seed.clientSecret()))))
            .andExpect(status().isOk())
            .andReturn();
    return objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
  }

  private Seed seedLspAndApiClient() throws Exception {
    String lspCode = "IP" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    MvcResult lspResult =
        mockMvc
            .perform(
                post("/api/v1/internal/admin/lsps")
                    .with(systemAdmin())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            Map.of("code", lspCode, "name", "IP Test LSP", "status", "ACTIVE"))))
            .andExpect(status().isOk())
            .andReturn();
    String lspId = objectMapper.readTree(lspResult.getResponse().getContentAsString()).get("id").asText();

    MvcResult clientResult =
        mockMvc
            .perform(
                post("/api/v1/internal/admin/api-clients")
                    .with(systemAdmin())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            Map.of(
                                "name",
                                "IP test client",
                                "lspId",
                                lspId,
                                "status",
                                "ACTIVE"))))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode clientJson = objectMapper.readTree(clientResult.getResponse().getContentAsString());
    return new Seed(lspId, clientJson.get("clientId").asText(), clientJson.get("clientSecret").asText());
  }

  private void addApiCidr(String lspId, String cidr) throws Exception {
    mockMvc
        .perform(
            post("/api/v1/internal/admin/lsps/{lspId}/api-ip-allowlist", lspId)
                .with(systemAdmin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("cidr", cidr, "description", "test"))))
        .andExpect(status().isCreated());
  }

  private void addUiCidr(String lspId, String cidr) throws Exception {
    mockMvc
        .perform(
            post("/api/v1/internal/admin/lsps/{lspId}/ui-ip-allowlist", lspId)
                .with(systemAdmin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("cidr", cidr, "description", "test"))))
        .andExpect(status().isCreated());
  }

  private void enableApiEnforcement(String lspId) throws Exception {
    mockMvc
        .perform(
            put("/api/v1/internal/admin/lsps/{lspId}/allowlist-enforcement", lspId)
                .with(systemAdmin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        Map.of("enforceApi", true, "enforceUi", false))))
        .andExpect(status().isOk());
  }

  private static RequestPostProcessor remoteAddr(String addr) {
    return request -> {
      request.setRemoteAddr(addr);
      return request;
    };
  }

  private static org.springframework.test.web.servlet.request.RequestPostProcessor systemAdmin() {
    return jwt()
        .jwt(
            builder ->
                builder
                    .subject("test.admin")
                    .claim("roles", List.of("SYSTEM_ADMIN")))
        .authorities(() -> "ROLE_SYSTEM_ADMIN");
  }

  private record Seed(String lspId, String clientId, String clientSecret) {}
}
