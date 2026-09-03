package com.bhawana.lms.web;

import com.bhawana.lms.support.TenantContextTestExecutionListener;
import org.springframework.test.context.TestExecutionListeners;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bhawana.lms.domain.OpsAlert;
import com.bhawana.lms.domain.OpsAlertSeverity;
import com.bhawana.lms.domain.OpsAlertType;
import com.bhawana.lms.repo.LoanAccountRepository;
import com.bhawana.lms.repo.OpsAlertRepository;
import com.bhawana.lms.repo.LoanApplicationDocumentChecklistRepository;
import com.bhawana.lms.support.IntegrationTestDatabaseCleaner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
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

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestExecutionListeners(
        value = TenantContextTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS
)
class HomeDashboardControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private LoanAccountRepository loanAccountRepository;

    @Autowired
    private LoanApplicationDocumentChecklistRepository loanApplicationDocumentChecklistRepository;

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
    void systemAdminCanReadHomeOverviewWithLspBreakdown() throws Exception {
        LspFixture apex = createLsp("APEX");
        LspFixture north = createLsp("NORTH");
        ProductFixture product = createProduct();
        mapProductToLsps(product.id(), apex.id(), north.id());

        JsonNode apexLoan = createApplication(apex.id(), product.id(), "APEX-HOME-001", "ABCDE1234F");
        JsonNode northLoan = createApplication(north.id(), product.id(), "NORTH-HOME-001", "ZXCVB1234N");
        JsonNode queuedLoan = createApplication(apex.id(), product.id(), "APEX-HOME-002", "PQRST5678K");

        transitionApplication(queuedLoan.get("id").asText(), "AWAITING_APPROVAL", "Queued for approval", systemAdmin());

        approveLoan(apexLoan.get("id").asText());
        approveLoan(northLoan.get("id").asText());
        disburseLoan(apexLoan.get("id").asText());
        disburseLoan(northLoan.get("id").asText());

        opsAlertRepository.save(new OpsAlert(
                OpsAlertType.BORROWER_IDENTITY_CONFLICT,
                OpsAlertSeverity.HIGH,
                "Duplicate PAN detected",
                "Borrower onboarding conflict for dashboard coverage",
                "BORROWER",
                UUID.randomUUID(),
                "home-dashboard-test",
                null
        ));

        setInstallmentDueDate(apexLoan.get("id").asText(), 1, LocalDate.now().minusDays(120));

        mockMvc.perform(get("/api/v1/internal/home/overview")
                        .with(systemAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalDisbursedAmount").value(90000.00))
                .andExpect(jsonPath("$.totalOutstandingAmount", closeTo(99271.58, 0.01)))
                .andExpect(jsonPath("$.dpd90PlusLoanCount").value(1))
                .andExpect(jsonPath("$.dpd90PlusAmount", closeTo(49635.79, 0.01)))
                .andExpect(jsonPath("$.lspBreakdown", hasSize(2)))
                .andExpect(jsonPath("$.lspBreakdown[0].lspCode").value("APEX"))
                .andExpect(jsonPath("$.lspBreakdown[0].disbursedAmount").value(45000.00))
                .andExpect(jsonPath("$.lspBreakdown[0].outstandingAmount", closeTo(49635.79, 0.01)))
                .andExpect(jsonPath("$.lspBreakdown[0].dpd90PlusLoanCount").value(1))
                .andExpect(jsonPath("$.lspBreakdown[0].dpd90PlusAmount", closeTo(49635.79, 0.01)))
                .andExpect(jsonPath("$.lspBreakdown[0].shareOfDisbursedPercent").value(50.00))
                .andExpect(jsonPath("$.lspBreakdown[0].shareOfDpd90PlusPercent").value(100.00))
                .andExpect(jsonPath("$.lspBreakdown[0].bucketBreakdown", hasSize(5)))
                .andExpect(jsonPath("$.lspBreakdown[0].bucketBreakdown[0].bucket").value("CURRENT"))
                .andExpect(jsonPath("$.lspBreakdown[0].bucketBreakdown[0].loanCount").value(0))
                .andExpect(jsonPath("$.lspBreakdown[0].bucketBreakdown[4].bucket").value("DPD_90_PLUS"))
                .andExpect(jsonPath("$.lspBreakdown[0].bucketBreakdown[4].loanCount").value(1))
                .andExpect(jsonPath("$.lspBreakdown[0].bucketBreakdown[4].outstandingAmount", closeTo(49635.79, 0.01)))
                .andExpect(jsonPath("$.lspBreakdown[1].lspCode").value("NORTH"))
                .andExpect(jsonPath("$.lspBreakdown[1].disbursedAmount").value(45000.00))
                .andExpect(jsonPath("$.lspBreakdown[1].dpd90PlusLoanCount").value(0))
                .andExpect(jsonPath("$.lspBreakdown[1].dpd90PlusAmount").value(0.00))
                .andExpect(jsonPath("$.lspBreakdown[1].shareOfDisbursedPercent").value(50.00))
                .andExpect(jsonPath("$.lspBreakdown[1].shareOfDpd90PlusPercent").value(0.00))
                .andExpect(jsonPath("$.lspBreakdown[1].bucketBreakdown[0].bucket").value("CURRENT"))
                .andExpect(jsonPath("$.lspBreakdown[1].bucketBreakdown[0].loanCount").value(1))
                .andExpect(jsonPath("$.lspBreakdown[1].bucketBreakdown[0].outstandingAmount", closeTo(49635.79, 0.01)))
                .andExpect(jsonPath("$.applicationsAwaitingApproval").value(1))
                .andExpect(jsonPath("$.applicationsInDisbursement").value(0))
                .andExpect(jsonPath("$.avgApprovalTatHours").doesNotExist())
                .andExpect(jsonPath("$.applicationsByStatus[?(@.status == 'AWAITING_APPROVAL')].count").value(1))
                .andExpect(jsonPath("$.applicationsByStatus[?(@.status == 'DISBURSED')].count").value(2))
                .andExpect(jsonPath("$.dpdBuckets[?(@.bucket == 'DPD_90_PLUS')].count").value(1))
                .andExpect(jsonPath("$.openAlerts").value(3))
                .andExpect(jsonPath("$.openAlertSummaries", hasSize(3)))
                .andExpect(jsonPath("$.openAlertSummaries[?(@.title == 'Duplicate PAN detected')]").exists())
                .andExpect(jsonPath("$.recentApplications", hasSize(3)))
                .andExpect(jsonPath("$.recentApplications[0].status").exists())
                .andExpect(jsonPath("$.recentApplications[?(@.externalLoanId == 'APEX-HOME-002')]").exists());
    }

    @Test
    void openAlertSummariesCarryTheAlertMessageForTriage() throws Exception {
        UUID firstSubject = UUID.randomUUID();
        UUID secondSubject = UUID.randomUUID();
        opsAlertRepository.save(delinquencyAlert(
                firstSubject,
                "Loan APEX-DPD-001 is 12 days past due (overdue ₹4,231.00)."
        ));
        opsAlertRepository.save(delinquencyAlert(
                secondSubject,
                "Loan APEX-DPD-002 is 27 days past due (overdue ₹18,900.50)."
        ));

        // Both alerts share a title and severity, so the message is the only thing that tells
        // them apart on the home card.
        mockMvc.perform(get("/api/v1/internal/home/overview")
                        .with(systemAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openAlertSummaries[?(@.subjectId == '" + firstSubject + "')].message")
                        .value("Loan APEX-DPD-001 is 12 days past due (overdue ₹4,231.00)."))
                .andExpect(jsonPath("$.openAlertSummaries[?(@.subjectId == '" + secondSubject + "')].message")
                        .value("Loan APEX-DPD-002 is 27 days past due (overdue ₹18,900.50)."));
    }

    @Test
    void openAlertSummaryReportsBlankAlertMessagesAsNull() throws Exception {
        UUID subjectId = UUID.randomUUID();
        opsAlertRepository.save(delinquencyAlert(subjectId, "   "));

        MvcResult result = mockMvc.perform(get("/api/v1/internal/home/overview")
                        .with(systemAdmin()))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode summary = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("openAlertSummaries")
                .get(0);
        assertThat(summary.get("subjectId").asText()).isEqualTo(subjectId.toString());
        assertThat(summary.get("message").isNull()).isTrue();
    }

    @Test
    void nonSystemAdminCannotReadHomeOverview() throws Exception {
        mockMvc.perform(get("/api/v1/internal/home/overview")
                        .with(opsUser()))
                .andExpect(status().isForbidden());
    }

    private static OpsAlert delinquencyAlert(UUID subjectId, String message) {
        return new OpsAlert(
                OpsAlertType.DPD_BUCKET_TRANSITION,
                OpsAlertSeverity.HIGH,
                "Delinquency bucket DPD_1_30",
                message,
                "LOAN_APPLICATION",
                subjectId,
                "home-dashboard-test",
                null
        );
    }

    private ProductFixture createProduct() throws Exception {
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
                                "status", "ACTIVE"
                        ))))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode createdJson = objectMapper.readTree(createResult.getResponse().getContentAsString());
        return new ProductFixture(createdJson.get("id").asText());
    }

    private LspFixture createLsp(String prefix) throws Exception {
        String code = prefix;
        MvcResult result = mockMvc.perform(post("/api/v1/internal/admin/lsps")
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", code,
                                "name", prefix + " Finance",
                                "status", "ACTIVE"
                        ))))
                .andExpect(status().isOk())
                .andReturn();

        return new LspFixture(objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    private void mapProductToLsps(String productId, String... lspIds) throws Exception {
        mockMvc.perform(put("/api/v1/internal/admin/product-lsp-mappings/{productId}", productId)
                        .with(productAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("lspIds", List.of(lspIds)))))
                .andExpect(status().isOk());
    }

    private JsonNode createApplication(String lspId, String productId, String externalLoanId, String borrowerPan)
            throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/internal/ops/loan-applications")
                        .with(opsUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loanApplicationPayload(
                                lspId,
                                productId,
                                externalLoanId,
                                borrowerPan
                        ))))
                .andExpect(status().isOk())
                .andReturn();

        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private void approveLoan(String applicationId) throws Exception {
        transitionApplication(applicationId, "AWAITING_APPROVAL", "Ready for approval", systemAdmin());
        markAllRequiredKycDocumentsVerified(applicationId);
        transitionApplication(applicationId, "APPROVED_PENDING_DISBURSAL", "Approved for dashboard coverage", systemAdmin());
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

    private void setInstallmentDueDate(String applicationId, int installmentNumber, LocalDate dueDate) {
        UUID loanAccountId = loanAccountRepository.findByLoanApplication_Id(UUID.fromString(applicationId))
                .orElseThrow()
                .getId();
        jdbcTemplate.update(
                "update loan_repayment_schedule_installment set due_date = ?, updated_at = current_timestamp where loan_account_id = ? and installment_number = ?",
                dueDate,
                loanAccountId,
                installmentNumber
        );
    }

    private void transitionApplication(
            String applicationId,
            String targetStatus,
            String note,
            org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor actor
    ) throws Exception {
        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/status-transitions", applicationId)
                        .with(actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "targetStatus", targetStatus,
                                "note", note
                        ))))
                .andExpect(status().isOk());
    }

    private void markAllRequiredKycDocumentsVerified(String applicationId) {
        loanApplicationDocumentChecklistRepository
                .findByLoanApplication_IdOrderByCreatedAtAsc(UUID.fromString(applicationId))
                .forEach(item -> {
                    if (item.isRequired()) {
                        item.update(
                                com.bhawana.lms.domain.LoanApplicationDocumentChecklistStatus.SUBMITTED,
                                "Uploaded for dashboard test",
                                "ops.user",
                                item.getDocumentType().name().toLowerCase() + ".pdf",
                                "doc/" + item.getDocumentType().name().toLowerCase(),
                                "seed",
                                "application/pdf"
                        );
                        loanApplicationDocumentChecklistRepository.save(item);
                    }
                });
    }

    private static Map<String, Object> loanApplicationPayload(
            String lspId,
            String productId,
            String externalLoanId,
            String borrowerPan
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("lspId", lspId);
        payload.put("productId", productId);
        payload.put("externalLoanId", externalLoanId);
        payload.put("sourceChannel", "API");
        payload.put("borrowerPan", borrowerPan);
        payload.put("borrowerFullName", "Anika Sharma");
        payload.put("borrowerMobile", mobileForPan(borrowerPan));
        payload.put("borrowerEmail", "anika+" + borrowerPan.toLowerCase() + "@example.com");
        payload.put("borrowerDateOfBirth", LocalDate.of(1992, 3, 10));
        payload.put("borrowerCity", "Mumbai");
        payload.put("borrowerState", "Maharashtra");
        payload.put("borrowerEmploymentType", "SALARIED");
        payload.put("borrowerMonthlyIncome", new BigDecimal("78000.00"));
        payload.put("requestedAmount", new BigDecimal("45000.00"));
        payload.put("tenureMonths", 12);
        return payload;
    }

    private static String mobileForPan(String pan) {
        int hash = Math.abs(pan.hashCode());
        String suffix = String.format("%09d", hash % 1_000_000_000);
        return "9" + suffix;
    }

    private record ProductFixture(String id) {
    }

    private record LspFixture(String id) {
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor systemAdmin() {
        return jwt().jwt(token -> token.subject("ops.admin").claim("roles", List.of("SYSTEM_ADMIN")))
                .authorities(() -> "ROLE_SYSTEM_ADMIN");
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor productAdmin() {
        return jwt().jwt(token -> token.subject("product.admin").claim("roles", List.of("PRODUCT_ADMIN")))
                .authorities(() -> "ROLE_PRODUCT_ADMIN");
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor opsUser() {
        return jwt().jwt(token -> token.subject("ops.user").claim("roles", List.of("OPS_USER")))
                .authorities(() -> "ROLE_OPS_USER");
    }
}
