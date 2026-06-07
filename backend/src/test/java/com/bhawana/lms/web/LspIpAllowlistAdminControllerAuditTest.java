package com.bhawana.lms.web;

import com.bhawana.lms.domain.LspAuditEvent;
import com.bhawana.lms.repo.LspAuditEventRepository;
import com.bhawana.lms.support.IntegrationTestDatabaseCleaner;
import com.bhawana.lms.support.TenantContextTestExecutionListener;
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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestExecutionListeners(
        value = TenantContextTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS
)
class LspIpAllowlistAdminControllerAuditTest {

    private static final String CLIENT_IP = "203.0.113.77";

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
    void apiAllowlistPostWritesAddedAuditRow() throws Exception {
        String lspId = createLsp();
        long beforeCount = auditCountFor(lspId);

        MvcResult created = mockMvc.perform(post("/api/v1/internal/admin/lsps/{lspId}/api-ip-allowlist", lspId)
                        .with(systemAdmin())
                        .header("X-Forwarded-For", CLIENT_IP)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "cidr", "10.0.0.0/8",
                                "description", "Corp VPN"
                        ))))
                .andExpect(status().isCreated())
                .andReturn();

        String entryId = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();
        assertEquals(beforeCount + 1, auditCountFor(lspId));

        LspAuditEvent event = latestAuditFor(lspId);
        assertEquals("LSP_IP_ALLOWLIST_ENTRY_ADDED", event.getAction());
        assertEquals("ops.admin", event.getActorUsername());
        assertEquals(CLIENT_IP, event.getActorIp());
        assertFalse(event.getCorrelationId() == null || event.getCorrelationId().isBlank());
        assertEquals("10.0.0.0/8", event.getDetailsJson().get("cidr").asText());
        assertEquals("Corp VPN", event.getDetailsJson().get("description").asText());
        assertEquals(entryId, event.getDetailsJson().get("entryId").asText());
        assertEquals("API", event.getDetailsJson().get("surface").asText());
    }

    @Test
    void apiAllowlistDeleteWritesRemovedAuditRow() throws Exception {
        String lspId = createLsp();
        String entryId = addApiAllowlistEntry(lspId, "192.168.0.0/16", "Office");
        long beforeCount = auditCountFor(lspId);

        mockMvc.perform(delete("/api/v1/internal/admin/lsps/{lspId}/api-ip-allowlist/{entryId}", lspId, entryId)
                        .with(systemAdmin())
                        .header("X-Forwarded-For", CLIENT_IP))
                .andExpect(status().isNoContent());

        assertEquals(beforeCount + 1, auditCountFor(lspId));
        LspAuditEvent event = latestAuditFor(lspId);
        assertEquals("LSP_IP_ALLOWLIST_ENTRY_REMOVED", event.getAction());
        assertEquals("192.168.0.0/16", event.getDetailsJson().get("cidr").asText());
        assertEquals("Office", event.getDetailsJson().get("description").asText());
        assertEquals(entryId, event.getDetailsJson().get("entryId").asText());
        assertEquals("API", event.getDetailsJson().get("surface").asText());
    }

    @Test
    void uiAllowlistPostWritesAddedAuditRowWithUiSurface() throws Exception {
        String lspId = createLsp();
        long beforeCount = auditCountFor(lspId);

        mockMvc.perform(post("/api/v1/internal/admin/lsps/{lspId}/ui-ip-allowlist", lspId)
                        .with(systemAdmin())
                        .header("X-Forwarded-For", CLIENT_IP)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "cidr", "172.16.0.0/12",
                                "description", "UI VPN"
                        ))))
                .andExpect(status().isCreated());

        assertEquals(beforeCount + 1, auditCountFor(lspId));
        LspAuditEvent event = latestAuditFor(lspId);
        assertEquals("LSP_IP_ALLOWLIST_ENTRY_ADDED", event.getAction());
        assertEquals("UI", event.getDetailsJson().get("surface").asText());
    }

    @Test
    void uiAllowlistDeleteWritesRemovedAuditRowWithUiSurface() throws Exception {
        String lspId = createLsp();
        MvcResult created = mockMvc.perform(post("/api/v1/internal/admin/lsps/{lspId}/ui-ip-allowlist", lspId)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "cidr", "10.10.0.0/16",
                                "description", "Branch"
                        ))))
                .andExpect(status().isCreated())
                .andReturn();
        String entryId = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();
        long beforeCount = auditCountFor(lspId);

        mockMvc.perform(delete("/api/v1/internal/admin/lsps/{lspId}/ui-ip-allowlist/{entryId}", lspId, entryId)
                        .with(systemAdmin())
                        .header("X-Forwarded-For", CLIENT_IP))
                .andExpect(status().isNoContent());

        assertEquals(beforeCount + 1, auditCountFor(lspId));
        LspAuditEvent event = latestAuditFor(lspId);
        assertEquals("LSP_IP_ALLOWLIST_ENTRY_REMOVED", event.getAction());
        assertEquals("UI", event.getDetailsJson().get("surface").asText());
    }

    @Test
    void allowlistErrorPathsLeaveAuditTableUntouched() throws Exception {
        String lspId = createLsp();
        String existingEntryId = addApiAllowlistEntry(lspId, "10.0.0.0/8", "Existing");
        long beforeCount = lspAuditEventRepository.count();

        mockMvc.perform(post("/api/v1/internal/admin/lsps/{lspId}/api-ip-allowlist", lspId)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("cidr", "not-a-cidr"))))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/internal/admin/lsps/{lspId}/api-ip-allowlist", lspId)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("cidr", "10.0.0.0/8"))))
                .andExpect(status().isBadRequest());

        UUID unknownLspId = UUID.randomUUID();
        mockMvc.perform(post("/api/v1/internal/admin/lsps/{lspId}/api-ip-allowlist", unknownLspId)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("cidr", "10.1.0.0/8"))))
                .andExpect(status().isBadRequest());

        mockMvc.perform(delete("/api/v1/internal/admin/lsps/{lspId}/api-ip-allowlist/{entryId}", lspId, UUID.randomUUID())
                        .with(systemAdmin()))
                .andExpect(status().isBadRequest());

        String otherLspId = createLsp();
        mockMvc.perform(delete("/api/v1/internal/admin/lsps/{lspId}/api-ip-allowlist/{entryId}", otherLspId, existingEntryId)
                        .with(systemAdmin()))
                .andExpect(status().isBadRequest());

        assertEquals(beforeCount, lspAuditEventRepository.count());
    }

    private String addApiAllowlistEntry(String lspId, String cidr, String description) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/internal/admin/lsps/{lspId}/api-ip-allowlist", lspId)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "cidr", cidr,
                                "description", description
                        ))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();
    }

    private String createLsp() throws Exception {
        MvcResult createResult = mockMvc.perform(post("/api/v1/internal/admin/lsps")
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", "IPL-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                                "name", "Allowlist Audit LSP",
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

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor systemAdmin() {
        return jwt().jwt(token -> token.subject("ops.admin").claim("roles", List.of("SYSTEM_ADMIN")))
                .authorities(() -> "ROLE_SYSTEM_ADMIN");
    }
}
