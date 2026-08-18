package com.bhawana.lms.web;

import com.bhawana.lms.support.TenantContextTestExecutionListener;
import org.springframework.test.context.TestExecutionListeners;

import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bhawana.lms.domain.OpsAlert;
import com.bhawana.lms.domain.OpsAlertSeverity;
import com.bhawana.lms.domain.OpsAlertStatus;
import com.bhawana.lms.domain.OpsAlertType;
import com.bhawana.lms.repo.OpsAlertRepository;
import com.bhawana.lms.tenant.TenantScopedExecution;
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
@TestExecutionListeners(
        value = TenantContextTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS
)
class OpsAlertControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OpsAlertRepository opsAlertRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void resetAlerts() {
        TenantScopedExecution.runAsAdmin(() -> opsAlertRepository.deleteAllInBatch());
    }

    private OpsAlert seedAlert() {
        return TenantScopedExecution.callAsAdmin(() -> {
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
        });
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

        java.util.List<OpsAlert> persisted = opsAlertRepository.findAll();
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
                .andExpect(jsonPath("$.length()").value(9))
                .andExpect(jsonPath("$[?(@.code=='STALE_INTAKE')].enabled").value(true))
                .andExpect(jsonPath("$[?(@.code=='AUTH_BRUTE_FORCE')].triggerKind").value("SCHEDULED"))
                .andExpect(jsonPath("$[?(@.code=='AUTH_BRUTE_FORCE_DISTRIBUTED')].triggerKind").value("SCHEDULED"))
                .andExpect(jsonPath("$[?(@.code=='OLDEST_TRANSACTION_AGE')].triggerKind").value("SCHEDULED"));
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

    @Test
    void listAlertsAppliesDefaultLimitWhenNoQueryParamsProvided() throws Exception {
        for (int i = 0; i < 60; i++) {
            opsAlertRepository.save(new OpsAlert(
                    OpsAlertType.BORROWER_IDENTITY_CONFLICT,
                    OpsAlertSeverity.HIGH,
                    "Alert " + i,
                    "Body " + i,
                    "BORROWER",
                    null,
                    "corr-" + i,
                    null
            ));
        }

        mockMvc.perform(get("/api/v1/internal/alerts").with(opsUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(50));
    }

    @Test
    void listAlertsHonorsOffsetAndLimitInDescendingCreatedAtOrder() throws Exception {
        seedAlertsInOrder("Alert-A", "Alert-B", "Alert-C", "Alert-D", "Alert-E");

        // Newest-first: E, D, C, B, A. offset=2, limit=2 -> [C, B].
        mockMvc.perform(get("/api/v1/internal/alerts")
                        .with(opsUser())
                        .queryParam("offset", "2")
                        .queryParam("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].title").value("Alert-C"))
                .andExpect(jsonPath("$[1].title").value("Alert-B"));
    }

    @Test
    void listAlertsEmitsPaginationHeadersWhenPaginationDetailsIsOn() throws Exception {
        seedAlertsInOrder("Alert-A", "Alert-B", "Alert-C", "Alert-D", "Alert-E");

        mockMvc.perform(get("/api/v1/internal/alerts")
                        .with(opsUser())
                        .queryParam("offset", "0")
                        .queryParam("limit", "2")
                        .queryParam("paginationDetails", "ON"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(header().string("X-Total-Count", "5"))
                .andExpect(header().string("X-Limit", "2"))
                .andExpect(header().string("X-Offset", "0"));
    }

    @Test
    void listAlertsFiltersByStatusAndComposesWithPagination() throws Exception {
        // Seed three NEW + two ACKNOWLEDGED alerts. The ACKs are the two newest.
        seedAlertsInOrder("New-A", "New-B", "New-C");
        OpsAlert ackOne = opsAlertRepository.save(new OpsAlert(
                OpsAlertType.BORROWER_IDENTITY_CONFLICT,
                OpsAlertSeverity.HIGH,
                "Ack-A",
                "Body Ack-A",
                "BORROWER", null, "corr-AckA", null
        ));
        ackOne.acknowledge("ops.user", "first ack");
        opsAlertRepository.save(ackOne);
        Thread.sleep(2);
        OpsAlert ackTwo = opsAlertRepository.save(new OpsAlert(
                OpsAlertType.BORROWER_IDENTITY_CONFLICT,
                OpsAlertSeverity.HIGH,
                "Ack-B",
                "Body Ack-B",
                "BORROWER", null, "corr-AckB", null
        ));
        ackTwo.acknowledge("ops.user", "second ack");
        opsAlertRepository.save(ackTwo);

        mockMvc.perform(get("/api/v1/internal/alerts")
                        .with(opsUser())
                        .queryParam("status", "NEW")
                        .queryParam("limit", "10")
                        .queryParam("paginationDetails", "ON"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[*].status", everyItem(is("NEW"))))
                .andExpect(jsonPath("$[0].title").value("New-C"))
                .andExpect(jsonPath("$[1].title").value("New-B"))
                .andExpect(jsonPath("$[2].title").value("New-A"))
                .andExpect(header().string("X-Total-Count", "3"));
    }

    @Test
    void listAlertsRejectsLimitAboveOneThousand() throws Exception {
        mockMvc.perform(get("/api/v1/internal/alerts")
                        .with(opsUser())
                        .queryParam("limit", "1001"))
                .andExpect(status().isBadRequest());
    }

    private void seedAlertsInOrder(String... titles) throws InterruptedException {
        for (String title : titles) {
            opsAlertRepository.save(new OpsAlert(
                    OpsAlertType.BORROWER_IDENTITY_CONFLICT,
                    OpsAlertSeverity.HIGH,
                    title,
                    "Body for " + title,
                    "BORROWER",
                    null,
                    "corr-" + title,
                    null
            ));
            // Force distinct createdAt across saves so DESC ordering is deterministic
            // (Instant.now() granularity can otherwise produce ties on fast loops).
            Thread.sleep(2);
        }
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor opsUser() {
        return jwt().jwt(jwt -> jwt.subject("ops.user").claim("roles", List.of("OPS_USER")))
                .authorities(() -> "ROLE_OPS_USER");
    }
}
