package com.bhawana.lms.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bhawana.lms.domain.ApiClient;
import com.bhawana.lms.repo.ApiClientRepository;
import com.bhawana.lms.service.ApiClientLockoutService;
import com.bhawana.lms.support.TenantContextTestExecutionListener;
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
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestExecutionListeners(
        value = TenantContextTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS
)
class ApiClientTokenLockoutIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ApiClientRepository apiClientRepository;

    @Test
    void repeatedFailedTokenAttemptsLockTheClientEvenForTheCorrectSecret() throws Exception {
        CreatedClient client = createActiveClient();

        for (int attempt = 0; attempt < ApiClientLockoutService.MAX_FAILED_ATTEMPTS; attempt++) {
            attemptToken(client.clientId(), "wrong-secret")
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        }

        ApiClient locked = apiClientRepository.findByClientId(client.clientId()).orElseThrow();
        assertNotNull(locked.getAuthLockedUntil());

        // The correct secret is rejected while the throttle window is active.
        attemptToken(client.clientId(), client.clientSecret())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void successfulTokenIssuanceResetsTheFailedAttemptCounter() throws Exception {
        CreatedClient client = createActiveClient();

        for (int attempt = 0; attempt < ApiClientLockoutService.MAX_FAILED_ATTEMPTS - 1; attempt++) {
            attemptToken(client.clientId(), "wrong-secret").andExpect(status().isUnauthorized());
        }

        attemptToken(client.clientId(), client.clientSecret())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isString());

        ApiClient afterSuccess = apiClientRepository.findByClientId(client.clientId()).orElseThrow();
        assertEquals(0, afterSuccess.getFailedAuthAttempts());
        assertNull(afterSuccess.getAuthLockedUntil());

        // A fresh run of failures below the threshold still does not lock — proving the reset.
        for (int attempt = 0; attempt < ApiClientLockoutService.MAX_FAILED_ATTEMPTS - 1; attempt++) {
            attemptToken(client.clientId(), "wrong-secret").andExpect(status().isUnauthorized());
        }
        assertNull(apiClientRepository.findByClientId(client.clientId()).orElseThrow().getAuthLockedUntil());

        attemptToken(client.clientId(), client.clientSecret()).andExpect(status().isOk());
    }

    private ResultActions attemptToken(String clientId, String secret) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        new AuthApiResponses.ClientCredentialsRequest(clientId, secret))));
    }

    private CreatedClient createActiveClient() throws Exception {
        String lspCode = "LCK" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        MvcResult lspResult = mockMvc.perform(post("/api/v1/internal/admin/lsps")
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", lspCode,
                                "name", "Lockout LSP",
                                "status", "ACTIVE"))))
                .andExpect(status().isOk())
                .andReturn();
        String lspId = objectMapper.readTree(lspResult.getResponse().getContentAsString()).get("id").asText();

        MvcResult createdResult = mockMvc.perform(post("/api/v1/internal/admin/api-clients")
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Lockout Client",
                                "lspId", lspId,
                                "status", "ACTIVE"))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode createdJson = objectMapper.readTree(createdResult.getResponse().getContentAsString());
        return new CreatedClient(createdJson.get("clientId").asText(), createdJson.get("clientSecret").asText());
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor systemAdmin() {
        return jwt().jwt(token -> token.subject("ops.admin").claim("roles", List.of("SYSTEM_ADMIN")))
                .authorities(() -> "ROLE_SYSTEM_ADMIN");
    }

    private record CreatedClient(String clientId, String clientSecret) {
    }
}
