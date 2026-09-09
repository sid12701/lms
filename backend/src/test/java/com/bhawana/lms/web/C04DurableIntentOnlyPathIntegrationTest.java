package com.bhawana.lms.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bhawana.lms.common.api.error.ApiConflictException;
import com.bhawana.lms.domain.DisbursementIntentState;
import com.bhawana.lms.domain.LoanAccount;
import com.bhawana.lms.domain.LoanAccountStatus;
import com.bhawana.lms.domain.LoanApplicationDocumentChecklistStatus;
import com.bhawana.lms.domain.LoanApplicationStatus;
import com.bhawana.lms.repo.DisbursementIntentRepository;
import com.bhawana.lms.repo.LoanAccountRepository;
import com.bhawana.lms.repo.LoanApplicationDocumentChecklistRepository;
import com.bhawana.lms.repo.LoanApplicationRepository;
import com.bhawana.lms.repo.LoanDisbursementRequestLogRepository;
import com.bhawana.lms.service.DisbursementIntentWorkflowService;
import com.bhawana.lms.service.LoanDisbursementAdapter;
import com.bhawana.lms.service.LoanDisbursementCommandService;
import com.bhawana.lms.service.LoanDisbursementWorkerService;
import com.bhawana.lms.support.TenantContextTestExecutionListener;
import com.bhawana.lms.support.TestPanSequence;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * C04 — the durable intent is the only money-movement path. Initiation never calls the bank
 * synchronously, re-initiation from REQUESTED/PENDING_RECONCILIATION is rejected with no new
 * reference, and no configuration can re-enable the removed inline path.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestExecutionListeners(
        value = TenantContextTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS
)
class C04DurableIntentOnlyPathIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private LoanApplicationRepository loanApplicationRepository;
    @Autowired private LoanAccountRepository loanAccountRepository;
    @Autowired private LoanApplicationDocumentChecklistRepository loanApplicationDocumentChecklistRepository;
    @Autowired private DisbursementIntentRepository disbursementIntentRepository;
    @Autowired private LoanDisbursementRequestLogRepository loanDisbursementRequestLogRepository;
    @Autowired private DisbursementIntentWorkflowService disbursementIntentWorkflowService;
    @Autowired private LoanDisbursementCommandService loanDisbursementCommandService;
    @Autowired private LoanDisbursementWorkerService loanDisbursementWorkerService;

    @MockitoSpyBean
    private LoanDisbursementAdapter loanDisbursementAdapter;

    @BeforeEach
    void resetMocks() {
        reset(loanDisbursementAdapter);
    }

    @Test
    void initiateCreatesIntentWithoutCallingBank() throws Exception {
        UUID applicationId = seedApproved("HDFC0001234", new BigDecimal("45000.00"));

        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/disbursement-requests", applicationId)
                        .with(systemAdmin()))
                .andExpect(status().isOk());

        // C04: initiation commits Tx-A only — the bank is contacted by the worker afterwards.
        verify(loanDisbursementAdapter, never()).requestDisbursement(any());

        LoanAccount account = loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow();
        assertEquals(LoanAccountStatus.DISBURSEMENT_REQUESTED, account.getStatus());
        var intent = disbursementIntentRepository.findLiveByLoanAccountId(account.getId()).orElseThrow();
        assertEquals(DisbursementIntentState.CREATED, intent.getState());
        assertEquals(1, intentsForAccount(account.getId()));
        // Tx-A persists no provider log; the log is written when the worker executes the intent.
        assertEquals(0, loanDisbursementRequestLogRepository.countByLoanAccount_Id(account.getId()));
    }

    @Test
    void reInitiationFromRequestedIsRejectedWithNoNewReference() throws Exception {
        // MOCK0PENDOK stays PENDING after execution: account remains REQUESTED (in flight).
        UUID applicationId = seedApproved("MOCK0PENDOK", new BigDecimal("45000.00"));

        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/disbursement-requests", applicationId)
                        .with(systemAdmin()))
                .andExpect(status().isOk());
        disbursementIntentWorkflowService.executeForApplication(applicationId);

        LoanAccount account = loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow();
        assertEquals(LoanAccountStatus.DISBURSEMENT_REQUESTED, account.getStatus());
        assertEquals(1, intentsForAccount(account.getId()));
        long logsAfterFirst = loanDisbursementRequestLogRepository.countByLoanAccount_Id(account.getId());
        verify(loanDisbursementAdapter, times(1)).requestDisbursement(any());

        ApiConflictException conflict = assertThrows(
                ApiConflictException.class,
                () -> loanDisbursementCommandService.initiateDisbursement(applicationId, "ops.admin"));
        assertEquals("DISBURSEMENT_ALREADY_REQUESTED", conflict.getErrorCode());

        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/disbursement-requests", applicationId)
                        .with(systemAdmin()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("DISBURSEMENT_ALREADY_REQUESTED"));

        // No second reference, no second bank call: the only forward path is status polling.
        assertEquals(1, intentsForAccount(account.getId()));
        assertEquals(logsAfterFirst, loanDisbursementRequestLogRepository.countByLoanAccount_Id(account.getId()));
        verify(loanDisbursementAdapter, times(1)).requestDisbursement(any());
        assertEquals(LoanAccountStatus.DISBURSEMENT_REQUESTED,
                loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow().getStatus());
    }

    @Test
    void reInitiationFromPendingReconciliationIsRejected() throws Exception {
        // MOCK0STUCK0 parks after the poll cap (test profile max-polls = 2).
        UUID applicationId = seedApproved("MOCK0STUCK0", new BigDecimal("45000.00"));

        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/disbursement-requests", applicationId)
                        .with(systemAdmin()))
                .andExpect(status().isOk());
        disbursementIntentWorkflowService.executeForApplication(applicationId);
        loanDisbursementWorkerService.processPendingStatusChecks();
        loanDisbursementWorkerService.processPendingStatusChecks();

        LoanAccount account = loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow();
        assertEquals(LoanAccountStatus.DISBURSEMENT_PENDING_RECONCILIATION, account.getStatus());
        assertEquals(1, intentsForAccount(account.getId()));
        long logsAfterPark = loanDisbursementRequestLogRepository.countByLoanAccount_Id(account.getId());
        int providerCallsAfterPark = providerCallCount();

        ApiConflictException conflict = assertThrows(
                ApiConflictException.class,
                () -> loanDisbursementCommandService.initiateDisbursement(applicationId, "ops.admin"));
        assertEquals("DISBURSEMENT_ALREADY_REQUESTED", conflict.getErrorCode());

        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/disbursement-requests", applicationId)
                        .with(systemAdmin()))
                .andExpect(status().isConflict());

        assertEquals(1, intentsForAccount(account.getId()));
        assertEquals(logsAfterPark, loanDisbursementRequestLogRepository.countByLoanAccount_Id(account.getId()));
        assertEquals(providerCallsAfterPark, providerCallCount());
        assertEquals(LoanAccountStatus.DISBURSEMENT_PENDING_RECONCILIATION,
                loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow().getStatus());
    }

    @Test
    void failedAttemptMayInitiateAgainWhileDisbursedMayNot() throws Exception {
        // Technical decline -> DISBURSEMENT_FAILED: a new attempt is the safe forward path.
        UUID retryId = seedApproved("MOCK0NPCIDN", new BigDecimal("45000.00"));
        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/disbursement-requests", retryId)
                        .with(systemAdmin()))
                .andExpect(status().isOk());
        disbursementIntentWorkflowService.executeForApplication(retryId);
        loanDisbursementCommandService.autoResolveAfterInitiate(retryId, "ops.admin", null, "c04-failed");
        assertEquals(LoanAccountStatus.DISBURSEMENT_FAILED,
                loanAccountRepository.findByLoanApplication_Id(retryId).orElseThrow().getStatus());

        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/disbursement-requests", retryId)
                        .with(systemAdmin()))
                .andExpect(status().isOk());
        LoanAccount retryAccount = loanAccountRepository.findByLoanApplication_Id(retryId).orElseThrow();
        assertEquals(LoanAccountStatus.DISBURSEMENT_REQUESTED, retryAccount.getStatus());
        assertEquals(2, intentsForAccount(retryAccount.getId()));

        // Successful funding -> DISBURSED: further initiation is a harmless no-op that must
        // never mint a new reference or debit (completed loans return the application as-is).
        UUID fundedId = seedApproved("HDFC0001234", new BigDecimal("45000.00"));
        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/disbursement-requests", fundedId)
                        .with(systemAdmin()))
                .andExpect(status().isOk());
        disbursementIntentWorkflowService.executeForApplication(fundedId);
        loanDisbursementCommandService.autoResolveAfterInitiate(fundedId, "ops.admin", null, "c04-funded");
        assertEquals(LoanAccountStatus.DISBURSED,
                loanAccountRepository.findByLoanApplication_Id(fundedId).orElseThrow().getStatus());
        LoanAccount fundedAccount = loanAccountRepository.findByLoanApplication_Id(fundedId).orElseThrow();
        long fundedIntents = intentsForAccount(fundedAccount.getId());
        long fundedLogs = loanDisbursementRequestLogRepository.countByLoanAccount_Id(fundedAccount.getId());
        int callsBefore = providerCallCount();

        loanDisbursementCommandService.initiateDisbursement(fundedId, "ops.admin");
        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/disbursement-requests", fundedId)
                        .with(systemAdmin()))
                .andExpect(status().isOk());
        assertEquals(fundedIntents, intentsForAccount(fundedAccount.getId()));
        assertEquals(fundedLogs, loanDisbursementRequestLogRepository.countByLoanAccount_Id(fundedAccount.getId()));
        assertEquals(callsBefore, providerCallCount());
        assertEquals(LoanAccountStatus.DISBURSED,
                loanAccountRepository.findByLoanApplication_Id(fundedId).orElseThrow().getStatus());
    }

    @Test
    void noInlineInitiationPathOrFlagRemains() {
        // Configuration regression: the second money path is deleted, not disabled.
        assertTrue(noDeclaredMethod(LoanDisbursementCommandService.class, "initiateDisbursementInline"),
                "initiateDisbursementInline must be removed");
        assertTrue(noDeclaredMethod(LoanDisbursementCommandService.class, "pollPendingDisbursementInline"),
                "pollPendingDisbursementInline must be removed");
        assertTrue(noDeclaredMethod(
                com.bhawana.lms.service.DisbursementIntentWorkflowProperties.class, "isEnabled"),
                "intent-workflow enabled flag must be removed");
        assertTrue(noDeclaredMethod(
                com.bhawana.lms.service.DisbursementIntentWorkflowProperties.class, "setEnabled"),
                "intent-workflow enabled setter must be removed");
        // The single guarded transition owns the disbursement-state rules.
        assertFalse(noDeclaredMethod(LoanAccount.class, "requestDisbursement"),
                "LoanAccount.requestDisbursement must exist as the single transition guard");
    }

    // --- helpers ---

    private long intentsForAccount(UUID accountId) {
        return disbursementIntentRepository.findAll().stream()
                .filter(intent -> intent.getLoanAccount().getId().equals(accountId))
                .count();
    }

    private int providerCallCount() {
        return (int) org.mockito.Mockito.mockingDetails(loanDisbursementAdapter).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("requestDisbursement"))
                .count();
    }

    private static boolean noDeclaredMethod(Class<?> type, String name) {
        for (var method : type.getDeclaredMethods()) {
            if (method.getName().equals(name)) {
                return false;
            }
        }
        return true;
    }

    private UUID seedApproved(String ifsc, BigDecimal requestedAmount) throws Exception {
        String lspId = createLspViaAdmin();
        String productId = createProductViaAdmin();
        mapProductToLsp(productId, lspId);
        String applicationId = createApplicationViaOps(lspId, productId, requestedAmount);
        transition(applicationId, "AWAITING_APPROVAL", "Ready for approval");
        markKycComplete(applicationId);
        transition(applicationId, "APPROVED_PENDING_DISBURSAL", "Approved for C04 single-path test");
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
                                "bankName", "C04 Bank",
                                "ifscCode", ifsc,
                                "accountHolderName", "C04 Borrower"
                        ))))
                .andExpect(status().isOk());
    }

    private String createLspViaAdmin() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/internal/admin/lsps")
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", "LSP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                                "name", "C04 LSP",
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
                                "name", "C04 product " + code,
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
        mockMvc.perform(put("/api/v1/internal/admin/product-lsp-mappings/{productId}", productId)
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
        payload.put("borrowerFullName", "C04 Borrower");
        payload.put("borrowerMobile", mobileForPan(borrowerPan));
        payload.put("borrowerEmail", "c04+" + borrowerPan.toLowerCase() + "@example.com");
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
        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/status-transitions", applicationId)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "targetStatus", targetStatus,
                                "note", note
                        ))))
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
                            "Uploaded for C04 single-path test",
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
