package com.bhawana.lms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bhawana.lms.domain.LoanApplicationDocumentChecklistStatus;
import com.bhawana.lms.domain.LoanDelinquencyBucket;
import com.bhawana.lms.domain.OpsAlert;
import com.bhawana.lms.domain.OpsAlertType;
import com.bhawana.lms.repo.LoanAccountRepository;
import com.bhawana.lms.repo.LoanApplicationDocumentChecklistRepository;
import com.bhawana.lms.repo.LoanDelinquencyStateRepository;
import com.bhawana.lms.repo.OpsAlertRepository;
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

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestExecutionListeners(
        value = TenantContextTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS
)
class AlertRuleEvaluationWorkerDpdBucketTransitionIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AlertRuleEvaluationWorker alertRuleEvaluationWorker;

    @Autowired
    private LoanAccountRepository loanAccountRepository;

    @Autowired
    private LoanApplicationDocumentChecklistRepository loanApplicationDocumentChecklistRepository;

    @Autowired
    private LoanDelinquencyStateRepository loanDelinquencyStateRepository;

    @Autowired
    private OpsAlertRepository opsAlertRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private IntegrationTestDatabaseCleaner integrationTestDatabaseCleaner;

    @BeforeEach
    void setUp() {
        integrationTestDatabaseCleaner.cleanIntegrationTestData();
        TenantScopedExecution.runAsAdmin(() -> loanDelinquencyStateRepository.deleteAllInBatch());
    }

    @Test
    void firstEvaluationOfTenDayPastDueLoanEmitsAlertAndPersistsState() throws Exception {
        UUID applicationId = seedUnderRepaymentLoan();
        setFirstInstallmentDueDate(applicationId, LocalDate.now().minusDays(10));

        AlertRuleEvaluationWorker.EvaluationSummary summary = alertRuleEvaluationWorker.evaluateScheduledRules();

        assertThat(summary.alertsEmitted()).isGreaterThanOrEqualTo(1);
        assertThat(countDpdAlerts(applicationId)).isEqualTo(1);

        OpsAlert alert = findLatestDpdAlert(applicationId);
        assertThat(alert.getContextJson()).contains("DPD_1_30");
        assertThat(alert.getContextJson()).contains("\"previousBucket\":\"CURRENT\"");

        var state = loanDelinquencyStateRepository.findByLoanApplication_Id(applicationId).orElseThrow();
        assertThat(state.getLastBucket()).isEqualTo(LoanDelinquencyBucket.DPD_1_30);
        assertThat(state.getLastMaxDaysPastDue()).isEqualTo(10);
    }

    @Test
    void unchangedBucketAfterAcknowledgementDoesNotEmitAnotherAlert() throws Exception {
        UUID applicationId = seedUnderRepaymentLoan();
        setFirstInstallmentDueDate(applicationId, LocalDate.now().minusDays(10));

        alertRuleEvaluationWorker.evaluateScheduledRules();
        acknowledgeLatestDpdAlert(applicationId);

        AlertRuleEvaluationWorker.EvaluationSummary secondPass = alertRuleEvaluationWorker.evaluateScheduledRules();

        assertThat(secondPass.alertsEmitted()).isZero();
        assertThat(countDpdAlerts(applicationId)).isEqualTo(1);
        assertThat(loanDelinquencyStateRepository.findByLoanApplication_Id(applicationId)).isPresent();
    }

    @Test
    void bucketWorseningEmitsAlertWithPreviousBucketInContext() throws Exception {
        UUID applicationId = seedUnderRepaymentLoan();
        setFirstInstallmentDueDate(applicationId, LocalDate.now().minusDays(10));
        alertRuleEvaluationWorker.evaluateScheduledRules();
        acknowledgeLatestDpdAlert(applicationId);

        setFirstInstallmentDueDate(applicationId, LocalDate.now().minusDays(35));
        AlertRuleEvaluationWorker.EvaluationSummary worseningPass = alertRuleEvaluationWorker.evaluateScheduledRules();

        assertThat(worseningPass.alertsEmitted()).isGreaterThanOrEqualTo(1);
        assertThat(countDpdAlerts(applicationId)).isEqualTo(2);

        OpsAlert latest = findLatestDpdAlert(applicationId);
        assertThat(latest.getContextJson()).contains("\"bucket\":\"DPD_31_60\"");
        assertThat(latest.getContextJson()).contains("\"previousBucket\":\"DPD_1_30\"");

        var state = loanDelinquencyStateRepository.findByLoanApplication_Id(applicationId).orElseThrow();
        assertThat(state.getLastBucket()).isEqualTo(LoanDelinquencyBucket.DPD_31_60);
        assertThat(state.getLastMaxDaysPastDue()).isEqualTo(35);
    }

    @Test
    void bucketImprovementUpdatesStateWithoutAlertAndReWorseningAlertsAgain() throws Exception {
        UUID applicationId = seedUnderRepaymentLoan();
        setFirstInstallmentDueDate(applicationId, LocalDate.now().minusDays(10));
        alertRuleEvaluationWorker.evaluateScheduledRules();
        acknowledgeLatestDpdAlert(applicationId);

        setFirstInstallmentDueDate(applicationId, LocalDate.now().plusDays(5));
        AlertRuleEvaluationWorker.EvaluationSummary improvementPass = alertRuleEvaluationWorker.evaluateScheduledRules();
        assertThat(improvementPass.alertsEmitted()).isZero();
        assertThat(loanDelinquencyStateRepository.findByLoanApplication_Id(applicationId).orElseThrow().getLastBucket())
                .isEqualTo(LoanDelinquencyBucket.CURRENT);

        setFirstInstallmentDueDate(applicationId, LocalDate.now().minusDays(12));
        AlertRuleEvaluationWorker.EvaluationSummary reWorseningPass = alertRuleEvaluationWorker.evaluateScheduledRules();
        assertThat(reWorseningPass.alertsEmitted()).isGreaterThanOrEqualTo(1);
        assertThat(countDpdAlerts(applicationId)).isEqualTo(2);

        OpsAlert latest = findLatestDpdAlert(applicationId);
        assertThat(latest.getContextJson()).contains("\"previousBucket\":\"CURRENT\"");
    }

    private UUID seedUnderRepaymentLoan() throws Exception {
        LspFixture lsp = createLsp();
        ProductFixture product = createProduct();
        mapProductToLsp(product.id(), lsp.id());

        JsonNode created = createApplication(lsp.id(), product.id(), "DPD-" + UUID.randomUUID().toString().substring(0, 8));
        UUID applicationId = UUID.fromString(created.get("id").asText());

        transitionApplication(applicationId, "AWAITING_APPROVAL", "Ready for approval");
        markAllRequiredKycDocumentsVerified(applicationId);
        transitionApplication(applicationId, "APPROVED_PENDING_DISBURSAL", "Approved for DPD test");

        seedBorrowerBankDetails(applicationId);

        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/disbursement-requests", applicationId)
                        .with(systemAdmin()))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/disbursement-requests/mock-outcome", applicationId)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("outcome", "DISBURSED"))))
                .andExpect(status().isOk());

        jdbcTemplate.update(
                "UPDATE loan_application SET status = 'UNDER_REPAYMENT', updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                applicationId
        );
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

    private void acknowledgeLatestDpdAlert(UUID applicationId) {
        TenantScopedExecution.runAsAdmin(() -> {
            OpsAlert alert = findLatestDpdAlert(applicationId);
            alert.acknowledge("ops.user", "Reviewed for test");
            opsAlertRepository.save(alert);
        });
    }

    private long countDpdAlerts(UUID applicationId) {
        return TenantScopedExecution.callAsAdmin(() -> opsAlertRepository.findAll().stream()
                .filter(alert -> alert.getType() == OpsAlertType.DPD_BUCKET_TRANSITION)
                .filter(alert -> applicationId.equals(alert.getSubjectId()))
                .count());
    }

    private OpsAlert findLatestDpdAlert(UUID applicationId) {
        return TenantScopedExecution.callAsAdmin(() -> opsAlertRepository.findAll().stream()
                .filter(alert -> alert.getType() == OpsAlertType.DPD_BUCKET_TRANSITION)
                .filter(alert -> applicationId.equals(alert.getSubjectId()))
                .max((left, right) -> left.getCreatedAt().compareTo(right.getCreatedAt()))
                .orElseThrow());
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
        payload.put("lspId", lspId);
        payload.put("productId", productId);
        payload.put("externalLoanId", externalLoanId);
        payload.put("sourceChannel", "API");
        payload.put("borrowerPan", pan);
        payload.put("borrowerFullName", "DPD Test Borrower");
        payload.put("borrowerMobile", "9876543210");
        payload.put("borrowerEmail", "dpd.test@example.com");
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
