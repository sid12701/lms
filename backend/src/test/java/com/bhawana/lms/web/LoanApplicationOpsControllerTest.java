package com.bhawana.lms.web;

import com.bhawana.lms.support.TenantContextTestExecutionListener;
import org.springframework.test.context.TestExecutionListeners;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bhawana.lms.domain.OpsAlertType;
import com.bhawana.lms.repo.LoanAccountRepository;
import com.bhawana.lms.repo.LoanApplicationDocumentChecklistRepository;
import com.bhawana.lms.repo.LoanApplicationRepository;
import com.bhawana.lms.repo.LoanRepaymentScheduleInstallmentRepository;
import com.bhawana.lms.repo.LspRepository;
import com.bhawana.lms.repo.OpsAlertRepository;
import com.bhawana.lms.repo.WebhookEventDeliveryAttemptRepository;
import com.bhawana.lms.repo.WebhookEventOutboxRepository;
import com.bhawana.lms.support.IntegrationTestDatabaseCleaner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import java.time.ZoneOffset;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestExecutionListeners(
        value = TenantContextTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS
)
class LoanApplicationOpsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private LoanApplicationRepository loanApplicationRepository;

    @Autowired
    private LoanAccountRepository loanAccountRepository;

    @Autowired
    private LoanRepaymentScheduleInstallmentRepository loanRepaymentScheduleInstallmentRepository;

    @Autowired
    private LoanApplicationDocumentChecklistRepository loanApplicationDocumentChecklistRepository;

    @Autowired
    private LspRepository lspRepository;

    @Autowired
    private WebhookEventOutboxRepository webhookEventOutboxRepository;

    @Autowired
    private WebhookEventDeliveryAttemptRepository webhookEventDeliveryAttemptRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private OpsAlertRepository opsAlertRepository;

    @Autowired
    private IntegrationTestDatabaseCleaner integrationTestDatabaseCleaner;

    @BeforeEach
    void setUp() {
        integrationTestDatabaseCleaner.cleanIntegrationTestData();
    }

    @Test
    void opsUserCanCreateAndListLoanApplicationsWhileReusingBorrowersByPan() throws Exception {
        LspFixture lsp = createLsp("ACTIVE");
        ProductFixture product = createProduct("ACTIVE");
        mapProductToLsp(product.id(), lsp.id());

        String firstBorrowerId = createApplication(lsp.id(), product.id(), "EXT-001", "API", "ABCDE1234F")
                .get("borrowerId").asText();

        mockMvc.perform(post("/api/v1/internal/ops/loan-applications")
                        .with(opsUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loanApplicationPayload(
                                lsp.id(),
                                product.id(),
                                "EXT-002",
                                "PARTNER_PORTAL",
                                "ABCDE1234F",
                                "Anika Sharma",
                                "9999999999",
                                "anika.updated@example.com",
                                LocalDate.of(1994, 2, 14),
                                "Bengaluru",
                                "Karnataka",
                                "SALARIED",
                                new BigDecimal("85000.00"),
                                new BigDecimal("50000.00"),
                                12
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.borrowerId").value(firstBorrowerId))
                .andExpect(jsonPath("$.borrowerCity").value("Bengaluru"))
                .andExpect(jsonPath("$.status").value("INITIALIZED"));

        mockMvc.perform(get("/api/v1/internal/ops/loan-applications")
                        .with(opsUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].externalLoanId").value("EXT-002"))
                .andExpect(jsonPath("$[0].borrowerId").value(firstBorrowerId))
                .andExpect(jsonPath("$[1].externalLoanId").value("EXT-001"));
    }

    @Test
    void opsUserCanFilterLoanApplicationsByLspProductAndSearchQuery() throws Exception {
        LspFixture apex = createLsp("ACTIVE");
        LspFixture north = createLsp("ACTIVE");
        ProductFixture salary = createProduct("ACTIVE");
        ProductFixture merchant = createProduct("ACTIVE");
        mapProductToLsp(salary.id(), apex.id());
        mapProductToLsp(merchant.id(), north.id());

        createApplication(apex.id(), salary.id(), "EXT-100", "API", "ABCDE1234F");

        mockMvc.perform(post("/api/v1/internal/ops/loan-applications")
                        .with(opsUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loanApplicationPayload(
                                north.id(),
                                merchant.id(),
                                "NORTH-200",
                                "PARTNER_PORTAL",
                                "ZXCVB1234N",
                                "Rahul Shah",
                                "9876543210",
                                "rahul@example.com",
                                LocalDate.of(1991, 9, 12),
                                "Delhi",
                                "Delhi",
                                "SELF_EMPLOYED",
                                new BigDecimal("92000.00"),
                                new BigDecimal("45000.00"),
                                12
                        ))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/internal/ops/loan-applications")
                        .with(opsUser())
                        .queryParam("lspId", north.id())
                        .queryParam("productId", merchant.id())
                        .queryParam("q", "rahul"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].externalLoanId").value("NORTH-200"))
                .andExpect(jsonPath("$[0].borrowerFullName").value("Rahul Shah"));
    }

    @Test
    void opsUserCanPaginateLoanApplicationListingsAndReadPaginationHeaders() throws Exception {
        LspFixture lsp = createLsp("ACTIVE");
        ProductFixture product = createProduct("ACTIVE");
        mapProductToLsp(product.id(), lsp.id());

        createApplication(lsp.id(), product.id(), "EXT-001", "API", "ABCDE1234F");
        createApplication(lsp.id(), product.id(), "EXT-002", "API", "ZXCVB1234N");
        createApplication(lsp.id(), product.id(), "EXT-003", "API", "LMNOP1234Q");

        mockMvc.perform(get("/api/v1/internal/ops/loan-applications")
                        .with(opsUser())
                        .queryParam("offset", "1")
                        .queryParam("limit", "1")
                        .queryParam("paginationDetails", "ON"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].externalLoanId").value("EXT-002"))
                .andExpect(header().string("X-Total-Count", "3"))
                .andExpect(header().string("X-Limit", "1"))
                .andExpect(header().string("X-Offset", "1"));
    }

    @Test
    void opsUserCanFilterLoanApplicationsByDisbursalDate() throws Exception {
        LspFixture lsp = createLsp("ACTIVE");
        ProductFixture product = createProduct("ACTIVE");
        mapProductToLsp(product.id(), lsp.id());

        String marchApplicationId = createApplication(lsp.id(), product.id(), "EXT-MARCH", "API", "ABCDE1234F")
                .get("id").asText();
        String aprilApplicationId = createApplication(lsp.id(), product.id(), "EXT-APRIL", "API", "ZXCVB1234N")
                .get("id").asText();

        transitionApplication(marchApplicationId, "AWAITING_APPROVAL", "Started review");
        markAllRequiredKycDocumentsVerified(marchApplicationId);
        transitionApplication(marchApplicationId, "APPROVED_PENDING_DISBURSAL", "Approved after checks", null, systemAdmin());
        disburseLoan(marchApplicationId);
        setDisbursedAt(marchApplicationId, LocalDate.of(2026, 3, 10));

        transitionApplication(aprilApplicationId, "AWAITING_APPROVAL", "Started review");
        markAllRequiredKycDocumentsVerified(aprilApplicationId);
        transitionApplication(aprilApplicationId, "APPROVED_PENDING_DISBURSAL", "Approved after checks", null, systemAdmin());
        disburseLoan(aprilApplicationId);
        setDisbursedAt(aprilApplicationId, LocalDate.of(2026, 4, 5));

        mockMvc.perform(get("/api/v1/internal/ops/loan-applications")
                        .with(opsUser())
                        .queryParam("disbursalDateFrom", "2026-03-01")
                        .queryParam("disbursalDateTo", "2026-03-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].externalLoanId").value("EXT-MARCH"));
    }

    @Test
    void opsUserCanFilterLoanApplicationsByDisbursedAfterDate() throws Exception {
        LspFixture lsp = createLsp("ACTIVE");
        ProductFixture product = createProduct("ACTIVE");
        mapProductToLsp(product.id(), lsp.id());

        String marchApplicationId = createApplication(lsp.id(), product.id(), "EXT-MARCH-AFTER", "API", "ABCDE1234F")
                .get("id").asText();
        String aprilApplicationId = createApplication(lsp.id(), product.id(), "EXT-APRIL-AFTER", "API", "ZXCVB1234N")
                .get("id").asText();

        transitionApplication(marchApplicationId, "AWAITING_APPROVAL", "Started review");
        markAllRequiredKycDocumentsVerified(marchApplicationId);
        transitionApplication(marchApplicationId, "APPROVED_PENDING_DISBURSAL", "Approved after checks", null, systemAdmin());
        disburseLoan(marchApplicationId);
        setDisbursedAt(marchApplicationId, LocalDate.of(2026, 3, 10));

        transitionApplication(aprilApplicationId, "AWAITING_APPROVAL", "Started review");
        markAllRequiredKycDocumentsVerified(aprilApplicationId);
        transitionApplication(aprilApplicationId, "APPROVED_PENDING_DISBURSAL", "Approved after checks", null, systemAdmin());
        disburseLoan(aprilApplicationId);
        setDisbursedAt(aprilApplicationId, LocalDate.of(2026, 4, 5));

        mockMvc.perform(get("/api/v1/internal/ops/loan-applications")
                        .with(opsUser())
                        .queryParam("disbursalDateFrom", "2026-04-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].externalLoanId").value("EXT-APRIL-AFTER"));
    }

    @Test
    void opsUserCanFilterLoanApplicationsByLspLoanIdAndBhawLoanId() throws Exception {
        LspFixture lsp = createLsp("ACTIVE");
        ProductFixture product = createProduct("ACTIVE");
        mapProductToLsp(product.id(), lsp.id());

        String disbursedApplicationId = createApplication(lsp.id(), product.id(), "LSP-LOAN-9001", "API", "ABCDE1234F")
                .get("id").asText();
        createApplication(lsp.id(), product.id(), "LSP-LOAN-9002", "API", "ZXCVB1234N");

        transitionApplication(disbursedApplicationId, "AWAITING_APPROVAL", "Started review");
        markAllRequiredKycDocumentsVerified(disbursedApplicationId);
        transitionApplication(disbursedApplicationId, "APPROVED_PENDING_DISBURSAL", "Approved after checks", null, systemAdmin());
        disburseLoan(disbursedApplicationId);
        String accountNumber = accountNumberFor(disbursedApplicationId);

        mockMvc.perform(get("/api/v1/internal/ops/loan-applications")
                        .with(opsUser())
                        .queryParam("lspLoanId", "9001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].externalLoanId").value("LSP-LOAN-9001"))
                .andExpect(jsonPath("$[0].accountNumber").value(accountNumber));

        mockMvc.perform(get("/api/v1/internal/ops/loan-applications")
                        .with(opsUser())
                        .queryParam("bhawLoanId", accountNumber.substring(0, accountNumber.length() - 2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(disbursedApplicationId))
                .andExpect(jsonPath("$[0].accountNumber").value(accountNumber));
    }

    @Test
    void opsUserCanSearchLoanApplicationsByApplicationId() throws Exception {
        LspFixture lsp = createLsp("ACTIVE");
        ProductFixture product = createProduct("ACTIVE");
        mapProductToLsp(product.id(), lsp.id());

        JsonNode firstApplication = createApplication(lsp.id(), product.id(), "EXT-410", "API", "ABCDE1234F");
        createApplication(lsp.id(), product.id(), "EXT-411", "PARTNER_PORTAL", "ZXCVB1234N");

        mockMvc.perform(get("/api/v1/internal/ops/loan-applications")
                        .with(opsUser())
                        .queryParam("q", firstApplication.get("id").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(firstApplication.get("id").asText()))
                .andExpect(jsonPath("$[0].externalLoanId").value("EXT-410"));
    }

    @Test
    void opsUserCanFilterLoanApplicationsByStatusAndSourceChannel() throws Exception {
        LspFixture lsp = createLsp("ACTIVE");
        ProductFixture product = createProduct("ACTIVE");
        mapProductToLsp(product.id(), lsp.id());

        createApplication(lsp.id(), product.id(), "EXT-301", "API", "ABCDE1234F");

        mockMvc.perform(post("/api/v1/internal/ops/loan-applications")
                        .with(opsUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "lspId", lsp.id(),
                                "productId", product.id(),
                                "externalLoanId", "EXT-302",
                                "sourceChannel", "PARTNER_PORTAL",
                                "borrowerPan", "ZXCVB1234N",
                                "borrowerFullName", "Rahul Shah",
                                "borrowerMobile", "9876543210",
                                "borrowerEmail", "rahul@example.com",
                                "requestedAmount", new BigDecimal("45000.00"),
                                "tenureMonths", 12
                        ))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/internal/ops/loan-applications")
                        .with(opsUser())
                        .queryParam("status", "INITIALIZED")
                        .queryParam("sourceChannel", "PARTNER_PORTAL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].externalLoanId").value("EXT-302"))
                .andExpect(jsonPath("$[0].sourceChannel").value("PARTNER_PORTAL"));
    }

    @Test
    void opsUserCanInspectLoanApplicationIntakeAudit() throws Exception {
        LspFixture lsp = createLsp("ACTIVE");
        ProductFixture product = createProduct("ACTIVE");
        mapProductToLsp(product.id(), lsp.id());

        JsonNode created = createApplication(lsp.id(), product.id(), "EXT-901", "API", "ABCDE1234F");

        mockMvc.perform(get("/api/v1/internal/ops/loan-applications/{applicationId}/intake-audits", created.get("id").asText())
                        .with(opsUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].loanApplicationId").value(created.get("id").asText()))
                .andExpect(jsonPath("$[0].actorUsername").value("ops.user"))
                .andExpect(jsonPath("$[0].payloadJson", containsString("\"externalLoanId\":\"EXT-901\"")))
                .andExpect(jsonPath("$[0].payloadJson", containsString("\"sourceChannel\":\"API\"")))
                .andExpect(jsonPath("$[0].payloadJson", containsString("\"borrowerPan\":\"ABCDE1234F\"")))
                .andExpect(jsonPath("$[0].payloadJson", containsString("\"borrowerMobile\":\"" + mobileForPan("ABCDE1234F") + "\"")))
                .andExpect(jsonPath("$[0].payloadJson", containsString("\"borrowerEmail\":\"anika+abcde1234f@example.com\"")));

        mockMvc.perform(get("/api/v1/internal/ops/loan-applications/{applicationId}/document-access-audits", created.get("id").asText())
                        .with(opsUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].action").value("INTAKE_AUDITS_VIEWED"))
                .andExpect(jsonPath("$[0].actorUsername").value("ops.user"))
                .andExpect(jsonPath("$[0].summary").value("Viewed intake audit payloads"))
                .andExpect(jsonPath("$[0].documentTypes.length()").value(0))
                .andExpect(jsonPath("$[0].correlationId").isNotEmpty());
    }

    @Test
    void opsUserCanInspectLoanApplicationDetailAndStatusHistory() throws Exception {
        LspFixture lsp = createLsp("ACTIVE");
        ProductFixture product = createProduct("ACTIVE");
        mapProductToLsp(product.id(), lsp.id());

        JsonNode created = createApplication(lsp.id(), product.id(), "EXT-951", "API", "ABCDE1234F");

        mockMvc.perform(get("/api/v1/internal/ops/loan-applications/{applicationId}", created.get("id").asText())
                        .with(opsUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(created.get("id").asText()))
                .andExpect(jsonPath("$.status").value("INITIALIZED"))
                .andExpect(jsonPath("$.lastActivity.activityType").value("INTAKE_CAPTURED"))
                .andExpect(jsonPath("$.lastActivity.actorUsername").value("ops.user"))
                .andExpect(jsonPath("$.lastActivity.summary").value("Application captured from API"))
                .andExpect(jsonPath("$.borrowerDateOfBirth").value("1992-03-10"))
                .andExpect(jsonPath("$.borrowerCity").value("Mumbai"))
                .andExpect(jsonPath("$.borrowerState").value("Maharashtra"))
                .andExpect(jsonPath("$.borrowerEmploymentType").value("SALARIED"))
                .andExpect(jsonPath("$.borrowerMonthlyIncome").value(78000.00))
                .andExpect(jsonPath("$.updatedAt").exists());

        transitionApplication(created.get("id").asText(), "AWAITING_APPROVAL", "Assigned for analyst review");
        markAllRequiredKycDocumentsVerified(created.get("id").asText());
        transitionApplication(created.get("id").asText(), "APPROVED_PENDING_DISBURSAL", "Approved after validation", null, systemAdmin());

        mockMvc.perform(get("/api/v1/internal/ops/loan-applications/{applicationId}/status-transitions", created.get("id").asText())
                        .with(opsUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].fromStatus").value("AWAITING_APPROVAL"))
                .andExpect(jsonPath("$[0].toStatus").value("APPROVED_PENDING_DISBURSAL"))
                .andExpect(jsonPath("$[0].reasonCode").doesNotExist())
                .andExpect(jsonPath("$[0].note", containsString("Approved after validation")))
                .andExpect(jsonPath("$[0].note", containsString("failedRules=")))
                .andExpect(jsonPath("$[1].fromStatus").value("INITIALIZED"))
                .andExpect(jsonPath("$[1].toStatus").value("AWAITING_APPROVAL"))
                .andExpect(jsonPath("$[1].reasonCode").doesNotExist())
                .andExpect(jsonPath("$[1].note").value("Assigned for analyst review"));
    }

    @Test
    void loanDetailSurfacesLatestWorkflowActivityAcrossStatusAssignmentAndDocumentReview() throws Exception {
        LspFixture lsp = createLsp("ACTIVE");
        ProductFixture product = createProduct("ACTIVE");
        mapProductToLsp(product.id(), lsp.id());
        JsonNode created = createApplication(lsp.id(), product.id(), "EXT-952", "PARTNER_PORTAL", "ABCDE1234F");
        String applicationId = created.get("id").asText();

        transitionApplication(applicationId, "AWAITING_APPROVAL", "Ready for queue assignment");

        mockMvc.perform(get("/api/v1/internal/ops/loan-applications/{applicationId}", applicationId)
                        .with(opsUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastActivity.activityType").value("STATUS_TRANSITION"))
                .andExpect(jsonPath("$.lastActivity.summary").value("Moved from INITIALIZED to AWAITING_APPROVAL"))
                .andExpect(jsonPath("$.lastActivity.detail").value("Ready for queue assignment"));

        loanApplicationDocumentChecklistRepository
                .findByLoanApplication_IdOrderByCreatedAtAsc(UUID.fromString(applicationId))
                .stream()
                .filter(item -> item.getDocumentType() == com.bhawana.lms.domain.LoanApplicationDocumentType.PAN_CARD)
                .findFirst()
                .ifPresent(item -> {
                    item.update(
                            com.bhawana.lms.domain.LoanApplicationDocumentChecklistStatus.SUBMITTED,
                            "PAN validated against OCR",
                            "ops.user",
                            "pan.pdf",
                            "seed://pan",
                            "seed",
                            "application/pdf"
                    );
                    loanApplicationDocumentChecklistRepository.save(item);
                });

        mockMvc.perform(get("/api/v1/internal/ops/loan-applications/{applicationId}", applicationId)
                        .with(opsUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastActivity.activityType").value("DOCUMENT_REVIEW_UPDATED"))
                .andExpect(jsonPath("$.lastActivity.actorUsername").value("ops.user"))
                .andExpect(jsonPath("$.lastActivity.summary").value("Updated PAN Card to SUBMITTED"))
                .andExpect(jsonPath("$.lastActivity.detail").value("PAN validated against OCR"));
    }

    @Test
    void statusTransitionsEmitLoanAuditEvents() throws Exception {
        LspFixture lsp = createLsp("ACTIVE");
        ProductFixture product = createProduct("ACTIVE");
        mapProductToLsp(product.id(), lsp.id());

        JsonNode created = createApplication(lsp.id(), product.id(), "EXT-953", "API", "ABCDE1234F");
        String applicationId = created.get("id").asText();

        transitionApplication(applicationId, "AWAITING_APPROVAL", "Picked up for review");

        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/manual-status", applicationId)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "targetStatus", "DISBURSEMENT_RETRY",
                                "note", "Escalating to manual exception queue",
                                "reasonCode", "MANUAL_ADMIN_OVERRIDE"
                        ))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/internal/ops/loan-applications/{applicationId}/audit-events", applicationId)
                        .with(opsUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].action").value("MANUAL_STATUS_OVERRIDE"))
                .andExpect(jsonPath("$[0].fromStatus").value("AWAITING_APPROVAL"))
                .andExpect(jsonPath("$[0].toStatus").value("DISBURSEMENT_RETRY"))
                .andExpect(jsonPath("$[0].reasonCode").value("MANUAL_ADMIN_OVERRIDE"))
                .andExpect(jsonPath("$[0].note", containsString("Manual override: Escalating to manual exception queue")))
                .andExpect(jsonPath("$[0].note", containsString("failedRules=")))
                .andExpect(jsonPath("$[0].actorUsername").value("ops.admin"))
                .andExpect(jsonPath("$[1].action").value("STATUS_TRANSITION"))
                .andExpect(jsonPath("$[1].fromStatus").value("INITIALIZED"))
                .andExpect(jsonPath("$[1].toStatus").value("AWAITING_APPROVAL"))
                .andExpect(jsonPath("$[1].reasonCode").doesNotExist())
                .andExpect(jsonPath("$[1].note").value("Picked up for review"))
                .andExpect(jsonPath("$[1].actorUsername").value("ops.admin"));
    }

    @Test
    void opsUserCanInspectChecklistAndPutVerifyRejectEndpointIsRemoved() throws Exception {
        LspFixture lsp = createLsp("ACTIVE");
        ProductFixture product = createProduct("ACTIVE");
        mapProductToLsp(product.id(), lsp.id());

        JsonNode created = createApplication(lsp.id(), product.id(), "EXT-990", "API", "ABCDE1234F");
        String applicationId = created.get("id").asText();

        transitionApplication(applicationId, "AWAITING_APPROVAL", "Ready for KYC review");

        // Read remains supported (view-only checklist).
        mockMvc.perform(get("/api/v1/internal/ops/loan-applications/{applicationId}/kyc-documents", applicationId)
                        .with(opsUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(8))
                .andExpect(jsonPath("$[0].documentType").value("PAN_CARD"))
                .andExpect(jsonPath("$[0].documentDisplayName").value("PAN Card"))
                .andExpect(jsonPath("$[0].required").value(true))
                .andExpect(jsonPath("$[0].status").value("PENDING"))
                .andExpect(jsonPath("$[0].uploadedAt").doesNotExist())
                .andExpect(jsonPath("$[0].reviewReason").doesNotExist())
                .andExpect(jsonPath("$[0].rejectionReason").doesNotExist());

        // Gap #18: the verify/reject PUT is permanently removed (status collapses to PENDING|SUBMITTED|NOT_REQUIRED).
        mockMvc.perform(put("/api/v1/internal/ops/loan-applications/{applicationId}/kyc-documents/{documentType}",
                        applicationId,
                        "BANK_STATEMENT")
                        .with(opsUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "status", "SUBMITTED",
                                "note", "Bank statement attached",
                                "fileName", "bank-statement-2026-03.pdf",
                                "contentType", "application/pdf"
                        ))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void documentChecklistReadsAreRecordedInDocumentAccessAudit() throws Exception {
        LspFixture lsp = createLsp("ACTIVE");
        ProductFixture product = createProduct("ACTIVE");
        mapProductToLsp(product.id(), lsp.id());

        JsonNode created = createApplication(lsp.id(), product.id(), "EXT-993", "API", "ABCDE1234F");
        String applicationId = created.get("id").asText();

        transitionApplication(applicationId, "AWAITING_APPROVAL", "Ready for document review");

        mockMvc.perform(get("/api/v1/internal/ops/loan-applications/{applicationId}/kyc-documents", applicationId)
                        .with(opsUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(8));

        mockMvc.perform(get("/api/v1/internal/ops/loan-applications/{applicationId}/document-access-audits", applicationId)
                        .with(opsUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].action").value("CHECKLIST_VIEWED"))
                .andExpect(jsonPath("$[0].actorUsername").value("ops.user"))
                .andExpect(jsonPath("$[0].summary").value("Viewed 8 KYC documents"))
                .andExpect(jsonPath("$[0].documentTypes.length()").value(8))
                .andExpect(jsonPath("$[0].documentTypes[0]").value("PAN_CARD"))
                .andExpect(jsonPath("$[0].correlationId").isNotEmpty());
    }

    @Test
    void documentChecklistResponseNoLongerSurfacesReviewOrRejectionReasonFields() throws Exception {
        LspFixture lsp = createLsp("ACTIVE");
        ProductFixture product = createProduct("ACTIVE");
        mapProductToLsp(product.id(), lsp.id());

        JsonNode created = createApplication(lsp.id(), product.id(), "EXT-992", "API", "ABCDE1234F");
        String applicationId = created.get("id").asText();

        transitionApplication(applicationId, "AWAITING_APPROVAL", "Ready for KYC review");

        mockMvc.perform(get("/api/v1/internal/ops/loan-applications/{applicationId}/kyc-documents", applicationId)
                        .with(opsUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].reviewReason").doesNotExist())
                .andExpect(jsonPath("$[*].rejectionReason").doesNotExist())
                .andExpect(jsonPath("$[0].status").value("PENDING"));
    }

    @Test
    void approvalIsBlockedUntilRequiredKycDocumentsAreComplete() throws Exception {
        LspFixture lsp = createLsp("ACTIVE");
        ProductFixture product = createProduct("ACTIVE");
        mapProductToLsp(product.id(), lsp.id());

        JsonNode created = createApplication(lsp.id(), product.id(), "EXT-991", "API", "ABCDE1234F");
        String applicationId = created.get("id").asText();

        transitionApplication(applicationId, "AWAITING_APPROVAL", "Ready for final approval");

        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/status-transitions", applicationId)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "targetStatus", "APPROVED_PENDING_DISBURSAL",
                                "note", "Approve after checks"
                        ))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("KYC_COMPLETION_REQUIRED"))
                .andExpect(jsonPath("$.message").value("Loan application cannot be approved until required KYC documents are complete."))
                .andExpect(jsonPath("$.errorReason").value("KYC_COMPLETION_REQUIRED"))
                .andExpect(jsonPath("$.errorSource").value("Loan application cannot be approved until required KYC documents are complete."))
                .andExpect(jsonPath("$.errors[0].errorReason").value("KYC_COMPLETION_REQUIRED"))
                .andExpect(jsonPath("$.errors[0].field").value("PAN_CARD"))
                .andExpect(jsonPath("$.violations.length()").value(8))
                .andExpect(jsonPath("$.violations[0].field").value("PAN_CARD"));

        markAllRequiredKycDocumentsVerified(applicationId);

        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/status-transitions", applicationId)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "targetStatus", "APPROVED_PENDING_DISBURSAL",
                                "note", "Approve after checks"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED_PENDING_DISBURSAL"))
                .andExpect(jsonPath("$.loanAccount.accountNumber", containsString("LMS-LN-")))
                .andExpect(jsonPath("$.loanAccount.status").value("PENDING_DISBURSEMENT"))
                .andExpect(jsonPath("$.loanAccount.principalAmount").value(45000.00))
                .andExpect(jsonPath("$.loanAccount.tenureMonths").value(12))
                .andExpect(jsonPath("$.loanAccount.approvedAt").exists())
                .andExpect(jsonPath("$.loanAccount.repaymentSchedule.installmentCount").value(12))
                .andExpect(jsonPath("$.loanAccount.repaymentSchedule.installmentAmount").value(4136.32))
                .andExpect(jsonPath("$.loanAccount.repaymentSchedule.firstDueDate").exists())
                .andExpect(jsonPath("$.loanAccount.repaymentSchedule.finalDueDate").exists());

        mockMvc.perform(get("/api/v1/internal/ops/loan-applications/{applicationId}", applicationId)
                        .with(systemAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED_PENDING_DISBURSAL"))
                .andExpect(jsonPath("$.loanAccount.accountNumber", containsString("LMS-LN-")))
                .andExpect(jsonPath("$.loanAccount.status").value("PENDING_DISBURSEMENT"))
                .andExpect(jsonPath("$.loanAccount.repaymentSchedule.installmentCount").value(12))
                .andExpect(jsonPath("$.loanAccount.repaymentSchedule.installmentAmount").value(4136.32));

        mockMvc.perform(get("/api/v1/internal/ops/loan-applications/{applicationId}/repayment-schedule", applicationId)
                        .with(systemAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(12))
                .andExpect(jsonPath("$[0].installmentNumber").value(1))
                .andExpect(jsonPath("$[0].installmentAmount").value(4136.32))
                .andExpect(jsonPath("$[0].interestDue").value(693.75))
                .andExpect(jsonPath("$[0].principalDue").value(3442.57))
                .andExpect(jsonPath("$[0].dueDate").exists())
                .andExpect(jsonPath("$[11].installmentNumber").value(12))
                .andExpect(jsonPath("$[11].closingPrincipal").value(0.00));
    }

    @Test
    void systemAdminCanRecordAndListLoanPaymentTransactions() throws Exception {
        LspFixture lsp = createLsp("ACTIVE");
        ProductFixture product = createProduct("ACTIVE");
        mapProductToLsp(product.id(), lsp.id());

        JsonNode created = createApplication(lsp.id(), product.id(), "EXT-974", "API", "ABCDE1234F");
        String applicationId = created.get("id").asText();
        transitionApplication(applicationId, "AWAITING_APPROVAL", "Started review");
        markAllRequiredKycDocumentsVerified(applicationId);
        transitionApplication(applicationId, "APPROVED_PENDING_DISBURSAL", "Approved after checks", null, systemAdmin());

        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/disbursement-requests", applicationId)
                        .with(systemAdmin()))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/disbursement-requests/mock-outcome", applicationId)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("outcome", "DISBURSED"))))
                .andExpect(status().isOk());

        String installment1Id = installmentIdAt(applicationId, 1);
        String installment2Id = installmentIdAt(applicationId, 2);
        String paymentDate = LocalDate.now().minusDays(2).toString();
        String secondPaymentDate = LocalDate.now().minusDays(1).toString();

        mockMvc.perform(postInstallmentPayment(
                        applicationId,
                        installment1Id,
                        new BigDecimal("4136.32"),
                        "PAY-001",
                        "UPI",
                        UUID.randomUUID().toString(),
                        LocalDate.parse(paymentDate)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actorUsername").value("ops.admin"))
                .andExpect(jsonPath("$.amount").value(4136.32))
                .andExpect(jsonPath("$.paymentDate").value(paymentDate))
                .andExpect(jsonPath("$.reference").value("PAY-001"))
                .andExpect(jsonPath("$.channel").value("UPI"))
                .andExpect(jsonPath("$.status").value("RECEIVED"))
                .andExpect(jsonPath("$.targetInstallmentId").value(installment1Id))
                .andExpect(jsonPath("$.allocatedAmount").value(4136.32))
                .andExpect(jsonPath("$.unallocatedAmount").value(0.00))
                .andExpect(jsonPath("$.correlationId").exists());

        mockMvc.perform(postInstallmentPayment(
                        applicationId,
                        installment2Id,
                        new BigDecimal("4136.32"),
                        "PAY-002",
                        "BANK_TRANSFER",
                        UUID.randomUUID().toString(),
                        LocalDate.parse(secondPaymentDate)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reference").value("PAY-002"))
                .andExpect(jsonPath("$.allocatedAmount").value(4136.32))
                .andExpect(jsonPath("$.unallocatedAmount").value(0.00));

        mockMvc.perform(get("/api/v1/internal/ops/loan-applications/{applicationId}/payments", applicationId)
                        .with(opsUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].reference").value("PAY-002"))
                .andExpect(jsonPath("$[0].channel").value("BANK_TRANSFER"))
                .andExpect(jsonPath("$[0].status").value("RECEIVED"))
                .andExpect(jsonPath("$[0].allocatedAmount").value(4136.32))
                .andExpect(jsonPath("$[0].unallocatedAmount").value(0.00))
                .andExpect(jsonPath("$[0].createdAt").exists())
                .andExpect(jsonPath("$[0].updatedAt").exists())
                .andExpect(jsonPath("$[1].reference").value("PAY-001"))
                .andExpect(jsonPath("$[1].allocatedAmount").value(4136.32));

        mockMvc.perform(get("/api/v1/internal/ops/loan-applications/{applicationId}/repayment-schedule", applicationId)
                        .with(opsUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("PAID"))
                .andExpect(jsonPath("$[0].paidAmount").value(4136.32))
                .andExpect(jsonPath("$[0].outstandingAmount").value(0.00))
                .andExpect(jsonPath("$[1].status").value("PAID"))
                .andExpect(jsonPath("$[1].paidAmount").value(4136.32))
                .andExpect(jsonPath("$[1].outstandingAmount").value(0.00))
                .andExpect(jsonPath("$[2].status").value("PENDING"));
    }

    @Test
    void partialInstallmentPaymentIsRejected() throws Exception {
        LspFixture lsp = createLsp("ACTIVE");
        ProductFixture product = createProduct("ACTIVE");
        mapProductToLsp(product.id(), lsp.id());

        JsonNode created = createApplication(lsp.id(), product.id(), "EXT-974P", "API", "ABCDE1234F");
        String applicationId = created.get("id").asText();
        transitionApplication(applicationId, "AWAITING_APPROVAL", "Started review");
        markAllRequiredKycDocumentsVerified(applicationId);
        transitionApplication(applicationId, "APPROVED_PENDING_DISBURSAL", "Approved after checks", null, systemAdmin());
        disburseLoan(applicationId);

        String installment1Id = installmentIdAt(applicationId, 1);

        mockMvc.perform(postInstallmentPayment(
                        applicationId,
                        installment1Id,
                        new BigDecimal("1000.00"),
                        "PAY-PARTIAL-REJECT",
                        "UPI",
                        UUID.randomUUID().toString(),
                        LocalDate.now().minusDays(1)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("PAYMENT_AMOUNT_MISMATCH"));

        mockMvc.perform(get("/api/v1/internal/ops/loan-applications/{applicationId}/payments", applicationId)
                        .with(systemAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void paymentRequiresIdempotencyKeyHeader() throws Exception {
        LspFixture lsp = createLsp("ACTIVE");
        ProductFixture product = createProduct("ACTIVE");
        mapProductToLsp(product.id(), lsp.id());

        JsonNode created = createApplication(lsp.id(), product.id(), "EXT-IDEM-PAY", "API", "ABCDE1234G");
        String applicationId = created.get("id").asText();
        transitionApplication(applicationId, "AWAITING_APPROVAL", "Started review");
        markAllRequiredKycDocumentsVerified(applicationId);
        transitionApplication(applicationId, "APPROVED_PENDING_DISBURSAL", "Approved after checks", null, systemAdmin());
        disburseLoan(applicationId);

        String installment1Id = installmentIdAt(applicationId, 1);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("targetInstallmentId", installment1Id);
        body.put("amount", new BigDecimal("4136.32"));
        body.put("postedAt", LocalDate.now().toString());
        body.put("channel", "NEFT");

        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/payments", applicationId)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("Idempotency-Key header is required."));
    }

    @Test
    void systemAdminCanQuoteAndExecuteForeclosure() throws Exception {
        LspFixture lsp = createLsp("ACTIVE");
        ProductFixture product = createProduct("ACTIVE");
        mapProductToLsp(product.id(), lsp.id());

        JsonNode created = createApplication(lsp.id(), product.id(), "EXT-974B", "API", "ABCDE1234F");
        String applicationId = created.get("id").asText();
        transitionApplication(applicationId, "AWAITING_APPROVAL", "Started review");
        markAllRequiredKycDocumentsVerified(applicationId);
        transitionApplication(applicationId, "APPROVED_PENDING_DISBURSAL", "Approved after checks", null, systemAdmin());

        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/disbursement-requests", applicationId)
                        .with(systemAdmin()))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/disbursement-requests/mock-outcome", applicationId)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("outcome", "DISBURSED"))))
                .andExpect(status().isOk());

        LocalDate effectiveDate = LocalDate.now();

        MvcResult quoteResult = mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/foreclosure-quotes", applicationId)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "effectiveDate", effectiveDate.toString()
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.effectiveDate").value(effectiveDate.toString()))
                .andReturn();

        JsonNode quote = objectMapper.readTree(quoteResult.getResponse().getContentAsString());
        String quoteId = quote.get("id").asText();
        double settlementAmount = quote.get("settlementAmount").asDouble();

        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/foreclosure-quotes/{quoteId}/execute", applicationId, quoteId)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "settlementDate", effectiveDate.toString(),
                                "reference", "FC-001",
                                "note", "Borrower opted for early settlement"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXECUTED"))
                .andExpect(jsonPath("$.executedByUsername").value("ops.admin"))
                .andExpect(jsonPath("$.executedAt").exists());

        mockMvc.perform(get("/api/v1/internal/ops/loan-applications/{applicationId}", applicationId)
                        .with(systemAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FORECLOSED"))
                .andExpect(jsonPath("$.loanAccount.status").value("FORECLOSED"))
                .andExpect(jsonPath("$.loanAccount.closureReason").value("FORECLOSURE"))
                .andExpect(jsonPath("$.loanAccount.closedAt").exists())
                .andExpect(jsonPath("$.loanAccount.closedByUsername").value("ops.admin"));

        mockMvc.perform(get("/api/v1/internal/ops/loan-applications/{applicationId}/foreclosure-quotes", applicationId)
                        .with(systemAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].status").value("EXECUTED"))
                .andExpect(jsonPath("$[0].effectiveDate").value(effectiveDate.toString()))
                .andExpect(jsonPath("$[0].settlementAmount").value(settlementAmount));

        mockMvc.perform(get("/api/v1/internal/ops/loan-applications/{applicationId}/audit-events", applicationId)
                        .with(systemAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].action", org.hamcrest.Matchers.hasItem("FORECLOSURE_EXECUTED")))
                .andExpect(jsonPath("$[*].note", org.hamcrest.Matchers.hasItem(containsString("quote v1"))))
                .andExpect(jsonPath("$[*].actorUsername", org.hamcrest.Matchers.hasItem("ops.admin")));

        mockMvc.perform(get("/api/v1/internal/ops/loan-applications/{applicationId}/payments", applicationId)
                        .with(systemAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].reference").value("FC-001"))
                .andExpect(jsonPath("$[0].channel").value("FORECLOSURE_SETTLEMENT"))
                .andExpect(jsonPath("$[0].allocatedAmount").value(settlementAmount))
                .andExpect(jsonPath("$[0].unallocatedAmount").value(0.00));
    }

    @Test
    void fullRepaymentClosesLoanAccount() throws Exception {
        LspFixture lsp = createLsp("ACTIVE");
        ProductFixture product = createProduct("ACTIVE");
        mapProductToLsp(product.id(), lsp.id());

        JsonNode created = createApplication(lsp.id(), product.id(), "EXT-974A", "API", "ABCDE1234F");
        String applicationId = created.get("id").asText();
        transitionApplication(applicationId, "AWAITING_APPROVAL", "Started review");
        markAllRequiredKycDocumentsVerified(applicationId);
        transitionApplication(applicationId, "APPROVED_PENDING_DISBURSAL", "Approved after checks", null, systemAdmin());
        disburseLoan(applicationId);

        UUID loanAccountId = loanAccountRepository.findByLoanApplication_Id(UUID.fromString(applicationId))
                .orElseThrow()
                .getId();
        List<BigDecimal> installmentAmounts = loanRepaymentScheduleInstallmentRepository
                .findByLoanAccount_IdOrderByInstallmentNumberAsc(loanAccountId)
                .stream()
                .map(installment -> installment.getOutstandingAmount())
                .toList();

        JsonNode schedule = fetchRepaymentSchedule(applicationId);
        for (int index = 0; index < installmentAmounts.size(); index++) {
            BigDecimal installmentAmount = installmentAmounts.get(index);
            String installmentId = schedule.get(index).get("id").asText();
            mockMvc.perform(postInstallmentPayment(
                            applicationId,
                            installmentId,
                            installmentAmount,
                            "PAY-CLOSE-" + String.format("%03d", index + 1),
                            "BANK_TRANSFER",
                            UUID.randomUUID().toString(),
                            LocalDate.now().minusDays(installmentAmounts.size() - index)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.allocatedAmount").value(installmentAmount.doubleValue()))
                    .andExpect(jsonPath("$.unallocatedAmount").value(0.00));
        }

        mockMvc.perform(get("/api/v1/internal/ops/loan-applications/{applicationId}", applicationId)
                        .with(systemAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.loanAccount.status").value("CLOSED"))
                .andExpect(jsonPath("$.loanAccount.closureReason").value("FULLY_REPAID"))
                .andExpect(jsonPath("$.loanAccount.closedByUsername").value("ops.admin"))
                .andExpect(jsonPath("$.loanAccount.closedAt").exists());
    }

    @Test
    void systemAdminCanVersionAndExecuteForeclosureQuotes() throws Exception {
        LspFixture lsp = createLsp("ACTIVE");
        ProductFixture product = createProduct("ACTIVE");
        mapProductToLsp(product.id(), lsp.id());

        JsonNode created = createApplication(lsp.id(), product.id(), "EXT-974B", "API", "ABCDE1234F");
        String applicationId = created.get("id").asText();
        transitionApplication(applicationId, "AWAITING_APPROVAL", "Started review");
        markAllRequiredKycDocumentsVerified(applicationId);
        transitionApplication(applicationId, "APPROVED_PENDING_DISBURSAL", "Approved after checks", null, systemAdmin());
        disburseLoan(applicationId);

        mockMvc.perform(postInstallmentPayment(
                        applicationId,
                        installmentIdAt(applicationId, 1),
                        new BigDecimal("4136.32"),
                        "PAY-INSTALLMENT-001",
                        "UPI",
                        UUID.randomUUID().toString(),
                        LocalDate.now().minusDays(2)))
                .andExpect(status().isOk());

        LocalDate firstEffectiveDate = LocalDate.now().minusDays(1);
        LocalDate secondEffectiveDate = LocalDate.now();

        MvcResult firstQuoteResult = mockMvc.perform(
                        post("/api/v1/internal/ops/loan-applications/{applicationId}/foreclosure-quotes", applicationId)
                                .with(systemAdmin())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(Map.of(
                                        "effectiveDate", firstEffectiveDate.toString()
                                ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.effectiveDate").value(firstEffectiveDate.toString()))
                .andReturn();

        String firstQuoteId = objectMapper.readTree(firstQuoteResult.getResponse().getContentAsString())
                .get("id").asText();

        MvcResult secondQuoteResult = mockMvc.perform(
                        post("/api/v1/internal/ops/loan-applications/{applicationId}/foreclosure-quotes", applicationId)
                                .with(systemAdmin())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(Map.of(
                                        "effectiveDate", secondEffectiveDate.toString()
                                ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.effectiveDate").value(secondEffectiveDate.toString()))
                .andReturn();

        JsonNode secondQuote = objectMapper.readTree(secondQuoteResult.getResponse().getContentAsString());
        String secondQuoteId = secondQuote.get("id").asText();

        mockMvc.perform(
                        post("/api/v1/internal/ops/loan-applications/{applicationId}/foreclosure-quotes/{quoteId}/execute",
                                applicationId,
                                secondQuoteId)
                                .with(systemAdmin())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(Map.of(
                                        "settlementDate", secondEffectiveDate.toString(),
                                        "reference", "FC-001",
                                        "note", "Customer requested early settlement"
                                ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(secondQuoteId))
                .andExpect(jsonPath("$.status").value("EXECUTED"))
                .andExpect(jsonPath("$.executedByUsername").value("ops.admin"))
                .andExpect(jsonPath("$.executedAt").exists());

        mockMvc.perform(get("/api/v1/internal/ops/loan-applications/{applicationId}", applicationId)
                        .with(systemAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FORECLOSED"))
                .andExpect(jsonPath("$.loanAccount.status").value("FORECLOSED"))
                .andExpect(jsonPath("$.loanAccount.closureReason").value("FORECLOSURE"))
                .andExpect(jsonPath("$.loanAccount.closedByUsername").value("ops.admin"));

        mockMvc.perform(get("/api/v1/internal/ops/loan-applications/{applicationId}/foreclosure-quotes", applicationId)
                        .with(systemAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(secondQuoteId))
                .andExpect(jsonPath("$[0].status").value("EXECUTED"))
                .andExpect(jsonPath("$[0].effectiveDate").value(secondEffectiveDate.toString()))
                .andExpect(jsonPath("$[1].id").value(firstQuoteId))
                .andExpect(jsonPath("$[1].status").value("SUPERSEDED"))
                .andExpect(jsonPath("$[1].effectiveDate").value(firstEffectiveDate.toString()));

        mockMvc.perform(get("/api/v1/internal/ops/loan-applications/{applicationId}/payments", applicationId)
                        .with(systemAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].reference").value("FC-001"))
                .andExpect(jsonPath("$[0].channel").value("FORECLOSURE_SETTLEMENT"));
    }

    @Test
    void paymentTransactionsRequireDisbursedLoanAccount() throws Exception {
        LspFixture lsp = createLsp("ACTIVE");
        ProductFixture product = createProduct("ACTIVE");
        mapProductToLsp(product.id(), lsp.id());

        JsonNode created = createApplication(lsp.id(), product.id(), "EXT-975", "API", "ABCDE1234F");
        String applicationId = created.get("id").asText();
        transitionApplication(applicationId, "AWAITING_APPROVAL", "Started review");
        markAllRequiredKycDocumentsVerified(applicationId);
        transitionApplication(applicationId, "APPROVED_PENDING_DISBURSAL", "Approved after checks", null, systemAdmin());

        mockMvc.perform(postInstallmentPayment(
                        applicationId,
                        installmentIdAt(applicationId, 1),
                        new BigDecimal("4136.32"),
                        "PAY-002",
                        "BANK_TRANSFER",
                        UUID.randomUUID().toString(),
                        LocalDate.now().minusDays(1)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("REPAYMENT_NOT_ALLOWED"));
    }

    @Test
    void loanDetailAndRepaymentScheduleExposeDelinquencyBuckets() throws Exception {
        LspFixture lsp = createLsp("ACTIVE");
        ProductFixture product = createProduct("ACTIVE");
        mapProductToLsp(product.id(), lsp.id());

        JsonNode created = createApplication(lsp.id(), product.id(), "EXT-975A", "API", "ABCDE1234F");
        String applicationId = created.get("id").asText();
        transitionApplication(applicationId, "AWAITING_APPROVAL", "Started review");
        markAllRequiredKycDocumentsVerified(applicationId);
        transitionApplication(applicationId, "APPROVED_PENDING_DISBURSAL", "Approved after checks", null, systemAdmin());

        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/disbursement-requests", applicationId)
                        .with(systemAdmin()))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/disbursement-requests/mock-outcome", applicationId)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("outcome", "DISBURSED"))))
                .andExpect(status().isOk());

        UUID loanAccountId = loanAccountRepository.findByLoanApplication_Id(UUID.fromString(applicationId))
                .orElseThrow()
                .getId();
        LocalDate overdueDate = LocalDate.now().minusDays(45);
        jdbcTemplate.update(
                "update loan_repayment_schedule_installment set due_date = ?, updated_at = current_timestamp where loan_account_id = ? and installment_number = 1",
                overdueDate,
                loanAccountId
        );

        mockMvc.perform(get("/api/v1/internal/ops/loan-applications/{applicationId}", applicationId)
                        .with(opsUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loanAccount.delinquency.maxDaysPastDue").value(45))
                .andExpect(jsonPath("$.loanAccount.delinquency.bucket").value("DPD_31_60"))
                .andExpect(jsonPath("$.loanAccount.delinquency.overdueInstallmentCount").value(1))
                .andExpect(jsonPath("$.loanAccount.delinquency.overdueAmount").value(4136.32));

        mockMvc.perform(get("/api/v1/internal/ops/loan-applications/{applicationId}/repayment-schedule", applicationId)
                        .with(opsUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].daysPastDue").value(45))
                .andExpect(jsonPath("$[0].delinquencyBucket").value("DPD_31_60"))
                .andExpect(jsonPath("$[0].status").value("PENDING"))
                .andExpect(jsonPath("$[1].daysPastDue").value(0))
                .andExpect(jsonPath("$[1].delinquencyBucket").value("CURRENT"));

        // Gap #6: borrower-admin response surfaces the aggregate delinquency
        // computed across every active loan for this borrower. Here it's a
        // single active loan with one 45-day overdue installment.
        UUID borrowerId = loanApplicationRepository.findById(UUID.fromString(applicationId))
                .orElseThrow()
                .getBorrower()
                .getId();
        mockMvc.perform(get("/api/v1/internal/admin/borrowers/{borrowerId}", borrowerId)
                        .with(opsUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.delinquency.maxDaysPastDue").value(45))
                .andExpect(jsonPath("$.delinquency.bucket").value("DPD_31_60"))
                .andExpect(jsonPath("$.delinquency.overdueLoanCount").value(1))
                .andExpect(jsonPath("$.delinquency.activeOverdueAmount").value(4136.32));
    }

    @Test
    void borrowerAdminDelinquencyIsZeroForBorrowersWithNoActiveLoans() throws Exception {
        // Gap #6: a borrower with only a pre-disbursement application (no loan
        // account active yet) reports zero overdue and a null bucket.
        LspFixture lsp = createLsp("ACTIVE");
        ProductFixture product = createProduct("ACTIVE");
        mapProductToLsp(product.id(), lsp.id());

        JsonNode created = createApplication(lsp.id(), product.id(), "EXT-DPD-AGG-EMPTY", "API", "ABCDE1234F");
        UUID borrowerId = loanApplicationRepository.findById(UUID.fromString(created.get("id").asText()))
                .orElseThrow()
                .getBorrower()
                .getId();

        mockMvc.perform(get("/api/v1/internal/admin/borrowers/{borrowerId}", borrowerId)
                        .with(opsUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.delinquency.maxDaysPastDue").value(0))
                .andExpect(jsonPath("$.delinquency.overdueLoanCount").value(0))
                .andExpect(jsonPath("$.delinquency.activeOverdueAmount").value(0))
                .andExpect(jsonPath("$.delinquency.bucket").doesNotExist());
    }

    @Test
    void opsUserCannotRecordLoanPaymentTransactions() throws Exception {
        LspFixture lsp = createLsp("ACTIVE");
        ProductFixture product = createProduct("ACTIVE");
        mapProductToLsp(product.id(), lsp.id());

        JsonNode created = createApplication(lsp.id(), product.id(), "EXT-976", "API", "ABCDE1234F");
        String applicationId = created.get("id").asText();
        transitionApplication(applicationId, "AWAITING_APPROVAL", "Started review");
        markAllRequiredKycDocumentsVerified(applicationId);
        transitionApplication(applicationId, "APPROVED_PENDING_DISBURSAL", "Approved after checks", null, systemAdmin());

        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/disbursement-requests", applicationId)
                        .with(systemAdmin()))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/disbursement-requests/mock-outcome", applicationId)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("outcome", "DISBURSED"))))
                .andExpect(status().isOk());

        mockMvc.perform(postInstallmentPayment(
                        applicationId,
                        installmentIdAt(applicationId, 1),
                        new BigDecimal("4136.32"),
                        "PAY-003",
                        "UPI",
                        UUID.randomUUID().toString(),
                        LocalDate.now().minusDays(1),
                        opsUser()))
                .andExpect(status().isForbidden());
    }

    @Test
    void opsUserCannotApproveOrRejectLoanApplications() throws Exception {
        LspFixture lsp = createLsp("ACTIVE");
        ProductFixture product = createProduct("ACTIVE");
        mapProductToLsp(product.id(), lsp.id());

        JsonNode created = createApplication(lsp.id(), product.id(), "EXT-962", "API", "ABCDE1234F");
        String applicationId = created.get("id").asText();
        transitionApplication(applicationId, "AWAITING_APPROVAL", "Started review");
        markAllRequiredKycDocumentsVerified(applicationId);

        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/status-transitions", applicationId)
                        .with(opsUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "targetStatus", "APPROVED_PENDING_DISBURSAL",
                                "note", "Approve after checks"
                        ))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("ACCESS_DENIED"));

        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/status-transitions", applicationId)
                        .with(opsUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "targetStatus", "REJECTED",
                                "note", "Reject after checks",
                                "reasonCode", "FAILED_VERIFICATION"
                        ))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("ACCESS_DENIED"));
    }

    @Test
    void systemAdminCanInitiateMockDisbursementAndInspectRequestLog() throws Exception {
        LspFixture lsp = createLsp("ACTIVE");
        ProductFixture product = createProduct("ACTIVE");
        mapProductToLsp(product.id(), lsp.id());

        JsonNode created = createApplication(lsp.id(), product.id(), "EXT-968", "API", "ABCDE1234F");
        String applicationId = created.get("id").asText();
        transitionApplication(applicationId, "AWAITING_APPROVAL", "Started review");
        markAllRequiredKycDocumentsVerified(applicationId);
        transitionApplication(applicationId, "APPROVED_PENDING_DISBURSAL", "Approved after checks", null, systemAdmin());

        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/disbursement-requests", applicationId)
                        .with(systemAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED_PENDING_DISBURSAL"))
                .andExpect(jsonPath("$.loanAccount.status").value("DISBURSEMENT_REQUESTED"));

        mockMvc.perform(get("/api/v1/internal/ops/loan-applications/{applicationId}/disbursement-requests", applicationId)
                        .with(systemAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].actorUsername").value("ops.admin"))
                .andExpect(jsonPath("$[0].amount").value(43987.50))
                .andExpect(jsonPath("$[0].providerName").value("MOCK_ICICI"))
                .andExpect(jsonPath("$[0].providerRequestId", containsString("ICI")))
                .andExpect(jsonPath("$[0].providerStatus").value("SUCCESS"))
                .andExpect(jsonPath("$[0].requestPayloadJson", containsString("\"externalLoanId\":\"EXT-968\"")))
                .andExpect(jsonPath("$[0].responsePayloadJson", containsString("\"ActCode\":\"0\"")));
    }

    @Test
    void disbursementRequestRequiresApprovedLoanAndCannotBeDuplicated() throws Exception {
        LspFixture lsp = createLsp("ACTIVE");
        ProductFixture product = createProduct("ACTIVE");
        mapProductToLsp(product.id(), lsp.id());

        JsonNode created = createApplication(lsp.id(), product.id(), "EXT-969", "API", "ABCDE1234F");
        String applicationId = created.get("id").asText();

        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/disbursement-requests", applicationId)
                        .with(systemAdmin()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("DISBURSEMENT_NOT_ALLOWED"));

        transitionApplication(applicationId, "AWAITING_APPROVAL", "Started review");
        markAllRequiredKycDocumentsVerified(applicationId);
        transitionApplication(applicationId, "APPROVED_PENDING_DISBURSAL", "Approved after checks", null, systemAdmin());

        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/disbursement-requests", applicationId)
                        .with(systemAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loanAccount.status").value("DISBURSEMENT_REQUESTED"));

        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/disbursement-requests", applicationId)
                        .with(systemAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loanAccount.status").value("DISBURSEMENT_REQUESTED"))
                .andExpect(jsonPath("$.loanAccountId").isNotEmpty());
    }

    @Test
    void disbursementRequestRequiresPreDisbursalDocumentsToBeUploaded() throws Exception {
        LspFixture lsp = createLsp("ACTIVE");
        ProductFixture product = createProduct("ACTIVE");
        mapProductToLsp(product.id(), lsp.id());

        JsonNode created = createApplication(lsp.id(), product.id(), "EXT-969A", "API", "ABCDE1234F");
        String applicationId = created.get("id").asText();

        transitionApplication(applicationId, "AWAITING_APPROVAL", "Started review");
        markAllRequiredKycDocumentsVerified(applicationId);
        transitionApplication(applicationId, "APPROVED_PENDING_DISBURSAL", "Approved after checks", null, systemAdmin());
        loanApplicationDocumentChecklistRepository.findByLoanApplication_IdOrderByCreatedAtAsc(UUID.fromString(applicationId))
                .forEach(item -> {
                    if (item.getDocumentType() == com.bhawana.lms.domain.LoanApplicationDocumentType.KFS
                            || item.getDocumentType() == com.bhawana.lms.domain.LoanApplicationDocumentType.LOAN_AGREEMENT) {
                        item.update(
                                com.bhawana.lms.domain.LoanApplicationDocumentChecklistStatus.PENDING,
                                "Removed after approval for disbursement gate test",
                                "ops.user",
                                null,
                                null,
                                null,
                                null
                        );
                        loanApplicationDocumentChecklistRepository.save(item);
                    }
                });

        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/disbursement-requests", applicationId)
                        .with(systemAdmin()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("DOCUMENT_UPLOAD_REQUIRED"))
                .andExpect(jsonPath("$.violations.length()").value(2))
                .andExpect(jsonPath("$.violations[0].field").value("KFS"));
    }

    @Test
    void systemAdminCanResolveMockDisbursementOutcomes() throws Exception {
        LspFixture lsp = createLsp("ACTIVE");
        ProductFixture product = createProduct("ACTIVE");
        mapProductToLsp(product.id(), lsp.id());

        JsonNode created = createApplication(lsp.id(), product.id(), "EXT-970", "API", "ABCDE1234F");
        String applicationId = created.get("id").asText();
        transitionApplication(applicationId, "AWAITING_APPROVAL", "Started review");
        markAllRequiredKycDocumentsVerified(applicationId);
        transitionApplication(applicationId, "APPROVED_PENDING_DISBURSAL", "Approved after checks", null, systemAdmin());

        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/disbursement-requests", applicationId)
                        .with(systemAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loanAccount.status").value("DISBURSEMENT_REQUESTED"));

        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/disbursement-requests/mock-outcome", applicationId)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("outcome", "DISBURSED"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loanAccount.status").value("DISBURSED"));

        mockMvc.perform(get("/api/v1/internal/ops/loan-applications/{applicationId}/disbursement-requests", applicationId)
                        .with(systemAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].providerStatus").value("DISBURSED"))
                .andExpect(jsonPath("$[0].responsePayloadJson", containsString("\"status\":\"DISBURSED\"")))
                .andExpect(jsonPath("$[0].updatedAt").exists());
    }

    @Test
    void mockDisbursementOutcomesSupportFailureAndPendingReconciliation() throws Exception {
        LspFixture lsp = createLsp("ACTIVE");
        ProductFixture product = createProduct("ACTIVE");
        mapProductToLsp(product.id(), lsp.id());

        JsonNode created = createApplication(lsp.id(), product.id(), "EXT-971", "API", "ABCDE1234F");
        String applicationId = created.get("id").asText();
        transitionApplication(applicationId, "AWAITING_APPROVAL", "Started review");
        markAllRequiredKycDocumentsVerified(applicationId);
        transitionApplication(applicationId, "APPROVED_PENDING_DISBURSAL", "Approved after checks", null, systemAdmin());
        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/disbursement-requests", applicationId)
                        .with(systemAdmin()))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/disbursement-requests/mock-outcome", applicationId)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("outcome", "PENDING_RECONCILIATION"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loanAccount.status").value("DISBURSEMENT_PENDING_RECONCILIATION"));

        JsonNode secondCreated = createApplication(lsp.id(), product.id(), "EXT-972", "API", "ZXCVB1234N");
        String secondApplicationId = secondCreated.get("id").asText();
        transitionApplication(secondApplicationId, "AWAITING_APPROVAL", "Started review");
        markAllRequiredKycDocumentsVerified(secondApplicationId);
        transitionApplication(secondApplicationId, "APPROVED_PENDING_DISBURSAL", "Approved after checks", null, systemAdmin());
        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/disbursement-requests", secondApplicationId)
                        .with(systemAdmin()))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/disbursement-requests/mock-outcome", secondApplicationId)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("outcome", "FAILED"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loanAccount.status").value("DISBURSEMENT_FAILED"));
    }

    @Test
    void mockDisbursementOutcomeRequiresRaisedRequest() throws Exception {
        LspFixture lsp = createLsp("ACTIVE");
        ProductFixture product = createProduct("ACTIVE");
        mapProductToLsp(product.id(), lsp.id());

        JsonNode created = createApplication(lsp.id(), product.id(), "EXT-973", "API", "ABCDE1234F");
        String applicationId = created.get("id").asText();
        transitionApplication(applicationId, "AWAITING_APPROVAL", "Started review");
        markAllRequiredKycDocumentsVerified(applicationId);
        transitionApplication(applicationId, "APPROVED_PENDING_DISBURSAL", "Approved after checks", null, systemAdmin());

        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/disbursement-requests/mock-outcome", applicationId)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("outcome", "DISBURSED"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("DISBURSEMENT_NOT_REQUESTED"));
    }

    @Test
    void opsUserCannotManuallyTransitionStatus() throws Exception {
        LspFixture lsp = createLsp("ACTIVE");
        ProductFixture product = createProduct("ACTIVE");
        mapProductToLsp(product.id(), lsp.id());

        JsonNode created = createApplication(lsp.id(), product.id(), "EXT-963", "API", "ABCDE1234F");
        String applicationId = created.get("id").asText();

        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/status-transitions", applicationId)
                        .with(opsUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "targetStatus", "AWAITING_APPROVAL",
                                "note", "Started review"
                        ))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("ACCESS_DENIED"));

        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/status-transitions", applicationId)
                        .with(opsUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "targetStatus", "REJECTED",
                                "note", "Attempting to reject without approval rights",
                                "reasonCode", "FAILED_VERIFICATION"
                        ))))
                .andExpect(status().isForbidden());
    }

    @Test
    void systemAdminCanManuallyOverrideRejectedLoanBackIntoActiveQueue() throws Exception {
        LspFixture lsp = createLsp("ACTIVE");
        ProductFixture product = createProduct("ACTIVE");
        mapProductToLsp(product.id(), lsp.id());

        JsonNode created = createApplication(lsp.id(), product.id(), "EXT-964", "API", "ABCDE1234F");
        String applicationId = created.get("id").asText();

        transitionApplication(applicationId, "AWAITING_APPROVAL", "Started review");
        transitionApplication(
                applicationId,
                "REJECTED",
                "Rejected after review",
                "FAILED_VERIFICATION",
                systemAdmin()
        );

        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/manual-status", applicationId)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "targetStatus", "AWAITING_APPROVAL",
                                "note", "Reopening after borrower appeal",
                                "reasonCode", "MANUAL_ADMIN_OVERRIDE"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AWAITING_APPROVAL"))
                .andExpect(jsonPath("$.lastActivity.summary").value("Moved from REJECTED to AWAITING_APPROVAL"))
                .andExpect(jsonPath("$.lastActivity.detail", containsString("Manual override: Reopening after borrower appeal")))
                .andExpect(jsonPath("$.lastActivity.detail", containsString("failedRules=")))
                .andExpect(jsonPath("$.lastActivity.detail", containsString("[MANUAL_ADMIN_OVERRIDE]")));

        assertTrue(
                opsAlertRepository.findAll().stream()
                        .anyMatch(alert -> alert.getType() == OpsAlertType.MANUAL_RULE_ENGINE_OVERRIDE)
        );
    }

    @Test
    void opsUserCannotUseManualStatusUpdate() throws Exception {
        LspFixture lsp = createLsp("ACTIVE");
        ProductFixture product = createProduct("ACTIVE");
        mapProductToLsp(product.id(), lsp.id());

        JsonNode created = createApplication(lsp.id(), product.id(), "EXT-965", "API", "ABCDE1234F");

        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/manual-status", created.get("id").asText())
                        .with(opsUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "targetStatus", "REJECTED",
                                "note", "Force close",
                                "reasonCode", "MANUAL_ADMIN_OVERRIDE"
                        ))))
                .andExpect(status().isForbidden());
    }

    @Test
    void manualStatusUpdateCannotTargetApprovedAndRequiresNote() throws Exception {
        LspFixture lsp = createLsp("ACTIVE");
        ProductFixture product = createProduct("ACTIVE");
        mapProductToLsp(product.id(), lsp.id());

        JsonNode created = createApplication(lsp.id(), product.id(), "EXT-966", "API", "ABCDE1234F");
        String applicationId = created.get("id").asText();
        transitionApplication(applicationId, "AWAITING_APPROVAL", "Started review");

        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/manual-status", applicationId)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "targetStatus", "APPROVED_PENDING_DISBURSAL",
                                "note", "Force approve",
                                "reasonCode", "MANUAL_ADMIN_OVERRIDE"
                        ))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("MANUAL_OVERRIDE_NOT_ALLOWED"));

        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/manual-status", applicationId)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "targetStatus", "DISBURSEMENT_RETRY",
                                "note", "",
                                "reasonCode", "MANUAL_ADMIN_OVERRIDE"
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));

        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/manual-status", applicationId)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "targetStatus", "DISBURSEMENT_RETRY",
                                "note", "Needs admin override"
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));
    }

    @Test
    void disbursementRetryAndRejectTransitionsRequireReasonCode() throws Exception {
        LspFixture lsp = createLsp("ACTIVE");
        ProductFixture product = createProduct("ACTIVE");
        mapProductToLsp(product.id(), lsp.id());

        JsonNode created = createApplication(lsp.id(), product.id(), "EXT-967", "API", "ABCDE1234F");
        String applicationId = created.get("id").asText();
        transitionApplication(applicationId, "AWAITING_APPROVAL", "Started review");

        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/manual-status", applicationId)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "targetStatus", "DISBURSEMENT_RETRY",
                                "note", "Waiting for clarification"
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));

        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/status-transitions", applicationId)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "targetStatus", "REJECTED",
                                "note", "Reject after review"
                        ))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("REASON_CODE_REQUIRED"));
    }

    @Test
    void invalidLoanApplicationStatusTransitionsAreRejected() throws Exception {
        LspFixture lsp = createLsp("ACTIVE");
        ProductFixture product = createProduct("ACTIVE");
        mapProductToLsp(product.id(), lsp.id());

        JsonNode created = createApplication(lsp.id(), product.id(), "EXT-961", "API", "ABCDE1234F");

        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/status-transitions", created.get("id").asText())
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "targetStatus", "APPROVED_PENDING_DISBURSAL",
                                "note", "Skipping review"
                        ))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("INVALID_STATUS_TRANSITION"));

        transitionApplication(created.get("id").asText(), "AWAITING_APPROVAL", "Started review");
        markAllRequiredKycDocumentsVerified(created.get("id").asText());
        transitionApplication(created.get("id").asText(), "APPROVED_PENDING_DISBURSAL", "Approved after checks", null, systemAdmin());

        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/status-transitions", created.get("id").asText())
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "targetStatus", "REJECTED",
                                "note", "Cannot revert after approval",
                                "reasonCode", "FAILED_VERIFICATION"
                        ))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("INVALID_STATUS_TRANSITION"));
    }

    @Test
    void invalidLoanAmountIsRejected() throws Exception {
        LspFixture lsp = createLsp("ACTIVE");
        ProductFixture product = createProduct("ACTIVE");
        mapProductToLsp(product.id(), lsp.id());

        mockMvc.perform(post("/api/v1/internal/ops/loan-applications")
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loanApplicationPayload(
                                lsp.id(),
                                product.id(),
                                "EXT-003",
                                "API",
                                "PQRSX4321Z",
                                "Riya Kapoor",
                                "9898989898",
                                "riya@example.com",
                                LocalDate.of(1995, 1, 11),
                                "Jaipur",
                                "Rajasthan",
                                "SALARIED",
                                new BigDecimal("64000.00"),
                                new BigDecimal("999999.00"),
                                12
                        ))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("AMOUNT_OUT_OF_RANGE"));
    }

    @Test
    void duplicateExternalLoanIdForSameLspIsRejected() throws Exception {
        LspFixture lsp = createLsp("ACTIVE");
        ProductFixture product = createProduct("ACTIVE");
        mapProductToLsp(product.id(), lsp.id());

        createApplication(lsp.id(), product.id(), "EXT-010", "API", "LMNOP4321Q");

        mockMvc.perform(post("/api/v1/internal/ops/loan-applications")
                        .with(opsUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loanApplicationPayload(
                                lsp.id(),
                                product.id(),
                                "EXT-010",
                                "API",
                                "ZXCVB1234N",
                                "Rahul Shah",
                                "9876543210",
                                "rahul@example.com",
                                LocalDate.of(1991, 9, 12),
                                "Delhi",
                                "Delhi",
                                "SELF_EMPLOYED",
                                new BigDecimal("92000.00"),
                                new BigDecimal("45000.00"),
                                12
                        ))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("DUPLICATE_EXTERNAL_LOAN_ID"));
    }

    @Test
    void unmappedProductIsRejected() throws Exception {
        LspFixture lsp = createLsp("ACTIVE");
        ProductFixture product = createProduct("ACTIVE");

        mockMvc.perform(post("/api/v1/internal/ops/loan-applications")
                        .with(opsUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loanApplicationPayload(
                                lsp.id(),
                                product.id(),
                                "EXT-020",
                                "API",
                                "ABCDE1234F",
                                "Anika Sharma",
                                "9999999999",
                                "anika@example.com",
                                LocalDate.of(1992, 3, 10),
                                "Mumbai",
                                "Maharashtra",
                                "SALARIED",
                                new BigDecimal("78000.00"),
                                new BigDecimal("45000.00"),
                                12
                        ))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("PRODUCT_NOT_MAPPED"));
    }

    @Test
    void borrowerProfileFieldsAreReturnedInListAndRefreshedOnRepeatedPanIntake() throws Exception {
        LspFixture lsp = createLsp("ACTIVE");
        ProductFixture product = createProduct("ACTIVE");
        mapProductToLsp(product.id(), lsp.id());

        createApplication(lsp.id(), product.id(), "EXT-110", "API", "ABCDE1234F");

        mockMvc.perform(post("/api/v1/internal/ops/loan-applications")
                        .with(opsUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loanApplicationPayload(
                                lsp.id(),
                                product.id(),
                                "EXT-111",
                                "PARTNER_PORTAL",
                                "ABCDE1234F",
                                "Anika Sharma",
                                "9999999998",
                                "anika.refresh@example.com",
                                LocalDate.of(1992, 3, 10),
                                "Pune",
                                "Maharashtra",
                                "SELF_EMPLOYED",
                                new BigDecimal("91000.00"),
                                new BigDecimal("52000.00"),
                                18
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.borrowerCity").value("Pune"))
                .andExpect(jsonPath("$.borrowerEmploymentType").value("SELF_EMPLOYED"));

        mockMvc.perform(get("/api/v1/internal/ops/loan-applications")
                        .with(opsUser())
                        .queryParam("q", "pune"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].borrowerCity").value("Pune"))
                .andExpect(jsonPath("$[0].borrowerState").value("Maharashtra"))
                .andExpect(jsonPath("$[0].borrowerEmploymentType").value("SELF_EMPLOYED"))
                .andExpect(jsonPath("$[0].borrowerMonthlyIncome").value(91000.00));
    }

    @Test
    void loanApplicationAssignmentEndpointsAreRemoved() throws Exception {
        LspFixture lsp = createLsp("ACTIVE");
        ProductFixture product = createProduct("ACTIVE");
        mapProductToLsp(product.id(), lsp.id());

        JsonNode created = createApplication(lsp.id(), product.id(), "EXT-120", "API", "ABCDE1234F");
        String applicationId = created.get("id").asText();

        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/assignment", applicationId)
                        .with(opsUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "assigneeUsername", "ops.user",
                                "note", "retired endpoint"
                        ))))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/internal/ops/loan-applications/{applicationId}/assignment-events", applicationId)
                        .with(opsUser()))
                .andExpect(status().isNotFound());
    }

    @Test
    void loanApplicationResponsesOmitAssignmentFields() throws Exception {
        LspFixture lsp = createLsp("ACTIVE");
        ProductFixture product = createProduct("ACTIVE");
        mapProductToLsp(product.id(), lsp.id());

        JsonNode created = createApplication(lsp.id(), product.id(), "EXT-121", "API", "ABCDE1234F");
        String applicationId = created.get("id").asText();

        mockMvc.perform(get("/api/v1/internal/ops/loan-applications/{applicationId}", applicationId)
                        .with(opsUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignedToUsername").doesNotExist())
                .andExpect(jsonPath("$.assignedByUsername").doesNotExist())
                .andExpect(jsonPath("$.assignedAt").doesNotExist());

        mockMvc.perform(get("/api/v1/internal/ops/loan-applications")
                        .with(opsUser())
                        .param("page", "0")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].assignedToUsername").doesNotExist())
                .andExpect(jsonPath("$.items[0].assignedByUsername").doesNotExist())
                .andExpect(jsonPath("$.items[0].assignedAt").doesNotExist());
    }

    @Test
    void perLoanWebhookEventsEndpointReturnsScopedRowsWithStatusMapping() throws Exception {
        LspFixture lsp = createLsp("ACTIVE");
        ProductFixture product = createProduct("ACTIVE");
        mapProductToLsp(product.id(), lsp.id());
        enableLspWebhookSubscription(lsp.id(), List.of("LOAN_CREATED", "LOAN_STATUS_CHANGED"));

        JsonNode created = createApplication(lsp.id(), product.id(), "EXT-WH-001", "API", "ABCDE1234F");
        String applicationId = created.get("id").asText();
        transitionApplication(applicationId, "AWAITING_APPROVAL", "Started review");

        JsonNode otherCreated = createApplication(lsp.id(), product.id(), "EXT-WH-002", "API", "ZXCVB1234N");
        String otherApplicationId = otherCreated.get("id").asText();

        // Drive multi-status outcomes by mutating the persisted outbox rows
        // directly — this gives us deterministic PENDING / DELIVERED /
        // RETRYABLE_FAILURE / PERMANENT_FAILURE coverage without leaning on
        // the dispatch worker, and is the cleanest way to assert the
        // status-mapping contract.
        java.util.List<com.bhawana.lms.domain.WebhookEventOutbox> events =
                webhookEventOutboxRepository.findTop200ByLoanApplicationIdOrderByCreatedAtDesc(
                        UUID.fromString(applicationId));
        assertThat(events.size()).isEqualTo(2);

        // events are returned newest-first; createApplication enqueued
        // LOAN_CREATED first, then transition enqueued LOAN_STATUS_CHANGED.
        com.bhawana.lms.domain.WebhookEventOutbox statusChangedEvent = events.get(0);
        com.bhawana.lms.domain.WebhookEventOutbox createdEvent = events.get(1);

        // Mark the LOAN_CREATED row delivered with a 202 attempt
        java.time.Instant deliveredAt = java.time.Instant.now().minusSeconds(120);
        createdEvent.markDelivered(deliveredAt);
        webhookEventOutboxRepository.save(createdEvent);
        webhookEventDeliveryAttemptRepository.save(new com.bhawana.lms.domain.WebhookEventDeliveryAttempt(
                createdEvent,
                1,
                "https://partner.example.com/webhooks/lms",
                createdEvent.getEventType().name(),
                createdEvent.getId().toString(),
                String.valueOf(deliveredAt.getEpochSecond()),
                "v1=signature",
                202,
                "accepted",
                null,
                com.bhawana.lms.domain.WebhookEventDeliveryAttemptStatus.SUCCESS
        ));

        // Mark the LOAN_STATUS_CHANGED row retryable failure with a 503 attempt
        java.time.Instant retryAttemptAt = java.time.Instant.now().minusSeconds(60);
        statusChangedEvent.markRetryableFailure(retryAttemptAt, retryAttemptAt.plusSeconds(60), "503 partner unavailable");
        webhookEventOutboxRepository.save(statusChangedEvent);
        webhookEventDeliveryAttemptRepository.save(new com.bhawana.lms.domain.WebhookEventDeliveryAttempt(
                statusChangedEvent,
                1,
                "https://partner.example.com/webhooks/lms",
                statusChangedEvent.getEventType().name(),
                statusChangedEvent.getId().toString(),
                String.valueOf(retryAttemptAt.getEpochSecond()),
                "v1=signature",
                503,
                "partner unavailable",
                null,
                com.bhawana.lms.domain.WebhookEventDeliveryAttemptStatus.RETRYABLE_FAILURE
        ));

        // Manually persist two additional rows to cover DELIVERED-only,
        // PERMANENT_FAILURE, and PENDING (no attempt) cases. These rows are
        // scoped to the same loan but emitted by a different aggregate.
        com.bhawana.lms.domain.Lsp lspEntity = lspRepository.findById(UUID.fromString(lsp.id())).orElseThrow();
        com.bhawana.lms.domain.WebhookEventOutbox permanentFailureEvent = webhookEventOutboxRepository.save(
                new com.bhawana.lms.domain.WebhookEventOutbox(
                        lspEntity,
                        com.bhawana.lms.domain.WebhookEventType.LOAN_DISBURSEMENT_UPDATED,
                        "LOAN_ACCOUNT",
                        UUID.randomUUID().toString(),
                        UUID.fromString(applicationId),
                        com.bhawana.lms.domain.WebhookEventOutboxStatus.PENDING,
                        "{\"payload\":\"perm\"}",
                        null
                ));
        java.time.Instant permanentAt = java.time.Instant.now().minusSeconds(30);
        permanentFailureEvent.markPermanentFailure(permanentAt, "400 invalid payload");
        webhookEventOutboxRepository.save(permanentFailureEvent);
        webhookEventDeliveryAttemptRepository.save(new com.bhawana.lms.domain.WebhookEventDeliveryAttempt(
                permanentFailureEvent,
                1,
                "https://partner.example.com/webhooks/lms",
                permanentFailureEvent.getEventType().name(),
                permanentFailureEvent.getId().toString(),
                String.valueOf(permanentAt.getEpochSecond()),
                "v1=signature",
                400,
                "invalid payload",
                null,
                com.bhawana.lms.domain.WebhookEventDeliveryAttemptStatus.PERMANENT_FAILURE
        ));

        webhookEventOutboxRepository.save(new com.bhawana.lms.domain.WebhookEventOutbox(
                lspEntity,
                com.bhawana.lms.domain.WebhookEventType.LOAN_REPAYMENT_RECORDED,
                "LOAN_PAYMENT_TRANSACTION",
                UUID.randomUUID().toString(),
                UUID.fromString(applicationId),
                com.bhawana.lms.domain.WebhookEventOutboxStatus.PENDING,
                "{\"payload\":\"pending\"}",
                null
        ));

        // A row belonging to a different loan must NOT appear in the response.
        webhookEventOutboxRepository.save(new com.bhawana.lms.domain.WebhookEventOutbox(
                lspEntity,
                com.bhawana.lms.domain.WebhookEventType.LOAN_CREATED,
                "LOAN_APPLICATION",
                otherApplicationId,
                UUID.fromString(otherApplicationId),
                com.bhawana.lms.domain.WebhookEventOutboxStatus.PENDING,
                "{\"payload\":\"other\"}",
                null
        ));

        // OPS_USER reads the per-loan view
        MvcResult result = mockMvc.perform(get(
                        "/api/v1/internal/ops/loan-applications/{applicationId}/webhook-events",
                        applicationId)
                        .with(opsUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        java.util.Map<String, JsonNode> byEventType = new java.util.HashMap<>();
        for (JsonNode row : body) {
            byEventType.put(row.get("eventType").asText() + ":" + row.get("status").asText(), row);
        }

        JsonNode deliveredRow = byEventType.get("LOAN_CREATED:DELIVERED");
        assertThat(deliveredRow).as("delivered row").isNotNull();
        assertThat(deliveredRow.get("eventId").asText()).isEqualTo(createdEvent.getId().toString());
        assertThat(deliveredRow.get("targetUrl").asText()).isEqualTo("https://partner.example.com/webhooks/lms");
        assertThat(deliveredRow.get("attempts").asInt()).isEqualTo(1);
        assertThat(deliveredRow.get("lastResponseCode").asInt()).isEqualTo(202);

        JsonNode failedRow = byEventType.get("LOAN_STATUS_CHANGED:FAILED");
        assertThat(failedRow).as("failed row (retryable failure)").isNotNull();
        assertThat(failedRow.get("lastResponseCode").asInt()).isEqualTo(503);
        assertThat(failedRow.get("lastError").asText()).contains("503");

        JsonNode deadLetteredRow = byEventType.get("LOAN_DISBURSEMENT_UPDATED:DEAD_LETTERED");
        assertThat(deadLetteredRow).as("dead-lettered row (permanent failure)").isNotNull();
        assertThat(deadLetteredRow.get("lastResponseCode").asInt()).isEqualTo(400);

        JsonNode pendingRow = byEventType.get("LOAN_REPAYMENT_RECORDED:PENDING");
        assertThat(pendingRow).as("pending row").isNotNull();
        assertThat(pendingRow.get("attempts").asInt()).isEqualTo(0);
        assertThat(pendingRow.get("lastResponseCode").isNull()).isTrue();
        assertThat(pendingRow.get("lastError").isNull()).isTrue();
        assertThat(pendingRow.get("lastAttemptAt").isNull()).isTrue();

        // SYSTEM_ADMIN sees the same view.
        mockMvc.perform(get(
                        "/api/v1/internal/ops/loan-applications/{applicationId}/webhook-events",
                        applicationId)
                        .with(systemAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4));
    }

    @Test
    void perLoanWebhookEventsEndpointReturns404ForUnknownLoanApplication() throws Exception {
        mockMvc.perform(get(
                        "/api/v1/internal/ops/loan-applications/{applicationId}/webhook-events",
                        UUID.randomUUID())
                        .with(opsUser()))
                .andExpect(status().isNotFound());
    }

    @Test
    void perLoanWebhookEventsEndpointIsForbiddenForLspRoles() throws Exception {
        LspFixture lsp = createLsp("ACTIVE");
        ProductFixture product = createProduct("ACTIVE");
        mapProductToLsp(product.id(), lsp.id());

        JsonNode created = createApplication(lsp.id(), product.id(), "EXT-WH-LSP", "API", "ABCDE1234F");
        String applicationId = created.get("id").asText();

        mockMvc.perform(get(
                        "/api/v1/internal/ops/loan-applications/{applicationId}/webhook-events",
                        applicationId)
                        .with(lspApiClient(lsp.id())))
                .andExpect(status().isForbidden());
    }

    private void enableLspWebhookSubscription(String lspId, List<String> eventTypes) throws Exception {
        mockMvc.perform(put("/api/v1/internal/admin/lsps/{lspId}/webhook-subscription", lspId)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "enabled", true,
                                "endpointUrl", "https://partner.example.com/webhooks/lms",
                                "signingSecret", "whsec_loan_ops_test",
                                "eventTypes", eventTypes
                        ))))
                .andExpect(status().isOk());
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor lspApiClient(String lspId) {
        return jwt().jwt(jwt -> jwt.subject("lsp.api")
                        .claim("roles", List.of("LSP_API_CLIENT"))
                        .claim("lspId", lspId))
                .authorities(() -> "ROLE_LSP_API_CLIENT");
    }

    private JsonNode createApplication(
            String lspId,
            String productId,
            String externalLoanId,
            String sourceChannel,
            String borrowerPan
    ) throws Exception {
        String mobile = mobileForPan(borrowerPan);
        String email = "anika+" + borrowerPan.toLowerCase() + "@example.com";
        MvcResult result = mockMvc.perform(post("/api/v1/internal/ops/loan-applications")
                        .with(opsUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loanApplicationPayload(
                                lspId,
                                productId,
                                externalLoanId,
                                sourceChannel,
                                borrowerPan,
                                "Anika Sharma",
                                mobile,
                                email,
                                LocalDate.of(1992, 3, 10),
                                "Mumbai",
                                "Maharashtra",
                                "SALARIED",
                                new BigDecimal("78000.00"),
                                new BigDecimal("45000.00"),
                                12
                        ))))
                .andExpect(status().isOk())
                .andReturn();

        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String mobileForPan(String pan) {
        int hash = Math.abs(pan.hashCode());
        String suffix = String.format("%09d", hash % 1_000_000_000);
        return "9" + suffix;
    }

    private JsonNode fetchRepaymentSchedule(String applicationId) throws Exception {
        MvcResult result = mockMvc.perform(get(
                        "/api/v1/internal/ops/loan-applications/{applicationId}/repayment-schedule",
                        applicationId)
                        .with(systemAdmin()))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String installmentIdAt(String applicationId, int installmentNumber) throws Exception {
        for (JsonNode installment : fetchRepaymentSchedule(applicationId)) {
            if (installment.get("installmentNumber").asInt() == installmentNumber) {
                return installment.get("id").asText();
            }
        }
        throw new IllegalStateException("Installment " + installmentNumber + " not found for " + applicationId);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder postInstallmentPayment(
            String applicationId,
            String installmentId,
            BigDecimal amount,
            String reference,
            String channel,
            String idempotencyKey,
            LocalDate postedAt
    ) throws Exception {
        return postInstallmentPayment(
                applicationId,
                installmentId,
                amount,
                reference,
                channel,
                idempotencyKey,
                postedAt,
                systemAdmin()
        );
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder postInstallmentPayment(
            String applicationId,
            String installmentId,
            BigDecimal amount,
            String reference,
            String channel,
            String idempotencyKey,
            LocalDate postedAt,
            org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor actor
    ) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("targetInstallmentId", installmentId);
        body.put("amount", amount);
        body.put("postedAt", postedAt.toString());
        body.put("channel", channel);
        if (reference != null) {
            body.put("reference", reference);
        }

        return post("/api/v1/internal/ops/loan-applications/{applicationId}/payments", applicationId)
                .with(actor)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body));
    }

    private void disburseLoan(String applicationId) throws Exception {
        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/disbursement-requests", applicationId)
                        .with(systemAdmin()))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/disbursement-requests/mock-outcome", applicationId)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("outcome", "DISBURSED"))))
                .andExpect(status().isOk());
    }

    private void setDisbursedAt(String applicationId, LocalDate disbursedDate) {
        jdbcTemplate.update(
                "update loan_account set disbursed_at = ? where loan_application_id = ?",
                Timestamp.from(OffsetDateTime.of(disbursedDate.atStartOfDay(), ZoneOffset.UTC).toInstant()),
                UUID.fromString(applicationId)
        );
    }

    private String accountNumberFor(String applicationId) {
        return jdbcTemplate.queryForObject(
                "select account_number from loan_account where loan_application_id = ?",
                String.class,
                UUID.fromString(applicationId)
        );
    }

    private JsonNode transitionApplication(String applicationId, String targetStatus, String note) throws Exception {
        return transitionApplication(applicationId, targetStatus, note, null, systemAdmin());
    }

    private JsonNode transitionApplication(
            String applicationId,
            String targetStatus,
            String note,
            String reasonCode,
            org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor actor
    ) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("targetStatus", targetStatus);
        payload.put("note", note);
        if (reasonCode != null) {
            payload.put("reasonCode", reasonCode);
        }

        MvcResult result = mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/status-transitions", applicationId)
                        .with(actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andReturn();

        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static Map<String, Object> loanApplicationPayload(
            String lspId,
            String productId,
            String externalLoanId,
            String sourceChannel,
            String borrowerPan,
            String borrowerFullName,
            String borrowerMobile,
            String borrowerEmail,
            LocalDate borrowerDateOfBirth,
            String borrowerCity,
            String borrowerState,
            String borrowerEmploymentType,
            BigDecimal borrowerMonthlyIncome,
            BigDecimal requestedAmount,
            int tenureMonths
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("lspId", lspId);
        payload.put("productId", productId);
        payload.put("externalLoanId", externalLoanId);
        payload.put("sourceChannel", sourceChannel);
        payload.put("borrowerPan", borrowerPan);
        payload.put("borrowerFullName", borrowerFullName);
        payload.put("borrowerMobile", borrowerMobile);
        payload.put("borrowerEmail", borrowerEmail);
        payload.put("borrowerDateOfBirth", borrowerDateOfBirth);
        payload.put("borrowerCity", borrowerCity);
        payload.put("borrowerState", borrowerState);
        payload.put("borrowerEmploymentType", borrowerEmploymentType);
        payload.put("borrowerMonthlyIncome", borrowerMonthlyIncome);
        payload.put("requestedAmount", requestedAmount);
        payload.put("tenureMonths", tenureMonths);
        return payload;
    }

    private ProductFixture createProduct(String status) throws Exception {
        String code = "PRODUCT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        MvcResult createResult = mockMvc.perform(post("/api/v1/internal/admin/products")
                        .with(productAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", code,
                                "name", "Product " + code,
                                "minPrincipal", new BigDecimal("5000.00"),
                                "maxPrincipal", new BigDecimal("250000.00"),
                                "interestRate", new BigDecimal("18.50"),
                                "processingFeeRate", new BigDecimal("2.25"),
                                "minTenureMonths", 6,
                                "maxTenureMonths", 24,
                                "status", status
                        ))))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode createdJson = objectMapper.readTree(createResult.getResponse().getContentAsString());
        return new ProductFixture(createdJson.get("id").asText(), createdJson.get("code").asText());
    }

    private LspFixture createLsp(String status) throws Exception {
        String code = "LSP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        MvcResult lspResult = mockMvc.perform(post("/api/v1/internal/admin/lsps")
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", code,
                                "name", "LSP " + code,
                                "status", status
                        ))))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode lspJson = objectMapper.readTree(lspResult.getResponse().getContentAsString());
        return new LspFixture(lspJson.get("id").asText(), lspJson.get("code").asText());
    }

    private void mapProductToLsp(String productId, String lspId) throws Exception {
        mockMvc.perform(put("/api/v1/internal/admin/product-lsp-mappings/{productId}", productId)
                        .with(productAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("lspIds", List.of(lspId)))))
                .andExpect(status().isOk());
    }

    private void markAllRequiredKycDocumentsVerified(String applicationId) {
        loanApplicationDocumentChecklistRepository.findByLoanApplication_IdOrderByCreatedAtAsc(UUID.fromString(applicationId))
                .forEach(item -> {
                    if (item.isRequired()) {
                        item.update(
                                com.bhawana.lms.domain.LoanApplicationDocumentChecklistStatus.SUBMITTED,
                                "Uploaded for approval",
                                "ops.user",
                                item.getFileName(),
                                item.getFileReference(),
                                item.getSourceReference(),
                                item.getContentType()
                        );
                        loanApplicationDocumentChecklistRepository.save(item);
                    }
                });
    }

    private record ProductFixture(String id, String code) {
    }

    private record LspFixture(String id, String code) {
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor systemAdmin() {
        return jwt().jwt(jwt -> jwt.subject("ops.admin").claim("roles", List.of("SYSTEM_ADMIN")))
                .authorities(() -> "ROLE_SYSTEM_ADMIN");
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor productAdmin() {
        return jwt().jwt(jwt -> jwt.subject("product.admin").claim("roles", List.of("PRODUCT_ADMIN")))
                .authorities(() -> "ROLE_PRODUCT_ADMIN");
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor opsUser() {
        return jwt().jwt(jwt -> jwt.subject("ops.user").claim("roles", List.of("OPS_USER")))
                .authorities(() -> "ROLE_OPS_USER");
    }
}
