package com.bhawana.lms.web;

import com.bhawana.lms.domain.LspAuditEvent;
import com.bhawana.lms.repo.LspAuditEventRepository;
import com.bhawana.lms.support.IntegrationTestDatabaseCleaner;
import com.bhawana.lms.support.TenantContextTestExecutionListener;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Comparator;
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
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestExecutionListeners(
        value = TenantContextTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS
)
class LspAdminControllerWebhookAuditTest {

    private static final String CLIENT_IP = "203.0.113.42";
    private static final String INITIAL_URL = "https://a.example.com/hook";
    private static final String INITIAL_SECRET = "whsec_initial_secret";
    private static final String UPDATED_SECRET = "whsec_rotated_secret";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private LspAuditEventRepository lspAuditEventRepository;

    @Autowired
    private IntegrationTestDatabaseCleaner integrationTestDatabaseCleaner;

    @BeforeEach
    void setUp() {
        integrationTestDatabaseCleaner.cleanIntegrationTestData();
    }

    @Test
    void urlOnlyChangeWritesWebhookUrlChangedRow() throws Exception {
        String lspId = createLsp();
        enableWebhook(lspId, INITIAL_URL, INITIAL_SECRET, List.of("LOAN_CREATED"));
        long beforeCount = auditCountFor(lspId);

        mockMvc.perform(put("/api/v1/internal/admin/lsps/{lspId}/webhook-subscription", lspId)
                        .with(systemAdmin())
                        .header("X-Forwarded-For", CLIENT_IP)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "enabled", true,
                                "endpointUrl", "https://b.example.com/hook",
                                "signingSecret", INITIAL_SECRET,
                                "eventTypes", List.of("LOAN_CREATED")
                        ))))
                .andExpect(status().isOk());

        assertEquals(beforeCount + 1, auditCountFor(lspId));
        LspAuditEvent event = latestAuditFor(lspId);
        assertEquals("WEBHOOK_URL_CHANGED", event.getAction());
        assertEquals("ops.admin", event.getActorUsername());
        assertEquals(CLIENT_IP, event.getActorIp());
        assertFalse(event.getCorrelationId() == null || event.getCorrelationId().isBlank());
        assertEquals(INITIAL_URL, event.getDetailsJson().get("before").get("url").asText());
        assertEquals("https://b.example.com/hook", event.getDetailsJson().get("after").get("url").asText());
    }

    @Test
    void secretOnlyRotationWritesWebhookSecretRotatedWithoutSecretMaterial() throws Exception {
        String lspId = createLsp();
        enableWebhook(lspId, INITIAL_URL, INITIAL_SECRET, List.of("LOAN_CREATED"));
        long beforeCount = auditCountFor(lspId);

        mockMvc.perform(put("/api/v1/internal/admin/lsps/{lspId}/webhook-subscription", lspId)
                        .with(systemAdmin())
                        .header("X-Forwarded-For", CLIENT_IP)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "enabled", true,
                                "endpointUrl", INITIAL_URL,
                                "signingSecret", UPDATED_SECRET,
                                "eventTypes", List.of("LOAN_CREATED")
                        ))))
                .andExpect(status().isOk());

        assertEquals(beforeCount + 1, auditCountFor(lspId));
        LspAuditEvent event = latestAuditFor(lspId);
        assertEquals("WEBHOOK_SECRET_ROTATED", event.getAction());
        String detailsText = event.getDetailsJson().toString();
        assertFalse(detailsText.contains(INITIAL_SECRET));
        assertFalse(detailsText.contains(UPDATED_SECRET));
        assertFalse(detailsText.contains("$2a$"));
        assertFalse(detailsText.contains("$2b$"));
    }

    @Test
    void enableFlipWritesWebhookEnabledRow() throws Exception {
        String lspId = createLsp();
        mockMvc.perform(put("/api/v1/internal/admin/lsps/{lspId}/webhook-subscription", lspId)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "enabled", false,
                                "endpointUrl", INITIAL_URL,
                                "signingSecret", INITIAL_SECRET,
                                "eventTypes", List.of("LOAN_CREATED")
                        ))))
                .andExpect(status().isOk());
        long beforeCount = auditCountFor(lspId);

        mockMvc.perform(put("/api/v1/internal/admin/lsps/{lspId}/webhook-subscription", lspId)
                        .with(systemAdmin())
                        .header("X-Forwarded-For", CLIENT_IP)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "enabled", true,
                                "endpointUrl", INITIAL_URL,
                                "signingSecret", INITIAL_SECRET,
                                "eventTypes", List.of("LOAN_CREATED")
                        ))))
                .andExpect(status().isOk());

        assertEquals(beforeCount + 1, auditCountFor(lspId));
        LspAuditEvent event = latestAuditFor(lspId);
        assertEquals("WEBHOOK_ENABLED", event.getAction());
        assertTrue(event.getDetailsJson().get("after").get("enabled").asBoolean());
    }

    @Test
    void disableFlipWritesWebhookDisabledRow() throws Exception {
        String lspId = createLsp();
        enableWebhook(lspId, INITIAL_URL, INITIAL_SECRET, List.of("LOAN_CREATED"));
        long beforeCount = auditCountFor(lspId);

        mockMvc.perform(put("/api/v1/internal/admin/lsps/{lspId}/webhook-subscription", lspId)
                        .with(systemAdmin())
                        .header("X-Forwarded-For", CLIENT_IP)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "enabled", false,
                                "endpointUrl", INITIAL_URL,
                                "signingSecret", INITIAL_SECRET,
                                "eventTypes", List.of("LOAN_CREATED")
                        ))))
                .andExpect(status().isOk());

        assertEquals(beforeCount + 1, auditCountFor(lspId));
        LspAuditEvent event = latestAuditFor(lspId);
        assertEquals("WEBHOOK_DISABLED", event.getAction());
        assertTrue(event.getDetailsJson().get("before").get("enabled").asBoolean());
    }

    @Test
    void eventTypesOnlyChangeWritesWebhookEventTypesChangedRow() throws Exception {
        String lspId = createLsp();
        enableWebhook(lspId, INITIAL_URL, INITIAL_SECRET, List.of("LOAN_CREATED"));
        long beforeCount = auditCountFor(lspId);

        mockMvc.perform(put("/api/v1/internal/admin/lsps/{lspId}/webhook-subscription", lspId)
                        .with(systemAdmin())
                        .header("X-Forwarded-For", CLIENT_IP)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "enabled", true,
                                "endpointUrl", INITIAL_URL,
                                "signingSecret", INITIAL_SECRET,
                                "eventTypes", List.of("LOAN_CREATED", "LOAN_DISBURSEMENT_UPDATED")
                        ))))
                .andExpect(status().isOk());

        assertEquals(beforeCount + 1, auditCountFor(lspId));
        LspAuditEvent event = latestAuditFor(lspId);
        assertEquals("WEBHOOK_EVENT_TYPES_CHANGED", event.getAction());
        assertEquals(1, event.getDetailsJson().get("before").get("eventTypes").size());
        assertEquals(2, event.getDetailsJson().get("after").get("eventTypes").size());
    }

    @Test
    void multiFieldChangeEmitsMultipleRowsSharingCorrelationId() throws Exception {
        String lspId = createLsp();
        enableWebhook(lspId, INITIAL_URL, INITIAL_SECRET, List.of("LOAN_CREATED"));
        long beforeCount = auditCountFor(lspId);

        mockMvc.perform(put("/api/v1/internal/admin/lsps/{lspId}/webhook-subscription", lspId)
                        .with(systemAdmin())
                        .header("X-Forwarded-For", CLIENT_IP)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "enabled", true,
                                "endpointUrl", "https://c.example.com/hook",
                                "signingSecret", UPDATED_SECRET,
                                "eventTypes", List.of("LOAN_CREATED", "LOAN_DISBURSEMENT_UPDATED")
                        ))))
                .andExpect(status().isOk());

        assertEquals(beforeCount + 3, auditCountFor(lspId));
        List<LspAuditEvent> events = recentAuditsFor(lspId, 3);
        assertEquals(
                List.of(
                        "WEBHOOK_EVENT_TYPES_CHANGED",
                        "WEBHOOK_SECRET_ROTATED",
                        "WEBHOOK_URL_CHANGED"
                ),
                events.stream().map(LspAuditEvent::getAction).sorted().toList()
        );
        String correlationId = events.getFirst().getCorrelationId();
        assertTrue(events.stream().allMatch(event -> correlationId.equals(event.getCorrelationId())));
    }

    @Test
    void noOpPutWritesZeroAuditRows() throws Exception {
        String lspId = createLsp();
        enableWebhook(lspId, INITIAL_URL, INITIAL_SECRET, List.of("LOAN_CREATED"));
        long beforeCount = auditCountFor(lspId);

        mockMvc.perform(put("/api/v1/internal/admin/lsps/{lspId}/webhook-subscription", lspId)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "enabled", true,
                                "endpointUrl", INITIAL_URL,
                                "signingSecret", INITIAL_SECRET,
                                "eventTypes", List.of("LOAN_CREATED")
                        ))))
                .andExpect(status().isOk());

        assertEquals(beforeCount, auditCountFor(lspId));
    }

    @Test
    void errorPathsLeaveAuditTableUntouched() throws Exception {
        String lspId = createLsp();
        long beforeCount = lspAuditEventRepository.count();

        mockMvc.perform(put("/api/v1/internal/admin/lsps/{lspId}/webhook-subscription", lspId)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "enabled", true,
                                "endpointUrl", "",
                                "signingSecret", INITIAL_SECRET,
                                "eventTypes", List.of("LOAN_CREATED")
                        ))))
                .andExpect(status().isBadRequest());

        mockMvc.perform(put("/api/v1/internal/admin/lsps/{lspId}/webhook-subscription", lspId)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "enabled", true,
                                "endpointUrl", "ftp://x.example.com/hook",
                                "signingSecret", INITIAL_SECRET,
                                "eventTypes", List.of("LOAN_CREATED")
                        ))))
                .andExpect(status().isBadRequest());

        mockMvc.perform(put("/api/v1/internal/admin/lsps/{lspId}/webhook-subscription", lspId)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "enabled", true,
                                "endpointUrl", "https://127.0.0.1/hook",
                                "signingSecret", INITIAL_SECRET,
                                "eventTypes", List.of("LOAN_CREATED")
                        ))))
                .andExpect(status().isBadRequest());

        mockMvc.perform(put("/api/v1/internal/admin/lsps/{lspId}/webhook-subscription", lspId)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "enabled", true,
                                "endpointUrl", INITIAL_URL,
                                "signingSecret", INITIAL_SECRET,
                                "eventTypes", List.of()
                        ))))
                .andExpect(status().isBadRequest());

        assertEquals(beforeCount, lspAuditEventRepository.count());
    }

    @Test
    void auditEventsEndpointReturnsDetailsJsonAndActorIp() throws Exception {
        String lspId = createLsp();
        enableWebhook(lspId, INITIAL_URL, INITIAL_SECRET, List.of("LOAN_CREATED"));

        mockMvc.perform(put("/api/v1/internal/admin/lsps/{lspId}/webhook-subscription", lspId)
                        .with(systemAdmin())
                        .header("X-Forwarded-For", CLIENT_IP)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "enabled", true,
                                "endpointUrl", "https://d.example.com/hook",
                                "signingSecret", INITIAL_SECRET,
                                "eventTypes", List.of("LOAN_CREATED")
                        ))))
                .andExpect(status().isOk());

        MvcResult result = mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .get("/api/v1/internal/admin/lsps/{lspId}/audit-events", lspId)
                                .with(systemAdmin()))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode events = objectMapper.readTree(result.getResponse().getContentAsString());
        JsonNode urlChange = null;
        for (JsonNode event : events) {
            if ("WEBHOOK_URL_CHANGED".equals(event.get("action").asText())) {
                urlChange = event;
                break;
            }
        }
        assertTrue(urlChange != null);
        assertEquals(CLIENT_IP, urlChange.get("actorIp").asText());
        assertTrue(urlChange.get("detailsJson").asText().contains("https://d.example.com/hook"));
    }

    private void enableWebhook(String lspId, String url, String secret, List<String> eventTypes) throws Exception {
        mockMvc.perform(put("/api/v1/internal/admin/lsps/{lspId}/webhook-subscription", lspId)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "enabled", true,
                                "endpointUrl", url,
                                "signingSecret", secret,
                                "eventTypes", eventTypes
                        ))))
                .andExpect(status().isOk());
    }

    private String createLsp() throws Exception {
        MvcResult createResult = mockMvc.perform(post("/api/v1/internal/admin/lsps")
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", "WHK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                                "name", "Webhook Audit LSP",
                                "status", "ACTIVE"
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(createResult.getResponse().getContentAsString()).get("id").asText();
    }

    private long auditCountFor(String lspId) {
        UUID id = UUID.fromString(lspId);
        return lspAuditEventRepository.findAll().stream()
                .filter(event -> event.getLsp().getId().equals(id))
                .count();
    }

    private LspAuditEvent latestAuditFor(String lspId) {
        UUID id = UUID.fromString(lspId);
        return lspAuditEventRepository.findAll().stream()
                .filter(event -> event.getLsp().getId().equals(id))
                .max(Comparator.comparing(LspAuditEvent::getCreatedAt))
                .orElseThrow();
    }

    private List<LspAuditEvent> recentAuditsFor(String lspId, int limit) {
        UUID id = UUID.fromString(lspId);
        return lspAuditEventRepository.findAll().stream()
                .filter(event -> event.getLsp().getId().equals(id))
                .sorted(Comparator.comparing(LspAuditEvent::getCreatedAt).reversed())
                .limit(limit)
                .toList();
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor systemAdmin() {
        return jwt().jwt(token -> token.subject("ops.admin").claim("roles", List.of("SYSTEM_ADMIN")))
                .authorities(() -> "ROLE_SYSTEM_ADMIN");
    }
}
