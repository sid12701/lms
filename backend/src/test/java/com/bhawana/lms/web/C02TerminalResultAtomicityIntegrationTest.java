package com.bhawana.lms.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bhawana.lms.domain.DisbursementDeclineKind;
import com.bhawana.lms.domain.DisbursementDisposition;
import com.bhawana.lms.domain.DisbursementIntent;
import com.bhawana.lms.domain.DisbursementIntentState;
import com.bhawana.lms.domain.LoanAccount;
import com.bhawana.lms.domain.LoanAccountStatus;
import com.bhawana.lms.domain.LoanApplicationDocumentChecklistStatus;
import com.bhawana.lms.domain.LoanApplicationStatus;
import com.bhawana.lms.domain.LoanDisbursementRequestLog;
import com.bhawana.lms.repo.DisbursementIntentRepository;
import com.bhawana.lms.repo.LoanAccountRepository;
import com.bhawana.lms.repo.LoanApplicationDocumentChecklistRepository;
import com.bhawana.lms.repo.LoanApplicationRepository;
import com.bhawana.lms.repo.LoanApplicationStatusTransitionRepository;
import com.bhawana.lms.repo.LoanDisbursementRequestLogRepository;
import com.bhawana.lms.service.DisbursementIntentWorkflowService;
import com.bhawana.lms.service.LoanApplicationStatusWriter;
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
import org.mockito.Mockito;
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
 * Terminal bank results are applied to the loan atomically: crash-before-commit rolls back
 * observation and application together, stranded terminal evidence is repaired once from stored
 * evidence with zero re-initiations, and a delayed failed poll cannot undo an accepted success.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestExecutionListeners(
        value = TenantContextTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS
)
class C02TerminalResultAtomicityIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private LoanApplicationRepository loanApplicationRepository;
    @Autowired private LoanAccountRepository loanAccountRepository;
    @Autowired private LoanApplicationDocumentChecklistRepository loanApplicationDocumentChecklistRepository;
    @Autowired private DisbursementIntentRepository disbursementIntentRepository;
    @Autowired private LoanDisbursementRequestLogRepository loanDisbursementRequestLogRepository;
    @Autowired private LoanApplicationStatusTransitionRepository loanApplicationStatusTransitionRepository;
    @Autowired private DisbursementIntentWorkflowService disbursementIntentWorkflowService;
    @Autowired private LoanDisbursementCommandService loanDisbursementCommandService;
    @Autowired private LoanDisbursementWorkerService loanDisbursementWorkerService;

    @MockitoSpyBean
    private LoanDisbursementAdapter loanDisbursementAdapter;

    @MockitoSpyBean
    private LoanApplicationStatusWriter loanApplicationStatusWriter;

    @BeforeEach
    void resetMocks() {
        reset(loanDisbursementAdapter, loanApplicationStatusWriter);
    }

    @Test
    void terminalInitiateAppliesLoanAtomicallyAndAutoResolveIsANoOp() throws Exception {
        UUID applicationId = seedApproved("HDFC0001234", new BigDecimal("45000.00"));
        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/disbursement-requests", applicationId)
                        .with(systemAdmin()))
                .andExpect(status().isOk());

        // No provider contact until the worker executes the committed intent.
        verify(loanDisbursementAdapter, never()).requestDisbursement(any());

        disbursementIntentWorkflowService.executeForApplication(applicationId);

        LoanAccount account = loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow();
        DisbursementIntent intent =
                disbursementIntentRepository.findTopByLoanAccount_IdAndStateOrderByCreatedAtDesc(
                        account.getId(), DisbursementIntentState.SUCCEEDED).orElseThrow();
        assertEquals(DisbursementIntentState.SUCCEEDED, intent.getState());
        assertEquals(LoanAccountStatus.DISBURSED, account.getStatus());
        assertEquals(LoanApplicationStatus.DISBURSED,
                loanApplicationRepository.findById(applicationId).orElseThrow().getStatus());
        verify(loanDisbursementAdapter, times(1)).requestDisbursement(any());

        int disbursedTransitions = loanApplicationStatusTransitionRepository
                .findByLoanApplication_IdAndToStatusOrderByCreatedAtAsc(applicationId, LoanApplicationStatus.DISBURSED)
                .size();

        // The old second commit is now a harmless no-op: nothing left to resolve.
        assertNull(loanDisbursementCommandService.autoResolveAfterInitiate(
                applicationId, "ops.admin", null, "c02-noop"));
        assertEquals(disbursedTransitions, loanApplicationStatusTransitionRepository
                .findByLoanApplication_IdAndToStatusOrderByCreatedAtAsc(applicationId, LoanApplicationStatus.DISBURSED)
                .size());
        verify(loanDisbursementAdapter, times(1)).requestDisbursement(any());
    }

    @Test
    void crashBeforeResultCommitRollsBackObservationAndApplicationTogether() throws Exception {
        UUID applicationId = seedApproved("HDFC0001234", new BigDecimal("45000.00"));
        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/disbursement-requests", applicationId)
                        .with(systemAdmin()))
                .andExpect(status().isOk());

        // Fail inside the atomic result transaction, before it can commit.
        doThrow(new IllegalStateException("simulated crash before result commit"))
                .when(loanApplicationStatusWriter).updateStatus(any(), any());

        assertThrows(IllegalStateException.class,
                () -> disbursementIntentWorkflowService.executeForApplication(applicationId));

        // Observation and application rolled back as a unit: no terminal intent, no moved loan.
        LoanAccount account = loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow();
        assertEquals(LoanAccountStatus.DISBURSEMENT_REQUESTED, account.getStatus());
        assertEquals(LoanApplicationStatus.APPROVED_PENDING_DISBURSAL,
                loanApplicationRepository.findById(applicationId).orElseThrow().getStatus());
        DisbursementIntent intent =
                disbursementIntentRepository.findLiveByLoanAccountId(account.getId()).orElseThrow();
        assertEquals(DisbursementIntentState.REQUESTED, intent.getState());
        LoanDisbursementRequestLog latest = loanDisbursementRequestLogRepository
                .findTopByLoanAccount_IdOrderByCreatedAtDesc(account.getId()).orElseThrow();
        assertEquals(DisbursementDisposition.PENDING.name(), latest.getProviderStatus());

        // Recovery polls the ORIGINAL reference and never initiates again.
        Mockito.doCallRealMethod().when(loanApplicationStatusWriter).updateStatus(any(), any());
        doAnswer(invocation -> {
            LoanDisbursementAdapter.DisbursementStatusQuery query = invocation.getArgument(0);
            return new LoanDisbursementAdapter.DisbursementStatusResult(
                    "0", "Check Transaction Successful",
                    DisbursementDisposition.SUCCESS, DisbursementDeclineKind.NONE,
                    "0", "RRN-C02-RECOVERY", "recovered terminal success", "{}");
        }).when(loanDisbursementAdapter).checkStatus(any());

        assertTrue(loanDisbursementCommandService.pollPendingDisbursement(
                applicationId, "worker", null, "c02-recovery"));
        assertEquals(LoanAccountStatus.DISBURSED,
                loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow().getStatus());
        verify(loanDisbursementAdapter, times(1)).requestDisbursement(any());
    }

    @Test
    void strandedTerminalIntentIsRepairedOnceWithOriginalReference() throws Exception {
        // Seed a PENDING acceptance (IMPS timeout codes stay PENDING on the payment call).
        UUID applicationId = seedApproved("MOCK0PENDOK", new BigDecimal("45000.00"));
        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/disbursement-requests", applicationId)
                        .with(systemAdmin()))
                .andExpect(status().isOk());
        disbursementIntentWorkflowService.executeForApplication(applicationId);

        LoanAccount accepted = loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow();
        DisbursementIntent pendingIntent =
                disbursementIntentRepository.findLiveByLoanAccountId(accepted.getId()).orElseThrow();
        assertEquals(DisbursementIntentState.REQUESTED, pendingIntent.getState());
        String tranRefNo = pendingIntent.getTranRefNo();

        // Simulate stored terminal evidence without the loan move. Only the stored evidence
        // is touched — never a new initiation.
        DisbursementIntent storedIntent =
                disbursementIntentRepository.findById(pendingIntent.getId()).orElseThrow();
        storedIntent.recordProviderResponse(DisbursementIntentState.SUCCEEDED, tranRefNo, "0", "RRN-C02-STRANDED",
                DisbursementDeclineKind.NONE);
        disbursementIntentRepository.save(storedIntent);
        LoanDisbursementRequestLog storedLog = loanDisbursementRequestLogRepository
                .findTopByLoanAccount_IdOrderByCreatedAtDesc(accepted.getId()).orElseThrow();
        storedLog.updateProviderSubmission("MOCK_ICICI", tranRefNo, "SUCCESS",
                storedLog.getPaymentMode(), "0", "RRN-C02-STRANDED", DisbursementDeclineKind.NONE,
                "{\"disposition\":\"SUCCESS\",\"tranRefNo\":\"" + tranRefNo + "\"}");
        loanDisbursementRequestLogRepository.save(storedLog);
        assertEquals(LoanAccountStatus.DISBURSEMENT_REQUESTED,
                loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow().getStatus());

        int providerCallsBeforeRepair = Mockito.mockingDetails(loanDisbursementAdapter).getInvocations().size();

        int repaired = disbursementIntentWorkflowService.repairStrandedTerminalDisbursements(10);
        assertEquals(1, repaired);
        assertEquals(LoanAccountStatus.DISBURSED,
                loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow().getStatus());
        assertEquals(LoanApplicationStatus.DISBURSED,
                loanApplicationRepository.findById(applicationId).orElseThrow().getStatus());

        // Original provider evidence is preserved under its original reference.
        DisbursementIntent repairedIntent = disbursementIntentRepository.findById(pendingIntent.getId()).orElseThrow();
        assertEquals(DisbursementIntentState.SUCCEEDED, repairedIntent.getState());
        assertEquals(tranRefNo, repairedIntent.getTranRefNo());
        LoanDisbursementRequestLog repairedLog = loanDisbursementRequestLogRepository
                .findTopByLoanAccount_IdOrderByCreatedAtDesc(accepted.getId()).orElseThrow();
        assertEquals(tranRefNo, repairedLog.getTranRefNo());
        assertEquals("RRN-C02-STRANDED", repairedLog.getBankRrn());
        assertEquals("0", repairedLog.getProviderActCode());

        // Exactly one DISBURSED transition; repeat repair is a no-op with zero new bank calls.
        assertEquals(1, loanApplicationStatusTransitionRepository
                .findByLoanApplication_IdAndToStatusOrderByCreatedAtAsc(applicationId, LoanApplicationStatus.DISBURSED)
                .size());
        assertEquals(0, disbursementIntentWorkflowService.repairStrandedTerminalDisbursements(10));
        assertEquals(1, loanApplicationStatusTransitionRepository
                .findByLoanApplication_IdAndToStatusOrderByCreatedAtAsc(applicationId, LoanApplicationStatus.DISBURSED)
                .size());
        assertEquals(providerCallsBeforeRepair,
                Mockito.mockingDetails(loanDisbursementAdapter).getInvocations().size());
    }

    @Test
    void delayedFailedPollCannotUndoAcceptedSuccess() throws Exception {
        UUID applicationId = seedApproved("HDFC0001234", new BigDecimal("45000.00"));
        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/disbursement-requests", applicationId)
                        .with(systemAdmin()))
                .andExpect(status().isOk());
        disbursementIntentWorkflowService.executeForApplication(applicationId);
        assertEquals(LoanAccountStatus.DISBURSED,
                loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow().getStatus());

        LoanAccount disbursed = loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow();
        String successRrn = loanDisbursementRequestLogRepository
                .findTopByLoanAccount_IdOrderByCreatedAtDesc(disbursed.getId()).orElseThrow().getBankRrn();
        assertNotNull(successRrn);

        // A delayed FAILED status for the same loan arrives after SUCCESS was accepted.
        doAnswer(invocation -> new LoanDisbursementAdapter.DisbursementStatusResult(
                "0", "Check Transaction Successful",
                DisbursementDisposition.FAILED, DisbursementDeclineKind.TECHNICAL,
                "11", null, "delayed failure must not regress success", "{}"))
                .when(loanDisbursementAdapter).checkStatus(any());

        loanDisbursementCommandService.pollPendingDisbursement(applicationId, "worker", null, "c02-ordering");

        assertEquals(LoanAccountStatus.DISBURSED,
                loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow().getStatus());
        assertEquals(LoanApplicationStatus.DISBURSED,
                loanApplicationRepository.findById(applicationId).orElseThrow().getStatus());
        LoanAccount stillDisbursed = loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow();
        DisbursementIntent intent = disbursementIntentRepository.findById(
                disbursementIntentRepository.findTopByLoanAccount_IdAndStateOrderByCreatedAtDesc(
                        stillDisbursed.getId(), DisbursementIntentState.SUCCEEDED).orElseThrow().getId())
                .orElseThrow();
        assertEquals(DisbursementIntentState.SUCCEEDED, intent.getState());
        assertEquals(successRrn, loanDisbursementRequestLogRepository
                .findTopByLoanAccount_IdOrderByCreatedAtDesc(stillDisbursed.getId()).orElseThrow().getBankRrn());
        verify(loanDisbursementAdapter, times(1)).requestDisbursement(any());
    }

    private UUID seedApproved(String ifsc, BigDecimal requestedAmount) throws Exception {
        String lspId = createLspViaAdmin();
        String productId = createProductViaAdmin();
        mapProductToLsp(productId, lspId);
        String applicationId = createApplicationViaOps(lspId, productId, requestedAmount);
        transition(applicationId, "AWAITING_APPROVAL", "Ready for approval");
        markKycComplete(applicationId);
        transition(applicationId, "APPROVED_PENDING_DISBURSAL", "Approved for C02 atomicity test");
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
                                "bankName", "C02 Bank",
                                "ifscCode", ifsc,
                                "accountHolderName", "C02 Borrower"
                        ))))
                .andExpect(status().isOk());
    }

    private String createLspViaAdmin() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/internal/admin/lsps")
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", "LSP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                                "name", "C02 LSP",
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
                                "name", "C02 product " + code,
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
        payload.put("borrowerFullName", "C02 Borrower");
        payload.put("borrowerMobile", mobileForPan(borrowerPan));
        payload.put("borrowerEmail", "c02+" + borrowerPan.toLowerCase() + "@example.com");
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
                            "Uploaded for C02 atomicity test",
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
