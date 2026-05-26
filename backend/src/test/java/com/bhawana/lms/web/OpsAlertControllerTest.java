package com.bhawana.lms.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bhawana.lms.domain.OpsAlert;
import com.bhawana.lms.domain.OpsAlertSeverity;
import com.bhawana.lms.domain.OpsAlertStatus;
import com.bhawana.lms.domain.OpsAlertType;
import com.bhawana.lms.repo.OpsAlertRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OpsAlertControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OpsAlertRepository opsAlertRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void resetAlerts() {
        opsAlertRepository.deleteAllInBatch();
    }

    private OpsAlert seedAlert() {
        OpsAlert alert = new OpsAlert(
                OpsAlertType.BORROWER_IDENTITY_CONFLICT,
                OpsAlertSeverity.HIGH,
                "Suspicious intake",
                "Two intakes share the same mobile with different PANs.",
                "BORROWER",
                null,
                "corr-1",
                null
        );
        return opsAlertRepository.save(alert);
    }

    @Test
    void acknowledgeAcceptsOptionalNoteAndReturnsItOnResponse() throws Exception {
        OpsAlert seeded = seedAlert();

        mockMvc.perform(post("/api/v1/internal/alerts/{id}/acknowledge", seeded.getId())
                        .with(jwt()
                                .jwt(jwt -> jwt
                                        .subject("ops.user")
                                        .claim("roles", List.of("OPS_USER")))
                                .authorities(() -> "ROLE_OPS_USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new OpsAlertController.AcknowledgeAlertRequest("Investigated; KYC duplicate confirmed."))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACKNOWLEDGED"))
                .andExpect(jsonPath("$.acknowledgedByUsername").value("ops.user"))
                .andExpect(jsonPath("$.acknowledgementNote").value(
                        "Investigated; KYC duplicate confirmed."));

        OpsAlert persisted = opsAlertRepository.findById(seeded.getId()).orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals(OpsAlertStatus.ACKNOWLEDGED, persisted.getStatus());
        org.junit.jupiter.api.Assertions.assertEquals(
                "Investigated; KYC duplicate confirmed.",
                persisted.getAcknowledgementNote()
        );

        mockMvc.perform(get("/api/v1/internal/alerts").with(jwt()
                        .jwt(jwt -> jwt
                                .subject("ops.user")
                                .claim("roles", List.of("OPS_USER")))
                        .authorities(() -> "ROLE_OPS_USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].acknowledgementNote").value(
                        "Investigated; KYC duplicate confirmed."));
    }

    @Test
    void acknowledgeAcceptsEmptyBodyAndPersistsNullNote() throws Exception {
        OpsAlert seeded = seedAlert();

        mockMvc.perform(post("/api/v1/internal/alerts/{id}/acknowledge", seeded.getId())
                        .with(jwt()
                                .jwt(jwt -> jwt
                                        .subject("ops.user")
                                        .claim("roles", List.of("OPS_USER")))
                                .authorities(() -> "ROLE_OPS_USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACKNOWLEDGED"))
                .andExpect(jsonPath("$.acknowledgementNote").doesNotExist());
    }

    @Test
    void acknowledgeRejectsNotesLongerThan500Chars() throws Exception {
        OpsAlert seeded = seedAlert();
        String tooLong = "x".repeat(501);

        mockMvc.perform(post("/api/v1/internal/alerts/{id}/acknowledge", seeded.getId())
                        .with(jwt()
                                .jwt(jwt -> jwt
                                        .subject("ops.user")
                                        .claim("roles", List.of("OPS_USER")))
                                .authorities(() -> "ROLE_OPS_USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new OpsAlertController.AcknowledgeAlertRequest(tooLong))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void escalateCreatesOpsUserEscalationAlert() throws Exception {
        java.util.UUID applicationId = java.util.UUID.randomUUID();

        mockMvc.perform(post("/api/v1/internal/alerts/escalate")
                        .with(jwt()
                                .jwt(jwt -> jwt
                                        .subject("ops.user")
                                        .claim("roles", List.of("OPS_USER")))
                                .authorities(() -> "ROLE_OPS_USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new OpsAlertController.EscalateAlertRequest(
                                        "LOAN_APPLICATION",
                                        applicationId.toString(),
                                        "Loan stuck in DISBURSEMENT_RETRY for 6h",
                                        "Disbursement adapter keeps failing; ops needs admin review."))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("OPS_USER_ESCALATION"))
                .andExpect(jsonPath("$.severity").value("HIGH"))
                .andExpect(jsonPath("$.status").value("NEW"))
                .andExpect(jsonPath("$.subjectType").value("LOAN_APPLICATION"))
                .andExpect(jsonPath("$.subjectId").value(applicationId.toString()))
                .andExpect(jsonPath("$.title").value("Loan stuck in DISBURSEMENT_RETRY for 6h"))
                .andExpect(jsonPath("$.message").value("Disbursement adapter keeps failing; ops needs admin review."));

        java.util.List<OpsAlert> persisted = opsAlertRepository.findAllByOrderByCreatedAtDesc();
        org.junit.jupiter.api.Assertions.assertEquals(1, persisted.size());
        org.junit.jupiter.api.Assertions.assertEquals(
                OpsAlertType.OPS_USER_ESCALATION,
                persisted.get(0).getType()
        );
    }

    @Test
    void escalateAcceptsSystemAdminCaller() throws Exception {
        mockMvc.perform(post("/api/v1/internal/alerts/escalate")
                        .with(jwt()
                                .jwt(jwt -> jwt
                                        .subject("admin")
                                        .claim("roles", List.of("SYSTEM_ADMIN")))
                                .authorities(() -> "ROLE_SYSTEM_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new OpsAlertController.EscalateAlertRequest(
                                        "SYSTEM",
                                        null,
                                        "Manual escalation",
                                        "Needs follow-up."))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("OPS_USER_ESCALATION"))
                .andExpect(jsonPath("$.subjectType").value("SYSTEM"))
                .andExpect(jsonPath("$.subjectId").doesNotExist());
    }

    @Test
    void escalateRejectsBlankTitleOrMessage() throws Exception {
        mockMvc.perform(post("/api/v1/internal/alerts/escalate")
                        .with(jwt()
                                .jwt(jwt -> jwt
                                        .subject("ops.user")
                                        .claim("roles", List.of("OPS_USER")))
                                .authorities(() -> "ROLE_OPS_USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new OpsAlertController.EscalateAlertRequest(
                                        "LOAN_APPLICATION",
                                        java.util.UUID.randomUUID().toString(),
                                        "  ",
                                        "Body"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void escalateRejectsUnauthenticatedCaller() throws Exception {
        mockMvc.perform(post("/api/v1/internal/alerts/escalate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new OpsAlertController.EscalateAlertRequest(
                                        "SYSTEM",
                                        null,
                                        "x",
                                        "y"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void escalateRejectsLspCaller() throws Exception {
        mockMvc.perform(post("/api/v1/internal/alerts/escalate")
                        .with(jwt()
                                .jwt(jwt -> jwt
                                        .subject("lsp.read")
                                        .claim("roles", List.of("LSP_UI_READ")))
                                .authorities(() -> "ROLE_LSP_UI_READ"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new OpsAlertController.EscalateAlertRequest(
                                        "LOAN_APPLICATION",
                                        java.util.UUID.randomUUID().toString(),
                                        "Title",
                                        "Body"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void listAlertRulesReturnsSeededRulesForSystemAdmin() throws Exception {
        mockMvc.perform(get("/api/v1/internal/alerts/rules")
                        .with(jwt()
                                .jwt(jwt -> jwt
                                        .subject("ops.admin")
                                        .claim("roles", List.of("SYSTEM_ADMIN")))
                                .authorities(() -> "ROLE_SYSTEM_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(7))
                .andExpect(jsonPath("$[?(@.code=='STALE_INTAKE')].enabled").value(true))
                .andExpect(jsonPath("$[?(@.code=='WEBHOOK_DEAD_LETTER')].triggerKind").value("EVENT"));
    }

    @Test
    void listAlertRulesForbiddenForOpsUser() throws Exception {
        mockMvc.perform(get("/api/v1/internal/alerts/rules")
                        .with(jwt()
                                .jwt(jwt -> jwt
                                        .subject("ops.user")
                                        .claim("roles", List.of("OPS_USER")))
                                .authorities(() -> "ROLE_OPS_USER")))
                .andExpect(status().isForbidden());
    }
}
