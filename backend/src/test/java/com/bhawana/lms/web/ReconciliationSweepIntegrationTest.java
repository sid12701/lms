package com.bhawana.lms.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bhawana.lms.domain.DisbursementDeclineKind;
import com.bhawana.lms.domain.DisbursementDisposition;
import com.bhawana.lms.domain.LoanAccount;
import com.bhawana.lms.domain.LoanAccountStatus;
import com.bhawana.lms.domain.LoanApplicationDocumentChecklistStatus;
import com.bhawana.lms.domain.LoanApplicationStatus;
import com.bhawana.lms.domain.LoanApplicationStatusReasonCode;
import com.bhawana.lms.repo.DisbursementIntentRepository;
import com.bhawana.lms.repo.DisbursementReconciliationQueueRepository;
import com.bhawana.lms.repo.LoanAccountRepository;
import com.bhawana.lms.repo.LoanApplicationDocumentChecklistRepository;
import com.bhawana.lms.repo.LoanApplicationRepository;
import com.bhawana.lms.service.DisbursementIntentWorkflowService;
import com.bhawana.lms.service.LoanDisbursementAdapter;
import com.bhawana.lms.service.LoanDisbursementCommandService;
import com.bhawana.lms.service.LoanDisbursementWorkerService;
import com.bhawana.lms.support.IntegrationTestDatabaseCleaner;
import com.bhawana.lms.support.TenantContextTestExecutionListener;
import com.bhawana.lms.support.TestPanSequence;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Integration — the bounded scheduled reconciliation phase recovers a parked loan by
 * re-polling its ORIGINAL reference exactly once (zero new initiations), and the queue row
 * clears only after the applier commits. Provider latency timers count the real initiate and
 * status-check paths, including the throw path.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestExecutionListeners(
        value = TenantContextTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS
)
class ReconciliationSweepIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private MeterRegistry meterRegistry;
    @Autowired private LoanApplicationRepository loanApplicationRepository;
    @Autowired private LoanAccountRepository loanAccountRepository;
    @Autowired private LoanApplicationDocumentChecklistRepository loanApplicationDocumentChecklistRepository;
    @Autowired private DisbursementIntentRepository disbursementIntentRepository;
    @Autowired private DisbursementReconciliationQueueRepository queueRepository;
    @Autowired private DisbursementIntentWorkflowService disbursementIntentWorkflowService;
    @Autowired private LoanDisbursementCommandService loanDisbursementCommandService;
    @Autowired private LoanDisbursementWorkerService workerService;
    @Autowired private IntegrationTestDatabaseCleaner databaseCleaner;

    @MockitoSpyBean
    private LoanDisbursementAdapter loanDisbursementAdapter;

    @MockitoSpyBean
    private com.bhawana.lms.service.LoanApplicationStatusWriter loanApplicationStatusWriter;

    @BeforeEach
    void setUp() {
        reset(loanDisbursementAdapter, loanApplicationStatusWriter);
        databaseCleaner.cleanIntegrationTestData();
    }

    @AfterEach
    void tearDown() {
        reset(loanDisbursementAdapter, loanApplicationStatusWriter);
        databaseCleaner.cleanIntegrationTestData();
    }

    @Test
    void scheduledSweepRecoversParkedOriginalReferenceOnceAndClearsQueueOnlyOnCommit() throws Exception {
        UUID applicationId = seedApproved("MOCK0STUCK0", new BigDecimal("45000.00"));
        initiate(applicationId);
        disbursementIntentWorkflowService.executeForApplication(applicationId);
        LoanAccount account = loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow();
        String ref = liveRef(account.getId());
        verify(loanDisbursementAdapter, times(1)).requestDisbursement(any());

        // Exhaust normal polling into PARKED with a queue row on the original reference.
        assertFalse(loanDisbursementCommandService.pollPendingDisbursement(applicationId, "worker", null, "t27-r1"));
        assertTrue(loanDisbursementCommandService.pollPendingDisbursement(applicationId, "worker", null, "t27-r2"));
        assertEquals(LoanAccountStatus.DISBURSEMENT_PENDING_RECONCILIATION,
                loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow().getStatus());
        assertEquals(ref, queueRepository.findById(account.getId()).orElseThrow().getTranRefNo());

        // The bank reports success for the ORIGINAL reference on the next sweep.
        doAnswer(invocation -> new LoanDisbursementAdapter.DisbursementStatusResult(
                        "0", "Check Transaction Successful",
                        DisbursementDisposition.SUCCESS, DisbursementDeclineKind.NONE,
                        "0", "RRN-SWEEP-001", "recovered terminal success", "{\"disposition\":\"SUCCESS\"}"))
                .when(loanDisbursementAdapter).checkStatus(any());

        // Applier crash first (fault inside the atomic apply, via the status writer):
        // nothing commits, so the queue row must stay and the loan must stay parked —
        // the sweep reports zero resolved.
        doThrow(new IllegalStateException("simulated applier crash"))
                .when(loanApplicationStatusWriter).updateStatus(any(), any());
        forceDue(account.getId());
        assertEquals(0, workerService.processReconciliationQueue());
        assertEquals(LoanAccountStatus.DISBURSEMENT_PENDING_RECONCILIATION,
                loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow().getStatus());
        assertTrue(queueRepository.findById(account.getId()).isPresent());

        // Real apply next: the SAME reference applies once, the queue drains, and no new
        // initiation ever happened (one intent row, one provider request).
        org.mockito.Mockito.doCallRealMethod().when(loanApplicationStatusWriter)
                .updateStatus(any(), any());
        forceDue(account.getId());
        double statusBefore = statusTimerCount();
        assertTrue(workerService.processReconciliationQueue() >= 1);
        assertEquals(LoanAccountStatus.DISBURSED,
                loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow().getStatus());
        assertEquals(LoanApplicationStatus.DISBURSED,
                loanApplicationRepository.findById(applicationId).orElseThrow().getStatus());
        assertTrue(queueRepository.findById(account.getId()).isEmpty());
        assertEquals(ref, loanDisbursementRequestLogRef(account.getId()));
        verify(loanDisbursementAdapter, times(1)).requestDisbursement(any());
        assertEquals(1L, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM disbursement_intent WHERE loan_account_id = ?", Long.class,
                account.getId()).longValue());
        assertEquals(statusBefore + 1, statusTimerCount(), 0.0001);
    }

    @Test
    void providerTimersCountRealInitiateStatusAndThrowPaths() throws Exception {
        double initiateBefore = initiateTimerCount();
        double statusBefore = statusTimerCount();

        UUID okId = seedApproved("HDFC0001234", new BigDecimal("45000.00"));
        initiate(okId);
        disbursementIntentWorkflowService.executeForApplication(okId);
        loanDisbursementCommandService.autoResolveAfterInitiate(okId, "ops.admin", null, "t27-timers");
        assertEquals(initiateBefore + 1, initiateTimerCount(), 0.0001);

        UUID pendingId = seedApproved("MOCK0PENDOK", new BigDecimal("45000.00"));
        initiate(pendingId);
        disbursementIntentWorkflowService.executeForApplication(pendingId);
        loanDisbursementCommandService.pollPendingDisbursement(pendingId, "worker", null, "t27-tpoll");
        assertEquals(initiateBefore + 2, initiateTimerCount(), 0.0001);
        assertEquals(statusBefore + 1, statusTimerCount(), 0.0001);

        // Throw path: the timeout still records initiate latency, and the loan parks UNKNOWN.
        UUID timeoutId = seedApproved("MOCK0PENDOK", new BigDecimal("45000.00"));
        initiate(timeoutId);
        doThrow(new RuntimeException("simulated initiate timeout"))
                .when(loanDisbursementAdapter).requestDisbursement(any());
        disbursementIntentWorkflowService.executeForApplication(timeoutId);
        org.mockito.Mockito.doCallRealMethod().when(loanDisbursementAdapter).requestDisbursement(any());
        assertEquals(initiateBefore + 3, initiateTimerCount(), 0.0001);
        assertEquals(LoanAccountStatus.DISBURSEMENT_REQUESTED,
                loanAccountRepository.findByLoanApplication_Id(timeoutId).orElseThrow().getStatus());
    }

    private double initiateTimerCount() {
        return meterRegistry.get("lms.disbursement.provider.initiate.latency").timer().count();
    }

    private double statusTimerCount() {
        return meterRegistry.get("lms.disbursement.provider.status_check.latency").timer().count();
    }

    private void forceDue(UUID loanAccountId) {
        jdbcTemplate.update(
                "UPDATE disbursement_reconciliation_queue SET next_poll_at = NOW() - INTERVAL '1 second' "
                        + "WHERE loan_account_id = ?",
                loanAccountId);
    }

    private String liveRef(UUID accountId) {
        return disbursementIntentRepository.findLiveByLoanAccountId(accountId).orElseThrow().getTranRefNo();
    }

    private String loanDisbursementRequestLogRef(UUID accountId) {
        return jdbcTemplate.queryForObject(
                "SELECT tran_ref_no FROM loan_disbursement_request_log WHERE loan_account_id = ? "
                        + "ORDER BY created_at DESC LIMIT 1",
                String.class, accountId);
    }

    private void initiate(UUID applicationId) throws Exception {
        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/disbursement-requests", applicationId)
                        .with(systemAdmin()))
                .andExpect(status().isOk());
    }

    private UUID seedApproved(String ifsc, BigDecimal requestedAmount) throws Exception {
        String lspId = createLspViaAdmin();
        String productId = createProductViaAdmin();
        mapProductToLsp(productId, lspId);
        String applicationId = createApplicationViaOps(lspId, productId, requestedAmount);
        transition(applicationId, "AWAITING_APPROVAL", "Ready for approval");
        markKycComplete(applicationId);
        transition(applicationId, "APPROVED_PENDING_DISBURSAL", "Approved for sweep test");
        seedBorrowerBankDetails(applicationId, ifsc);
        return UUID.fromString(applicationId);
    }

    private void seedBorrowerBankDetails(String applicationId, String ifsc) throws Exception {
        String borrowerId = loanApplicationRepository.findById(UUID.fromString(applicationId)).orElseThrow()
                .getBorrower().getId().toString();
        mockMvc.perform(patch("/api/v1/internal/admin/borrowers/{borrowerId}/bank-details", borrowerId)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "bankAccountNumber", "123456789012",
                                "bankName", "Test Bank",
                                "ifscCode", ifsc,
                                "accountHolderName", "Test Sweep Borrower"
                        ))))
                .andExpect(status().isOk());
    }

    private String createLspViaAdmin() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/internal/admin/lsps")
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", "LSP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                                "name", "Test LSP",
                                "status", "ACTIVE"
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private String createProductViaAdmin() throws Exception {
        String code = "PROD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        MvcResult result = mockMvc.perform(post("/api/v1/internal/admin/products")
                        .with(productAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", code,
                                "name", "Test product " + code,
                                "minPrincipal", new BigDecimal("5000.00"),
                                "maxPrincipal", new BigDecimal("1000000.00"),
                                "interestRate", new BigDecimal("18.50"),
                                "processingFeeRate", new BigDecimal("2.25"),
                                "minTenureMonths", 6,
                                "maxTenureMonths", 24,
                                "status", "ACTIVE"
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private void mapProductToLsp(String productId, String lspId) throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/v1/internal/admin/product-lsp-mappings/{productId}", productId)
                        .with(productAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("lspIds", List.of(lspId)))))
                .andExpect(status().isOk());
    }

    private String createApplicationViaOps(String lspId, String productId, BigDecimal requestedAmount) throws Exception {
        String borrowerPan = TestPanSequence.uniquePan();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("lspId", lspId);
        payload.put("productId", productId);
        payload.put("externalLoanId", "EXT-" + UUID.randomUUID().toString().substring(0, 8));
        payload.put("sourceChannel", "API");
        payload.put("borrowerPan", borrowerPan);
        payload.put("borrowerFullName", "Test Sweep Borrower");
        payload.put("borrowerMobile", mobileForPan(borrowerPan));
        payload.put("borrowerEmail", "t27sweep+" + borrowerPan.toLowerCase() + "@example.com");
        payload.put("borrowerDateOfBirth", LocalDate.of(1990, 1, 1));
        payload.put("borrowerCity", "Mumbai");
        payload.put("borrowerState", "Maharashtra");
        payload.put("borrowerEmploymentType", "SALARIED");
        payload.put("borrowerMonthlyIncome", new BigDecimal("250000.00"));
        payload.put("requestedAmount", requestedAmount);
        payload.put("tenureMonths", 12);

        MvcResult result = mockMvc.perform(post("/api/v1/internal/ops/loan-applications")
                        .with(opsUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private void transition(String applicationId, String targetStatus, String note) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("targetStatus", targetStatus);
        body.put("note", note);
        if ("REJECTED".equals(targetStatus) || "DISBURSEMENT_RETRY".equals(targetStatus)) {
            body.put("reasonCode", LoanApplicationStatusReasonCode.FAILED_VERIFICATION.name());
        }
        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/status-transitions", applicationId)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk());
    }

    private void markKycComplete(String applicationId) {
        UUID applicationUuid = UUID.fromString(applicationId);
        loanApplicationDocumentChecklistRepository.findByLoanApplication_IdOrderByCreatedAtAsc(applicationUuid)
                .forEach(item -> {
                    if (!item.isRequired()) {
                        return;
                    }
                    String documentKey = item.getDocumentType().name().toLowerCase();
                    item.update(
                            LoanApplicationDocumentChecklistStatus.SUBMITTED,
                            "Uploaded for sweep test",
                            "ops.user",
                            documentKey + ".pdf",
                            "storage://" + applicationId + "/" + documentKey + ".pdf",
                            null,
                            "application/pdf",
                            1024L,
                            "checksum-" + documentKey,
                            "storage-key/" + applicationId + "/" + documentKey,
                            true
                    );
                    loanApplicationDocumentChecklistRepository.save(item);
                });
    }

    private static String mobileForPan(String pan) {
        int hash = Math.abs(pan.hashCode());
        return "9" + String.format("%09d", hash % 1_000_000_000);
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
