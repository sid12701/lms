package com.bhawana.lms.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bhawana.lms.common.api.error.ApiConflictException;
import com.bhawana.lms.common.api.error.BusinessRuleViolationException;
import com.bhawana.lms.domain.DisbursementIntentState;
import com.bhawana.lms.domain.LoanAccount;
import com.bhawana.lms.domain.LoanAccountStatus;
import com.bhawana.lms.domain.LoanApplication;
import com.bhawana.lms.domain.LoanApplicationDocumentChecklistStatus;
import com.bhawana.lms.domain.LoanApplicationStatus;
import com.bhawana.lms.domain.LoanInvalidationReason;
import com.bhawana.lms.domain.LspStatus;
import com.bhawana.lms.repo.DisbursementIntentRepository;
import com.bhawana.lms.repo.LoanAccountRepository;
import com.bhawana.lms.repo.LoanApplicationDocumentChecklistRepository;
import com.bhawana.lms.repo.LoanApplicationRepository;
import com.bhawana.lms.repo.LoanApplicationStatusTransitionRepository;
import com.bhawana.lms.repo.LspRepository;
import com.bhawana.lms.service.DisbursementIntentWorkflowService;
import com.bhawana.lms.service.LoanApplicationLifecycleService;
import com.bhawana.lms.service.LoanDisbursementAdapter;
import com.bhawana.lms.service.LoanDisbursementCommandService;
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
 * Invalidation and queued submission share one cancellation boundary.
 *
 * <p>Conservative guard: invalidation is rejected while a live disbursement intent exists
 * or the account is REQUESTED/PENDING_RECONCILIATION, and submission preparation re-reads
 * application eligibility plus LSP state under the shared application → account → intent
 * lock order before the CREATED → REQUESTED transition.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestExecutionListeners(
        value = TenantContextTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS
)
class InvalidationSubmissionGuardIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private LoanApplicationRepository loanApplicationRepository;
    @Autowired private LoanAccountRepository loanAccountRepository;
    @Autowired private LoanApplicationDocumentChecklistRepository loanApplicationDocumentChecklistRepository;
    @Autowired private DisbursementIntentRepository disbursementIntentRepository;
    @Autowired private LoanApplicationStatusTransitionRepository loanApplicationStatusTransitionRepository;
    @Autowired private LspRepository lspRepository;
    @Autowired private DisbursementIntentWorkflowService disbursementIntentWorkflowService;
    @Autowired private LoanDisbursementCommandService loanDisbursementCommandService;
    @Autowired private LoanApplicationLifecycleService loanApplicationLifecycleService;

    @MockitoSpyBean
    private LoanDisbursementAdapter loanDisbursementAdapter;

    @BeforeEach
    void resetProviderMock() {
        Mockito.reset(loanDisbursementAdapter);
    }

    @Test
    void invalidationIsRejectedWhenLiveIntentExistsAndLeavesStateUnchanged() throws Exception {
        UUID applicationId = seedApproved("HDFC0001234", new BigDecimal("45000.00"));

        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/disbursement-requests", applicationId)
                        .with(systemAdmin()))
                .andExpect(status().isOk());

        LoanAccount account = loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow();
        assertEquals(LoanAccountStatus.DISBURSEMENT_REQUESTED, account.getStatus());
        assertEquals(
                DisbursementIntentState.CREATED,
                disbursementIntentRepository.findLiveByLoanAccountId(account.getId()).orElseThrow().getState());

        UUID lspId = detailedApplication(applicationId).getLsp().getId();
        long invalidTransitionsBefore = countInvalidTransitions(applicationId);

        ApiConflictException conflict = assertThrows(
                ApiConflictException.class,
                () -> loanApplicationLifecycleService.invalidateApplicationForLsp(
                        lspId, applicationId, "ops.admin", LoanInvalidationReason.DUPLICATE_APPLICATION, null));
        assertEquals("DISBURSEMENT_IN_PROGRESS", conflict.getErrorCode());

        // Every row is unchanged: statuses, live intent, and no INVALID history entry.
        assertEquals(
                LoanApplicationStatus.APPROVED_PENDING_DISBURSAL,
                loanApplicationRepository.findById(applicationId).orElseThrow().getStatus());
        assertEquals(
                LoanAccountStatus.DISBURSEMENT_REQUESTED,
                loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow().getStatus());
        assertEquals(
                DisbursementIntentState.CREATED,
                disbursementIntentRepository
                        .findLiveByLoanAccountId(account.getId()).orElseThrow().getState());
        assertEquals(invalidTransitionsBefore, countInvalidTransitions(applicationId));
        verify(loanDisbursementAdapter, never()).requestDisbursement(any());
    }

    @Test
    void initiationIsRejectedAfterSuccessfulInvalidation() throws Exception {
        UUID applicationId = seedApproved("HDFC0001234", new BigDecimal("45000.00"));
        UUID lspId = detailedApplication(applicationId).getLsp().getId();

        loanApplicationLifecycleService.invalidateApplicationForLsp(
                lspId, applicationId, "ops.admin", LoanInvalidationReason.DUPLICATE_APPLICATION, null);

        assertEquals(
                LoanApplicationStatus.INVALID,
                loanApplicationRepository.findById(applicationId).orElseThrow().getStatus());
        assertEquals(
                LoanAccountStatus.INVALID,
                loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow().getStatus());

        // The pre-invalidation caller path rejects the disbursement; no intent or bank call follows.
        assertThrows(
                BusinessRuleViolationException.class,
                () -> loanDisbursementCommandService.initiateDisbursement(applicationId, "ops.admin"));

        LoanAccount account = loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow();
        assertTrue(disbursementIntentRepository.findLiveByLoanAccountId(account.getId()).isEmpty());
        verify(loanDisbursementAdapter, never()).requestDisbursement(any());
        assertEquals(
                LoanApplicationStatus.INVALID,
                loanApplicationRepository.findById(applicationId).orElseThrow().getStatus());
        assertEquals(1, countInvalidTransitions(applicationId));
    }

    @Test
    void submissionPreparationRechecksLspEnabledState() throws Exception {
        UUID applicationId = seedApproved("HDFC0001234", new BigDecimal("45000.00"));

        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/disbursement-requests", applicationId)
                        .with(systemAdmin()))
                .andExpect(status().isOk());

        UUID lspId = detailedApplication(applicationId).getLsp().getId();
        var lsp = lspRepository.findById(lspId).orElseThrow();
        lsp.updateStatus(LspStatus.INACTIVE);
        lspRepository.save(lsp);

        // Preparation must re-read eligibility: no submission permission, provider untouched.
        assertTrue(disbursementIntentWorkflowService.executeForApplication(applicationId).isEmpty());
        verify(loanDisbursementAdapter, never()).requestDisbursement(any());

        LoanAccount account = loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow();
        assertEquals(
                DisbursementIntentState.CREATED,
                disbursementIntentRepository.findLiveByLoanAccountId(account.getId()).orElseThrow().getState());
        assertEquals(LoanAccountStatus.DISBURSEMENT_REQUESTED, account.getStatus());
    }

    @Test
    void invalidationIsRejectedForUncertainInstructionAndStaysRecoverable() throws Exception {
        UUID applicationId = seedApproved("HDFC0001234", new BigDecimal("45000.00"));

        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/disbursement-requests", applicationId)
                        .with(systemAdmin()))
                .andExpect(status().isOk());

        doAnswer(invocation -> {
            throw new IllegalStateException("provider response lost");
        }).when(loanDisbursementAdapter).requestDisbursement(any());

        disbursementIntentWorkflowService.executeForApplication(applicationId);

        LoanAccount account = loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow();
        assertEquals(
                DisbursementIntentState.UNKNOWN,
                disbursementIntentRepository.findLiveByLoanAccountId(account.getId()).orElseThrow().getState());

        UUID lspId = detailedApplication(applicationId).getLsp().getId();
        ApiConflictException conflict = assertThrows(
                ApiConflictException.class,
                () -> loanApplicationLifecycleService.invalidateApplicationForLsp(
                        lspId, applicationId, "ops.admin", LoanInvalidationReason.DUPLICATE_APPLICATION, null));
        assertEquals("DISBURSEMENT_IN_PROGRESS", conflict.getErrorCode());

        // The uncertain instruction keeps its original reference and stays recoverable.
        assertTrue(disbursementIntentWorkflowService.loadStatusPollContext(applicationId).isPresent());
        assertEquals(
                DisbursementIntentState.UNKNOWN,
                disbursementIntentRepository.findLiveByLoanAccountId(account.getId()).orElseThrow().getState());
    }

    private long countInvalidTransitions(UUID applicationId) {
        return loanApplicationStatusTransitionRepository
                .findTop20ByLoanApplication_IdOrderByCreatedAtDesc(applicationId)
                .stream()
                .filter(transition -> transition.getToStatus() == LoanApplicationStatus.INVALID)
                .count();
    }

    private LoanApplication detailedApplication(UUID applicationId) {
        return loanApplicationRepository.findDetailedById(applicationId).orElseThrow();
    }

    private UUID seedApproved(String ifsc, BigDecimal requestedAmount) throws Exception {
        String lspId = createLspViaAdmin();
        String productId = createProductViaAdmin();
        mapProductToLsp(productId, lspId);
        String applicationId = createApplicationViaOps(lspId, productId, requestedAmount);
        transition(applicationId, "AWAITING_APPROVAL", "Ready for approval");
        markKycComplete(applicationId);
        transition(applicationId, "APPROVED_PENDING_DISBURSAL", "Approved for invalidation guard test");
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
                                "bankName", "Intent Bank",
                                "ifscCode", ifsc,
                                "accountHolderName", "Intent Borrower"
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
        payload.put("borrowerFullName", "Intent Borrower");
        payload.put("borrowerMobile", mobileForPan(borrowerPan));
        payload.put("borrowerEmail", "t01+" + borrowerPan.toLowerCase() + "@example.com");
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
                            "Uploaded for guard test",
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
