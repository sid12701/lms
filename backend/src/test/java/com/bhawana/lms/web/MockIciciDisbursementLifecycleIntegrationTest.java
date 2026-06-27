package com.bhawana.lms.web;

import com.bhawana.lms.support.TenantContextTestExecutionListener;
import org.springframework.test.context.TestExecutionListeners;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bhawana.lms.domain.DisbursementPaymentMode;
import com.bhawana.lms.domain.LoanAccount;
import com.bhawana.lms.domain.LoanAccountStatus;
import com.bhawana.lms.domain.LoanApplicationDocumentChecklistStatus;
import com.bhawana.lms.domain.LoanApplicationStatus;
import com.bhawana.lms.domain.LoanDisbursementRequestLog;
import com.bhawana.lms.domain.OpsAlertType;
import com.bhawana.lms.repo.LoanAccountRepository;
import com.bhawana.lms.repo.OpsAlertRepository;
import com.bhawana.lms.repo.LoanApplicationDocumentChecklistRepository;
import com.bhawana.lms.repo.LoanApplicationRepository;
import com.bhawana.lms.repo.LoanDisbursementRequestLogRepository;
import com.bhawana.lms.service.LoanDisbursementWorkerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * End-to-end coverage of the mock ICICI Composite Pay disbursement lifecycle: synchronous IMPS
 * success, business vs technical declines (reject vs retry), the deferred status-check loop, the
 * NEFT rail and the stuck → reconciliation parking path.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestExecutionListeners(
        value = TenantContextTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS
)
class MockIciciDisbursementLifecycleIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private LoanApplicationRepository loanApplicationRepository;
    @Autowired private LoanAccountRepository loanAccountRepository;
    @Autowired private LoanDisbursementRequestLogRepository loanDisbursementRequestLogRepository;
    @Autowired private LoanApplicationDocumentChecklistRepository loanApplicationDocumentChecklistRepository;
    @Autowired private LoanDisbursementWorkerService loanDisbursementWorkerService;
    @Autowired private OpsAlertRepository opsAlertRepository;

    @Test
    void defaultIfscDisbursesSynchronouslyOverImps() {
        UUID applicationId = seedApproved("HDFC0001234", new BigDecimal("45000.00"));

        loanDisbursementWorkerService.processApplication(applicationId);

        assertEquals(LoanApplicationStatus.DISBURSED, applicationStatus(applicationId));
        assertEquals(LoanAccountStatus.DISBURSED, accountStatus(applicationId));
        LoanDisbursementRequestLog log = latestLog(applicationId);
        assertEquals(DisbursementPaymentMode.IMPS, log.getPaymentMode());
        assertEquals("0", log.getProviderActCode());
        assertNotNull(log.getTranRefNo());
    }

    @Test
    void businessDeclineRejectsWithoutRetry() {
        UUID applicationId = seedApproved("MOCK0INSUFF", new BigDecimal("45000.00"));

        loanDisbursementWorkerService.processApplication(applicationId);

        assertEquals(LoanApplicationStatus.REJECTED, applicationStatus(applicationId));
        assertEquals(LoanAccountStatus.DISBURSEMENT_FAILED, accountStatus(applicationId));
        assertEquals("51", latestLog(applicationId).getProviderActCode());
    }

    @Test
    void technicalDeclineGoesToRetry() {
        UUID applicationId = seedApproved("MOCK0NPCIDN", new BigDecimal("45000.00"));

        loanDisbursementWorkerService.processApplication(applicationId);

        assertEquals(LoanApplicationStatus.DISBURSEMENT_RETRY, applicationStatus(applicationId));
        assertEquals(LoanAccountStatus.DISBURSEMENT_FAILED, accountStatus(applicationId));
        assertEquals("18", latestLog(applicationId).getProviderActCode());
    }

    @Test
    void pendingTransactionResolvesViaStatusCheck() {
        UUID applicationId = seedApproved("MOCK0PENDOK", new BigDecimal("45000.00"));

        loanDisbursementWorkerService.processApplication(applicationId);
        // Request accepted but not yet terminal — left for the status-check worker.
        assertEquals(LoanAccountStatus.DISBURSEMENT_REQUESTED, accountStatus(applicationId));
        assertEquals(LoanApplicationStatus.APPROVED_PENDING_DISBURSAL, applicationStatus(applicationId));

        // First poll is too early (CheckStatusCode 100); the second resolves to success.
        loanDisbursementWorkerService.processPendingStatusChecks();
        assertEquals(LoanAccountStatus.DISBURSEMENT_REQUESTED, accountStatus(applicationId));
        loanDisbursementWorkerService.processPendingStatusChecks();

        assertEquals(LoanApplicationStatus.DISBURSED, applicationStatus(applicationId));
        assertEquals(LoanAccountStatus.DISBURSED, accountStatus(applicationId));
    }

    @Test
    void neftRailIsDeferredThenDisbursed() {
        UUID applicationId = seedApproved("HDFC0001234", new BigDecimal("600000.00"));

        loanDisbursementWorkerService.processApplication(applicationId);
        assertEquals(LoanAccountStatus.DISBURSEMENT_REQUESTED, accountStatus(applicationId));
        assertEquals(DisbursementPaymentMode.NEFT, latestLog(applicationId).getPaymentMode());

        loanDisbursementWorkerService.processPendingStatusChecks();
        loanDisbursementWorkerService.processPendingStatusChecks();

        assertEquals(LoanApplicationStatus.DISBURSED, applicationStatus(applicationId));
        assertEquals(LoanAccountStatus.DISBURSED, accountStatus(applicationId));
    }

    @Test
    void stuckTransactionParksForReconciliation() {
        UUID applicationId = seedApproved("MOCK0STUCK0", new BigDecimal("45000.00"));

        loanDisbursementWorkerService.processApplication(applicationId);
        assertEquals(LoanAccountStatus.DISBURSEMENT_REQUESTED, accountStatus(applicationId));

        // Poll until the cap (test profile max-polls = 2) is reached.
        loanDisbursementWorkerService.processPendingStatusChecks();
        loanDisbursementWorkerService.processPendingStatusChecks();

        assertEquals(LoanAccountStatus.DISBURSEMENT_PENDING_RECONCILIATION, accountStatus(applicationId));
        assertEquals(LoanApplicationStatus.DISBURSEMENT_RETRY, applicationStatus(applicationId));

        boolean reconciliationAlertRaised = opsAlertRepository.findAll().stream()
                .anyMatch(alert -> alert.getType() == OpsAlertType.LSP_BOUND_VIOLATION
                        && applicationId.equals(alert.getSubjectId())
                        && alert.getMessage() != null
                        && alert.getMessage().contains("parked for manual reconciliation"));
        assertTrue(reconciliationAlertRaised, "Expected an ops alert when a disbursement is parked for reconciliation");
    }

    // --- helpers ---

    private LoanApplicationStatus applicationStatus(UUID applicationId) {
        return loanApplicationRepository.findById(applicationId).orElseThrow().getStatus();
    }

    private LoanAccountStatus accountStatus(UUID applicationId) {
        return loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow().getStatus();
    }

    private LoanDisbursementRequestLog latestLog(UUID applicationId) {
        LoanAccount account = loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow();
        return loanDisbursementRequestLogRepository
                .findTopByLoanAccount_IdOrderByCreatedAtDesc(account.getId())
                .orElseThrow();
    }

    private UUID seedApproved(String ifsc, BigDecimal requestedAmount) {
        try {
            String lspId = createLspViaAdmin();
            String productId = createProductViaAdmin();
            mapProductToLsp(productId, lspId);
            String applicationId = createApplicationViaOps(lspId, productId, requestedAmount);
            transition(applicationId, "AWAITING_APPROVAL", "Ready for approval");
            markKycComplete(applicationId);
            transition(applicationId, "APPROVED_PENDING_DISBURSAL", "Approved for lifecycle test");
            seedBorrowerBankDetails(applicationId, ifsc);
            return UUID.fromString(applicationId);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to seed approved application", exception);
        }
    }

    private void seedBorrowerBankDetails(String applicationId, String ifsc) throws Exception {
        String borrowerId = loanApplicationRepository.findById(UUID.fromString(applicationId)).orElseThrow()
                .getBorrower().getId().toString();
        mockMvc.perform(patch("/api/v1/internal/admin/borrowers/{borrowerId}/bank-details", borrowerId)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "bankAccountNumber", "123456789012",
                                "bankName", "Lifecycle Bank",
                                "ifscCode", ifsc,
                                "accountHolderName", "Lifecycle Borrower"
                        ))))
                .andExpect(status().isOk());
    }

    private String createLspViaAdmin() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/internal/admin/lsps")
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", "LSP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                                "name", "Lifecycle LSP",
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
                                "name", "Lifecycle product " + code,
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
        String borrowerPan = uniquePan();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("lspId", lspId);
        payload.put("productId", productId);
        payload.put("externalLoanId", "EXT-" + UUID.randomUUID().toString().substring(0, 8));
        payload.put("sourceChannel", "API");
        payload.put("borrowerPan", borrowerPan);
        payload.put("borrowerFullName", "Lifecycle Borrower");
        payload.put("borrowerMobile", mobileForPan(borrowerPan));
        payload.put("borrowerEmail", "lifecycle+" + borrowerPan.toLowerCase() + "@example.com");
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
                            "Uploaded for lifecycle test",
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

    private static String uniquePan() {
        int suffix = Math.abs(UUID.randomUUID().hashCode()) % 10_000;
        return String.format("ABCDE%04dF", suffix);
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
