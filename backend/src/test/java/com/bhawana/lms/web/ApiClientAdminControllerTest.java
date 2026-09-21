package com.bhawana.lms.web;

import com.bhawana.lms.support.TenantContextTestExecutionListener;
import org.springframework.test.context.TestExecutionListeners;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.nullValue;

import com.bhawana.lms.repo.ApiClientAuditEventRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
@TestExecutionListeners(
        value = TenantContextTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS
)
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
    void systemAdminCanUpdateApiClientMetadata() throws Exception {
        CreatedClientFixture fixture = createActiveClient();

        mockMvc.perform(put("/api/v1/internal/admin/api-clients/{id}", fixture.id())
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "description", "Updated integration client",
                                "status", "DISABLED"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("Updated integration client"))
                .andExpect(jsonPath("$.status").value("INACTIVE"))
                .andExpect(jsonPath("$.clientSecret").doesNotExist());

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
                        .content(objectMapper.writeValueAsString(new AuthApiResponses.ClientCredentialsRequest(
                                fixture.clientId(),
                                originalSecret
                        ))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createWithSameIdempotencyKeyReplaysAndDoesNotDuplicate() throws Exception {
        String lspId = createIdempotencyLsp();
        String key = UUID.randomUUID().toString();
        String body = objectMapper.writeValueAsString(Map.of(
                "name", "Idempotent Client",
                "description", "First call",
                "lspId", lspId,
                "status", "ACTIVE"
        ));

        MvcResult first = mockMvc.perform(post("/api/v1/internal/admin/api-clients")
                        .with(systemAdmin())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clientSecret").isString())
                .andReturn();
        JsonNode firstJson = objectMapper.readTree(first.getResponse().getContentAsString());
        String clientId = firstJson.get("clientId").asText();

        MvcResult replay = mockMvc.perform(post("/api/v1/internal/admin/api-clients")
                        .with(systemAdmin())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clientId").value(clientId))
                .andReturn();

        // The one-time secret is never replayed: the stored idempotency record holds metadata only.
        JsonNode replayJson = objectMapper.readTree(replay.getResponse().getContentAsString());
        assertTrue(replayJson.get("clientSecret") == null || replayJson.get("clientSecret").isNull());
        assertEquals(1, countClientsForLsp(lspId));
    }

    @Test
    void createWithReusedKeyButDifferentBodyConflicts() throws Exception {
        String lspId = createIdempotencyLsp();
        String key = UUID.randomUUID().toString();

        mockMvc.perform(post("/api/v1/internal/admin/api-clients")
                        .with(systemAdmin())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Original Name",
                                "lspId", lspId,
                                "status", "ACTIVE"
                        ))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/internal/admin/api-clients")
                        .with(systemAdmin())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Different Name",
                                "lspId", lspId,
                                "status", "ACTIVE"
                        ))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));

        assertEquals(1, countClientsForLsp(lspId));
    }

    @Test
    void rotateWithSameIdempotencyKeyRotatesOnce() throws Exception {
        CreatedClientFixture fixture = createActiveClient();
        String originalSecret = fixture.clientSecret();
        String key = UUID.randomUUID().toString();
        String rotateBody = objectMapper.writeValueAsString(Map.of("graceSeconds", 300));

        MvcResult firstRotate = mockMvc.perform(post("/api/v1/internal/admin/api-clients/{id}/rotate-secret", fixture.id())
                        .with(systemAdmin())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rotateBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clientSecret").isString())
                .andExpect(jsonPath("$.oldSecretValidUntil").isString())
                .andReturn();
        JsonNode firstJson = objectMapper.readTree(firstRotate.getResponse().getContentAsString());
        String newSecret = firstJson.get("clientSecret").asText();
        String firstValidUntil = firstJson.get("oldSecretValidUntil").asText();

        MvcResult replayRotate = mockMvc.perform(post("/api/v1/internal/admin/api-clients/{id}/rotate-secret", fixture.id())
                        .with(systemAdmin())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rotateBody))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode replayJson = objectMapper.readTree(replayRotate.getResponse().getContentAsString());
        assertTrue(replayJson.get("clientSecret") == null || replayJson.get("clientSecret").isNull());
        assertEquals(firstValidUntil, replayJson.get("oldSecretValidUntil").asText());

        // Exactly one rotation ran: the original secret is still valid within its grace window and
        // the new secret works. A second rotation would have invalidated the original secret.
        issueClientCredentialsToken(fixture.clientId(), originalSecret);
        issueClientCredentialsToken(fixture.clientId(), newSecret);
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

    @Test
    void listApiClientsIsBoundedAndEmitsPaginationHeaders() throws Exception {
        String lspId = createIdempotencyLsp();
        createClientNamed(lspId, "M20 Page Client A");
        createClientNamed(lspId, "M20 Page Client B");

        // M20 — a requested page returns only that slice; the total count comes
        // from the headers, not from downloading every row.
        MvcResult first = mockMvc.perform(get("/api/v1/internal/admin/api-clients")
                        .param("lspId", lspId)
                        .param("limit", "1")
                        .param("offset", "0")
                        .param("paginationDetails", "ON")
                        .with(systemAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("X-Total-Count", "2"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("X-Limit", "1"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("X-Offset", "0"))
                .andReturn();

        MvcResult second = mockMvc.perform(get("/api/v1/internal/admin/api-clients")
                        .param("lspId", lspId)
                        .param("limit", "1")
                        .param("offset", "1")
                        .param("paginationDetails", "ON")
                        .with(systemAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andReturn();

        // Both pages together cover the full filtered set — no row repeats.
        JsonNode firstPage = objectMapper.readTree(first.getResponse().getContentAsString());
        JsonNode secondPage = objectMapper.readTree(second.getResponse().getContentAsString());
        String firstId = firstPage.get(0).get("id").asText();
        String secondId = secondPage.get(0).get("id").asText();
        assertTrue(!firstId.equals(secondId));

        // Offset past the end returns an empty page, not an error.
        mockMvc.perform(get("/api/v1/internal/admin/api-clients")
                        .param("lspId", lspId)
                        .param("limit", "10")
                        .param("offset", "500")
                        .param("paginationDetails", "ON")
                        .with(systemAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("X-Total-Count", "2"));
    }

    @Test
    void listApiClientsAppliesFiltersBeforePagination() throws Exception {
        String lspId = createIdempotencyLsp();
        String needle = "m20uniq" + UUID.randomUUID().toString().substring(0, 8);
        createClientNamed(lspId, needle + " alpha");
        createClientNamed(lspId, "M20 Other Client");

        mockMvc.perform(get("/api/v1/internal/admin/api-clients")
                        .param("lspId", lspId)
                        .param("paginationDetails", "ON")
                        .with(systemAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("X-Total-Count", "2"));

        mockMvc.perform(get("/api/v1/internal/admin/api-clients")
                        .param("q", needle.toUpperCase())
                        .with(systemAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value(needle + " alpha"));

        mockMvc.perform(get("/api/v1/internal/admin/api-clients")
                        .param("lspId", lspId)
                        .param("status", "INACTIVE")
                        .with(systemAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        mockMvc.perform(get("/api/v1/internal/admin/api-clients")
                        .param("lspId", UUID.randomUUID().toString())
                        .with(systemAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void listApiClientsRejectsInvalidPaginationParams() throws Exception {
        mockMvc.perform(get("/api/v1/internal/admin/api-clients")
                        .param("limit", "0")
                        .with(systemAdmin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(get("/api/v1/internal/admin/api-clients")
                        .param("offset", "-1")
                        .with(systemAdmin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(get("/api/v1/internal/admin/api-clients")
                        .param("limit", "201")
                        .with(systemAdmin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    private String createClientNamed(String lspId, String name) throws Exception {
        MvcResult createdResult = mockMvc.perform(post("/api/v1/internal/admin/api-clients")
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", name,
                                "description", "M20 pagination test client",
                                "lspId", lspId,
                                "status", "ACTIVE"
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(createdResult.getResponse().getContentAsString())
                .get("id").asText();
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

    private String createIdempotencyLsp() throws Exception {
        String lspCode = "IDK" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        MvcResult lspResult = mockMvc.perform(post("/api/v1/internal/admin/lsps")
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", lspCode,
                                "name", "Idempotency LSP",
                                "status", "ACTIVE"
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(lspResult.getResponse().getContentAsString()).get("id").asText();
    }

    private long countClientsForLsp(String lspId) throws Exception {
        MvcResult listResult = mockMvc.perform(get("/api/v1/internal/admin/api-clients").with(systemAdmin()))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode list = objectMapper.readTree(listResult.getResponse().getContentAsString());
        long count = 0;
        for (JsonNode row : list) {
            if (lspId.equals(row.get("lspId").asText())) {
                count++;
            }
        }
        return count;
    }

    private void issueClientCredentialsToken(String clientId, String clientSecret) throws Exception {
        mockMvc.perform(post("/api/v1/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AuthApiResponses.ClientCredentialsRequest(
                                clientId,
                                clientSecret
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isString());
    }

    private record CreatedClientFixture(String id, String clientId, String clientSecret) {
    }
}
