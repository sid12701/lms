package com.bhawana.lms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bhawana.lms.domain.LoanApplicationDocumentChecklistStatus;
import com.bhawana.lms.domain.LoanDelinquencyBucket;
import com.bhawana.lms.repo.AlertRuleRepository;
import com.bhawana.lms.repo.AlertRuleSetQueryRepository;
import com.bhawana.lms.repo.LoanAccountRepository;
import com.bhawana.lms.repo.LoanApplicationDocumentChecklistRepository;
import com.bhawana.lms.repo.LoanDelinquencyStateRepository;
import com.bhawana.lms.support.IntegrationTestDatabaseCleaner;
import com.bhawana.lms.support.TenantContextTestExecutionListener;
import com.bhawana.lms.tenant.TenantScopedExecution;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * M07 acceptance: the delinquency sweep pages the servicing population through short
 * per-batch transactions instead of one unbounded writing transaction. With a two-loan page
 * size, five delinquent loans prove that (a) a mid-run failure leaves completed batches
 * committed, (b) a rerun finishes the remainder with no missing and no duplicate bucket
 * transitions, and (c) one rule's failure never rolls back unrelated completed rules.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "app.alert-rules.evaluation-batch-limit=2")
@TestExecutionListeners(
        value = TenantContextTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS
)
class AlertRuleEvaluationBatchingIntegrationTest {

    private static final int DELINQUENT_LOANS = 5;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AlertRuleEvaluationWorker alertRuleEvaluationWorker;

    @Autowired
    private AlertRuleRepository alertRuleRepository;

    @Autowired
    private LoanAccountRepository loanAccountRepository;

    @Autowired
    private LoanApplicationDocumentChecklistRepository loanApplicationDocumentChecklistRepository;

    @Autowired
    private LoanDelinquencyStateRepository loanDelinquencyStateRepository;

    @Autowired
    private DisbursementIntentWorkflowService disbursementIntentWorkflowService;

    @Autowired
    private LoanDisbursementCommandService loanDisbursementCommandService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private IntegrationTestDatabaseCleaner integrationTestDatabaseCleaner;

    @MockitoSpyBean
    private OpsAlertService opsAlertService;

    @MockitoSpyBean
    private AlertRuleSetQueryRepository alertRuleSetQueryRepository;

    @BeforeEach
    void setUp() {
        integrationTestDatabaseCleaner.cleanIntegrationTestData();
        TenantScopedExecution.runAsAdmin(() -> loanDelinquencyStateRepository.deleteAllInBatch());
    }

    @AfterEach
    void tearDown() {
        integrationTestDatabaseCleaner.cleanIntegrationTestData();
        TenantScopedExecution.runAsAdmin(() -> loanDelinquencyStateRepository.deleteAllInBatch());
    }

    @Test
    void midRunFailureLeavesCompletedBatchesCommittedAndRerunFinishesWithoutDuplicates() throws Exception {
        List<UUID> delinquentIds = seedDelinquentLoans();
        // Pages run in ascending database id order (PostgreSQL uuid ordering, which differs
        // from Java's signed compareTo); poisoning the third application's alert forces a
        // failure inside the second page's transaction — after page one already committed.
        UUID poisonedId = delinquentIds.get(2);
        doThrow(new IllegalStateException("batch crash probe"))
                .when(opsAlertService).createAlertIfAbsent(
                        eq(com.bhawana.lms.domain.OpsAlertType.DPD_BUCKET_TRANSITION),
                        anySeverity(), any(), any(), any(), eq(poisonedId), any(), any());

        alertRuleEvaluationWorker.evaluateScheduledRules();

        List<UUID> committed = delinquentIds.subList(0, 2);
        List<UUID> unprocessed = delinquentIds.subList(2, DELINQUENT_LOANS);
        assertThat(stateWrittenIds(delinquentIds))
                .as("page one must have committed before the crash")
                .containsExactlyInAnyOrderElementsOf(committed);
        for (UUID id : committed) {
            assertThat(bucketChangeEvents(id)).isEqualTo(1);
        }
        for (UUID id : unprocessed) {
            assertThat(bucketChangeEvents(id)).isZero();
        }

        reset(opsAlertService);
        alertRuleEvaluationWorker.evaluateScheduledRules();

        // Resume: every delinquent loan now has its state, each transition emitted exactly
        // once — committed batches were not re-emitted as duplicate events.
        assertThat(stateWrittenIds(delinquentIds)).containsExactlyInAnyOrderElementsOf(delinquentIds);
        for (UUID id : delinquentIds) {
            assertThat(bucketChangeEvents(id))
                    .as("duplicate LOAN_DELINQUENCY_BUCKET_CHANGED events for " + id)
                    .isEqualTo(1);
            var state = loanDelinquencyStateRepository.findByLoanApplication_Id(id).orElseThrow();
            assertThat(state.getLastBucket()).isEqualTo(LoanDelinquencyBucket.DPD_1_30);
        }
    }

    @Test
    void oneFailedRuleDoesNotRollBackCompletedRules() throws Exception {
        List<UUID> delinquentIds = seedDelinquentLoans();
        Instant evaluatedBefore = lastEvaluatedAt("DPD_BUCKET_TRANSITION");
        Instant staleEvaluatedBefore = lastEvaluatedAt("STALE_INTAKE");
        doThrow(new IllegalStateException("rule probe failure"))
                .when(alertRuleSetQueryRepository)
                .findStaleIntakeCandidates(any(), eq(2));

        alertRuleEvaluationWorker.evaluateScheduledRules();

        // The failed rule is never marked evaluated; the unrelated DPD rule completed fully.
        assertThat(lastEvaluatedAt("STALE_INTAKE"))
                .as("a failed rule must not be marked evaluated")
                .isEqualTo(staleEvaluatedBefore);
        Instant dpdEvaluated = lastEvaluatedAt("DPD_BUCKET_TRANSITION");
        assertThat(dpdEvaluated)
                .as("unrelated rule did not complete after a sibling rule failed")
                .isNotNull();
        assertThat(evaluatedBefore == null || dpdEvaluated.isAfter(evaluatedBefore)).isTrue();
        assertThat(stateWrittenIds(delinquentIds)).containsExactlyInAnyOrderElementsOf(delinquentIds);
    }

    private Instant lastEvaluatedAt(String ruleCode) {
        return TenantScopedExecution.callAsAdmin(() -> alertRuleRepository.findByCode(ruleCode)
                .map(rule -> rule.getLastEvaluatedAt())
                .orElse(null));
    }

    private List<UUID> stateWrittenIds(List<UUID> applicationIds) {
        return applicationIds.stream()
                .filter(id -> loanDelinquencyStateRepository.findByLoanApplication_Id(id).isPresent())
                .toList();
    }

    private long bucketChangeEvents(UUID applicationId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM loan_event "
                        + "WHERE loan_application_id = ? AND event_type = 'LOAN_DELINQUENCY_BUCKET_CHANGED'",
                Long.class, applicationId);
        return count == null ? 0 : count;
    }

    /** Five funded loans, each one installment ten days past due, sorted by id = page order. */
    private List<UUID> seedDelinquentLoans() throws Exception {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < DELINQUENT_LOANS; i++) {
            UUID applicationId = seedDisbursedLoan();
            setFirstInstallmentDueDate(applicationId, LocalDate.now().minusDays(10));
            ids.add(applicationId);
        }
        ids.sort(AlertRuleEvaluationBatchingIntegrationTest::compareUuidAsPostgres);
        return ids;
    }

    /** PostgreSQL orders uuids as unsigned 128-bit values; Java compares them signed. */
    private static int compareUuidAsPostgres(UUID left, UUID right) {
        int high = Long.compareUnsigned(left.getMostSignificantBits(), right.getMostSignificantBits());
        return high != 0 ? high
                : Long.compareUnsigned(left.getLeastSignificantBits(), right.getLeastSignificantBits());
    }

    private static com.bhawana.lms.domain.OpsAlertSeverity anySeverity() {
        return org.mockito.ArgumentMatchers.any();
    }

    private static <T> T any() {
        return org.mockito.ArgumentMatchers.any();
    }

    private UUID seedDisbursedLoan() throws Exception {
        LspFixture lsp = createLsp();
        ProductFixture product = createProduct();
        mapProductToLsp(product.id(), lsp.id());

        JsonNode created = createApplication(
                lsp.id(), product.id(), "DPD-B-" + UUID.randomUUID().toString().substring(0, 8));
        UUID applicationId = UUID.fromString(created.get("id").asText());

        transitionApplication(applicationId, "AWAITING_APPROVAL", "Ready for approval");
        markAllRequiredKycDocumentsVerified(applicationId);
        transitionApplication(applicationId, "APPROVED_PENDING_DISBURSAL", "Approved for batching test");
        seedBorrowerBankDetails(applicationId);

        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/disbursement-requests", applicationId)
                        .with(systemAdmin()))
                .andExpect(status().isOk());
        disbursementIntentWorkflowService.executeForApplication(applicationId);
        loanDisbursementCommandService.autoResolveAfterInitiate(
                applicationId, "ops.admin", null, "dpd-batching-test");
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

    private void markAllRequiredKycDocumentsVerified(UUID applicationId) {
        loanApplicationDocumentChecklistRepository
                .findByLoanApplication_IdOrderByCreatedAtAsc(applicationId)
                .forEach(item -> {
                    if (item.isRequired()) {
                        item.update(
                                LoanApplicationDocumentChecklistStatus.SUBMITTED,
                                "Uploaded for batching test",
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

    private static Map<String, Object> loanApplicationPayload(String lspId, String productId, String externalLoanId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        String pan = "ABCDE" + String.format("%04d", Math.abs(externalLoanId.hashCode() % 10000)) + "F";
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
