package com.bhawana.lms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bhawana.lms.common.money.Money;
import com.bhawana.lms.config.TimeConfig;
import com.bhawana.lms.domain.LoanApplicationDocumentChecklistStatus;
import com.bhawana.lms.repo.LoanAccountRepository;
import com.bhawana.lms.repo.LoanApplicationDocumentChecklistRepository;
import com.bhawana.lms.repo.PortfolioKpiSnapshotRepository;
import com.bhawana.lms.support.IntegrationTestDatabaseCleaner;
import com.bhawana.lms.support.TenantContextTestExecutionListener;
import com.bhawana.lms.tenant.TenantScopedExecution;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * H11 — portfolio totals, delinquency buckets and the priority list each count their own
 * population ({@link com.bhawana.lms.repo.LoanPortfolioPopulation}). Unfunded and invalid accounts
 * carry generated, overdue schedules that must never read as debt; a closed loan still counts as
 * historical disbursed volume but not as servicing debt.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestExecutionListeners(
        value = TenantContextTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS
)
class PortfolioPopulationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private HomeDashboardService homeDashboardService;

    @Autowired
    private PortfolioKpiSnapshotComputationService portfolioKpiSnapshotComputationService;

    @Autowired
    private PortfolioKpiSnapshotRepository portfolioKpiSnapshotRepository;

    @Autowired
    private LoanAccountRepository loanAccountRepository;

    @Autowired
    private LoanApplicationDocumentChecklistRepository loanApplicationDocumentChecklistRepository;

    @Autowired
    private DisbursementIntentWorkflowService disbursementIntentWorkflowService;

    @Autowired
    private LoanDisbursementCommandService loanDisbursementCommandService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private IntegrationTestDatabaseCleaner integrationTestDatabaseCleaner;

    @BeforeEach
    void setUp() {
        integrationTestDatabaseCleaner.cleanIntegrationTestData();
    }

    @AfterEach
    void tearDown() {
        integrationTestDatabaseCleaner.cleanIntegrationTestData();
    }

    @Test
    void eachMetricCountsOnlyItsOwnPopulationInLiveFallbackAndSnapshots() throws Exception {
        // Each loan is overdue in a different bucket, so a leak shows up as a wrong bucket count.
        UUID unfunded = seedApprovedLoan();
        setFirstInstallmentDueDate(unfunded, LocalDate.now(TimeConfig.BUSINESS_ZONE).minusDays(45));

        UUID invalid = seedApprovedLoan();
        setFirstInstallmentDueDate(invalid, LocalDate.now(TimeConfig.BUSINESS_ZONE).minusDays(70));
        forceLoanState(invalid, "INVALID", "INVALID");

        UUID active = seedDisbursedLoan();
        setFirstInstallmentDueDate(active, LocalDate.now(TimeConfig.BUSINESS_ZONE).minusDays(10));

        UUID closed = seedDisbursedLoan();
        setFirstInstallmentDueDate(closed, LocalDate.now(TimeConfig.BUSINESS_ZONE).minusDays(120));
        forceLoanState(closed, "CLOSED", "CLOSED");

        BigDecimal expectedDisbursed = principalOf(active).add(principalOf(closed));
        BigDecimal expectedOutstanding = outstandingOf(active);
        BigDecimal expectedOverdue = firstInstallmentOutstandingOf(active);
        assertThat(expectedOutstanding).isPositive();
        assertThat(expectedOverdue).isPositive();

        // No snapshot exists yet, so the dashboard computes one live (the fallback path).
        HomeDashboardService.HomeDashboardSummary live = summary();
        assertPopulations(live, active, expectedDisbursed, expectedOutstanding);
        assertThat(globalOverdue()).isEqualByComparingTo(expectedOverdue);

        // A later scheduled snapshot over the same data must agree with the live computation.
        TenantScopedExecution.runAsAdmin(portfolioKpiSnapshotComputationService::computeAndPersistSnapshots);
        HomeDashboardService.HomeDashboardSummary snapshot = summary();
        assertPopulations(snapshot, active, expectedDisbursed, expectedOutstanding);
        assertThat(globalOverdue()).isEqualByComparingTo(expectedOverdue);
        assertThat(snapshot.dpdBuckets()).isEqualTo(live.dpdBuckets());
        assertThat(snapshot.lspBreakdown()).isEqualTo(live.lspBreakdown());

        // The unfunded pipeline stays visible through its own, separate status counts.
        assertThat(snapshot.applicationsInDisbursement()).isEqualTo(1);
    }

    private void assertPopulations(
            HomeDashboardService.HomeDashboardSummary summary,
            UUID active,
            BigDecimal expectedDisbursed,
            BigDecimal expectedOutstanding
    ) {
        // Historical disbursed: active + closed. Funded servicing: active only.
        assertThat(summary.totalDisbursedAmount()).isEqualByComparingTo(expectedDisbursed);
        assertThat(summary.totalOutstandingAmount()).isEqualByComparingTo(expectedOutstanding);
        assertThat(summary.dpdBuckets()).containsExactly(
                new HomeDashboardService.DpdBucketCount("CURRENT", 0),
                new HomeDashboardService.DpdBucketCount("DPD_1_30", 1),
                new HomeDashboardService.DpdBucketCount("DPD_31_60", 0),
                new HomeDashboardService.DpdBucketCount("DPD_61_90", 0),
                new HomeDashboardService.DpdBucketCount("DPD_90_PLUS", 0)
        );
        assertThat(summary.dpd90PlusLoanCount()).isZero();
        assertThat(summary.dpd90PlusAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(summary.priorityAccounts())
                .extracting(HomeDashboardService.PriorityAccount::applicationId)
                .containsExactly(active.toString());
        assertThat(summary.priorityAccounts().get(0).daysPastDue()).isEqualTo(10);

        BigDecimal lspOutstanding = summary.lspBreakdown().stream()
                .map(HomeDashboardService.LspBreakdown::outstandingAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal lspDisbursed = summary.lspBreakdown().stream()
                .map(HomeDashboardService.LspBreakdown::disbursedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(lspOutstanding).isEqualByComparingTo(expectedOutstanding);
        assertThat(lspDisbursed).isEqualByComparingTo(expectedDisbursed);
    }

    private HomeDashboardService.HomeDashboardSummary summary() {
        return TenantScopedExecution.callAsAdmin(homeDashboardService::getSummary);
    }

    private BigDecimal globalOverdue() {
        return TenantScopedExecution.callAsAdmin(() ->
                portfolioKpiSnapshotRepository.findLatestGlobal().orElseThrow().getTotalOverdue());
    }

    private BigDecimal principalOf(UUID applicationId) {
        return Money.scale(jdbcTemplate.queryForObject(
                "SELECT principal_amount FROM loan_account WHERE loan_application_id = ?",
                BigDecimal.class,
                applicationId
        ));
    }

    private BigDecimal outstandingOf(UUID applicationId) {
        return jdbcTemplate.queryForObject(
                """
                        SELECT coalesce(sum(i.outstanding_amount), 0)
                        FROM loan_repayment_schedule_installment i
                        JOIN loan_account a ON a.id = i.loan_account_id
                        WHERE a.loan_application_id = ?
                        """,
                BigDecimal.class,
                applicationId
        );
    }

    private BigDecimal firstInstallmentOutstandingOf(UUID applicationId) {
        return jdbcTemplate.queryForObject(
                """
                        SELECT i.outstanding_amount
                        FROM loan_repayment_schedule_installment i
                        JOIN loan_account a ON a.id = i.loan_account_id
                        WHERE a.loan_application_id = ? AND i.installment_number = 1
                        """,
                BigDecimal.class,
                applicationId
        );
    }

    /**
     * Forces a population edge that the happy-path fixtures cannot reach here (invalidation and
     * closure have their own guarded command paths); only the two status columns the portfolio
     * population predicates read are touched.
     */
    private void forceLoanState(UUID applicationId, String accountStatus, String applicationStatus) {
        jdbcTemplate.update(
                "UPDATE loan_account SET status = ?, updated_at = CURRENT_TIMESTAMP WHERE loan_application_id = ?",
                accountStatus,
                applicationId
        );
        jdbcTemplate.update(
                "UPDATE loan_application SET status = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                applicationStatus,
                applicationId
        );
    }

    /**
     * An approved, unfunded loan: the account exists and its schedule is already generated
     * (approval generates it), but no money has moved. The delinquency sweep must never see it.
     */
    private UUID seedApprovedLoan() throws Exception {
        LspFixture lsp = createLsp();
        ProductFixture product = createProduct();
        mapProductToLsp(product.id(), lsp.id());

        JsonNode created = createApplication(lsp.id(), product.id(), "DPD-" + UUID.randomUUID().toString().substring(0, 8));
        UUID applicationId = UUID.fromString(created.get("id").asText());

        transitionApplication(applicationId, "AWAITING_APPROVAL", "Ready for approval");
        markAllRequiredKycDocumentsVerified(applicationId);
        transitionApplication(applicationId, "APPROVED_PENDING_DISBURSAL", "Approved for DPD test");

        seedBorrowerBankDetails(applicationId);
        return applicationId;
    }

    /**
     * A funded loan on which the borrower has never paid: the application stays DISBURSED because
     * only an allocated receipt moves it to UNDER_REPAYMENT. This is the H10 population.
     */
    private UUID seedDisbursedLoan() throws Exception {
        UUID applicationId = seedApprovedLoan();

        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/disbursement-requests", applicationId)
                        .with(systemAdmin()))
                .andExpect(status().isOk());

        // HDFC fixtures disburse atomically on intent execution — no mock outcome follows.
        disbursementIntentWorkflowService.executeForApplication(applicationId);
        loanDisbursementCommandService.autoResolveAfterInitiate(
                applicationId, "ops.admin", null, "dpd-test");
        return applicationId;
    }

    private void seedBorrowerBankDetails(UUID applicationId) throws Exception {
        UUID borrowerId = jdbcTemplate.queryForObject(
                "SELECT borrower_id FROM loan_application WHERE id = ?",
                UUID.class,
                applicationId
        );
        mockMvc.perform(patch("/api/v1/internal/admin/borrowers/{borrowerId}/bank-details", borrowerId)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "bankAccountNumber", "123456789012",
                                "bankName", "DPD Test Bank",
                                "ifscCode", "HDFC0001234",
                                "accountHolderName", "DPD Borrower"
                        ))))
                .andExpect(status().isOk());
    }

    private void setFirstInstallmentDueDate(UUID applicationId, LocalDate dueDate) {
        UUID loanAccountId = loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow().getId();
        jdbcTemplate.update(
                """
                        UPDATE loan_repayment_schedule_installment
                        SET due_date = ?, updated_at = CURRENT_TIMESTAMP
                        WHERE loan_account_id = ? AND installment_number = 1
                        """,
                dueDate,
                loanAccountId
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

    private LspFixture createLsp() throws Exception {
        String code = "LSP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        MvcResult result = mockMvc.perform(post("/api/v1/internal/admin/lsps")
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", code,
                                "name", "LSP " + code,
                                "status", "ACTIVE"
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        return new LspFixture(objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    private void mapProductToLsp(String productId, String lspId) throws Exception {
        mockMvc.perform(put("/api/v1/internal/admin/product-lsp-mappings/{productId}", productId)
                        .with(productAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("lspIds", List.of(lspId)))))
                .andExpect(status().isOk());
    }

    private JsonNode createApplication(String lspId, String productId, String externalLoanId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/internal/ops/loan-applications")
                        .with(opsUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loanApplicationPayload(lspId, productId, externalLoanId))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private void transitionApplication(UUID applicationId, String targetStatus, String note) throws Exception {
        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/status-transitions", applicationId)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "targetStatus", targetStatus,
                                "note", note
                        ))))
                .andExpect(status().isOk());
    }

    private void markAllRequiredKycDocumentsVerified(UUID applicationId) {
        loanApplicationDocumentChecklistRepository
                .findByLoanApplication_IdOrderByCreatedAtAsc(applicationId)
                .forEach(item -> {
                    if (item.isRequired()) {
                        item.update(
                                LoanApplicationDocumentChecklistStatus.SUBMITTED,
                                "Uploaded for DPD test",
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

    private static Map<String, Object> loanApplicationPayload(String lspId, String productId, String externalLoanId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        String pan = "ABCDE" + String.format("%04d", Math.abs(externalLoanId.hashCode() % 10000)) + "F";
        // Borrower identity (PAN, mobile, email) must be unique per application: a test that seeds
        // more than one loan otherwise trips BORROWER_IDENTITY_CONFLICT on the shared contact details.
        String mobile = "9" + String.format("%09d", Math.abs(externalLoanId.hashCode() % 1_000_000_000));
        payload.put("lspId", lspId);
        payload.put("productId", productId);
        payload.put("externalLoanId", externalLoanId);
        payload.put("sourceChannel", "API");
        payload.put("borrowerPan", pan);
        payload.put("borrowerFullName", "DPD Test Borrower");
        payload.put("borrowerMobile", mobile);
        payload.put("borrowerEmail", externalLoanId.toLowerCase() + "@example.com");
        payload.put("borrowerDateOfBirth", LocalDate.of(1990, 1, 15));
        payload.put("borrowerCity", "Mumbai");
        payload.put("borrowerState", "Maharashtra");
        payload.put("borrowerEmploymentType", "SALARIED");
        payload.put("borrowerMonthlyIncome", new BigDecimal("75000.00"));
        payload.put("requestedAmount", new BigDecimal("45000.00"));
        payload.put("tenureMonths", 12);
        return payload;
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
