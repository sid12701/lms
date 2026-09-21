package com.bhawana.lms.web;

import com.bhawana.lms.support.TenantContextTestExecutionListener;
import org.springframework.test.context.TestExecutionListeners;

import static org.hamcrest.Matchers.anEmptyMap;
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

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

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
    void listAlertRulesExposesEffectiveEvaluatedConfigNotStaleJson() throws Exception {
        // M06: the API must render the typed app.alert-rules.* configuration the
        // evaluator enforces — never a persisted config_json blob. The test profile
        // overrides oldest-transaction-age-seconds to 86400, so a displayed 86400
        // proves the response tracks the deployed evaluation boundary, not a seed copy.
        mockMvc.perform(get("/api/v1/internal/alerts/rules")
                        .with(jwt()
                                .jwt(jwt -> jwt
                                        .subject("ops.admin")
                                        .claim("roles", List.of("SYSTEM_ADMIN")))
                                .authorities(() -> "ROLE_SYSTEM_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].configJson").doesNotExist())
                .andExpect(jsonPath("$[?(@.code=='STALE_INTAKE')].effectiveConfig.staleHours").value(24))
                .andExpect(jsonPath("$[?(@.code=='STUCK_DISBURSEMENT')].effectiveConfig.stuckHours").value(2))
                .andExpect(jsonPath("$[?(@.code=='LSP_AUTO_REJECT_SPIKE')].effectiveConfig.rejectRatePct").value(40))
                .andExpect(jsonPath("$[?(@.code=='AUTH_BRUTE_FORCE')].effectiveConfig.threshold").value(5))
                .andExpect(jsonPath("$[?(@.code=='AUTH_BRUTE_FORCE')].effectiveConfig.windowMinutes").value(10))
                .andExpect(jsonPath("$[?(@.code=='AUTH_BRUTE_FORCE_DISTRIBUTED')].effectiveConfig.distinctIpMin").value(5))
                .andExpect(jsonPath("$[?(@.code=='OLDEST_TRANSACTION_AGE')].effectiveConfig.ageSeconds").value(86400))
                .andExpect(jsonPath("$[?(@.code=='DPD_BUCKET_TRANSITION')].effectiveConfig", everyItem(anEmptyMap())))
                .andExpect(jsonPath("$[*].configSource", everyItem(is("application-config"))));
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

    @Test
    void listAlertsFindsSevereMatchOnlyOnALaterPageWithAgreeingTotals() throws Exception {
        // The severe match is the OLDEST row: the default first page (cap 50)
        // cannot see it, so the totals must come from the full dataset.
        TenantScopedExecution.callAsAdmin(() -> {
            opsAlertRepository.save(new OpsAlert(
                    OpsAlertType.DPD_BUCKET_TRANSITION,
                    OpsAlertSeverity.CRITICAL,
                    "Severe late-page delinquency",
                    "Ninety days past due on a large ticket.",
                    "LOAN_ACCOUNT",
                    null,
                    "corr-severe",
                    null
            ));
            return null;
        });
        Thread.sleep(2);
        for (int i = 0; i < 54; i++) {
            final int index = i;
            TenantScopedExecution.callAsAdmin(() -> {
                opsAlertRepository.save(new OpsAlert(
                        OpsAlertType.BORROWER_IDENTITY_CONFLICT,
                        OpsAlertSeverity.HIGH,
                        "Routine alert " + index,
                        "Routine body " + index,
                        "BORROWER",
                        null,
                        "corr-routine-" + index,
                        null
                ));
                return null;
            });
        }

        // First page: 50 routine alerts, total covers all 55.
        mockMvc.perform(get("/api/v1/internal/alerts")
                        .with(opsUser())
                        .queryParam("paginationDetails", "ON"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(50))
                .andExpect(jsonPath("$[*].title").value(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("Severe late-page delinquency"))))
                .andExpect(header().string("X-Total-Count", "55"));

        // Second page holds the severe match.
        mockMvc.perform(get("/api/v1/internal/alerts")
                        .with(opsUser())
                        .queryParam("offset", "50")
                        .queryParam("limit", "50")
                        .queryParam("paginationDetails", "ON"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$[4].title").value("Severe late-page delinquency"))
                .andExpect(jsonPath("$[4].severity").value("CRITICAL"))
                .andExpect(header().string("X-Total-Count", "55"));

        // Severity filtering applies to the full dataset, not the first page.
        mockMvc.perform(get("/api/v1/internal/alerts")
                        .with(opsUser())
                        .queryParam("severity", "CRITICAL")
                        .queryParam("paginationDetails", "ON"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].title").value("Severe late-page delinquency"))
                .andExpect(header().string("X-Total-Count", "1"));

        // Text search finds it too.
        mockMvc.perform(get("/api/v1/internal/alerts")
                        .with(opsUser())
                        .queryParam("q", "late-page delinquency")
                        .queryParam("paginationDetails", "ON"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].severity").value("CRITICAL"))
                .andExpect(header().string("X-Total-Count", "1"));

        // Subject-type filtering composes with the same totals.
        mockMvc.perform(get("/api/v1/internal/alerts")
                        .with(opsUser())
                        .queryParam("subjectType", "LOAN_ACCOUNT")
                        .queryParam("paginationDetails", "ON"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(header().string("X-Total-Count", "1"));
    }

    @Test
    void listAlertsRejectsUnknownSeverityClearly() throws Exception {
        seedAlert();

        mockMvc.perform(get("/api/v1/internal/alerts")
                        .with(opsUser())
                        .queryParam("severity", "URGENT"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("INVALID_SEVERITY"));
    }

    @Test
    void listAlertsKeepsStableOrderAcrossPagesWhenTimestampsTie() throws Exception {
        for (int i = 0; i < 55; i++) {
            final int index = i;
            TenantScopedExecution.callAsAdmin(() -> {
                opsAlertRepository.save(new OpsAlert(
                        OpsAlertType.BORROWER_IDENTITY_CONFLICT,
                        OpsAlertSeverity.HIGH,
                        "Tied alert " + index,
                        "Tied body " + index,
                        "BORROWER",
                        null,
                        "corr-tied-" + index,
                        null
                ));
                return null;
            });
        }
        // Force every row onto the same timestamp: without the id tie-breaker
        // pagination over ties can duplicate or omit rows.
        TenantScopedExecution.callAsAdmin(() -> {
            opsAlertRepository.findAll().forEach(alert -> {
                jdbcTemplate.update(
                        "UPDATE ops_alert SET created_at = ? WHERE id = ?",
                        java.sql.Timestamp.from(java.time.Instant.parse("2026-05-01T00:00:00Z")),
                        alert.getId());
            });
            return null;
        });

        java.util.Set<String> seenIds = new java.util.HashSet<>();
        for (int page = 0; page < 6; page++) {
            org.springframework.test.web.servlet.MvcResult result = mockMvc.perform(
                            get("/api/v1/internal/alerts")
                                    .with(opsUser())
                                    .queryParam("offset", String.valueOf(page * 10))
                                    .queryParam("limit", "10")
                                    .queryParam("paginationDetails", "ON"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("X-Total-Count", "55"))
                    .andReturn();
            com.fasterxml.jackson.databind.JsonNode body = objectMapper.readTree(
                    result.getResponse().getContentAsString());
            for (com.fasterxml.jackson.databind.JsonNode row : body) {
                org.junit.jupiter.api.Assertions.assertTrue(
                        seenIds.add(row.get("id").asText()),
                        "duplicate alert id across pages: " + row.get("id").asText());
            }
        }
        org.junit.jupiter.api.Assertions.assertEquals(55, seenIds.size());
    }

    @Test
    void alertAcknowledgementRefreshFindsLatePageAlert() throws Exception {
        OpsAlert seeded = TenantScopedExecution.callAsAdmin(() -> opsAlertRepository.save(new OpsAlert(
                OpsAlertType.DPD_BUCKET_TRANSITION,
                OpsAlertSeverity.CRITICAL,
                "Severe ack-target delinquency",
                "Ninety days past due.",
                "LOAN_ACCOUNT",
                null,
                "corr-ack-target",
                null
        )));

        mockMvc.perform(post("/api/v1/internal/alerts/{id}/acknowledge", seeded.getId())
                        .with(opsUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new OpsAlertController.AcknowledgeAlertRequest("Reviewed."))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/internal/alerts")
                        .with(opsUser())
                        .queryParam("status", "ACKNOWLEDGED")
                        .queryParam("paginationDetails", "ON"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].title").value("Severe ack-target delinquency"))
                .andExpect(header().string("X-Total-Count", "1"));
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
