package com.bhawana.lms.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bhawana.lms.domain.LoanAccountStatus;
import com.bhawana.lms.domain.LoanApplicationDocumentChecklistStatus;
import com.bhawana.lms.domain.LoanApplicationStatus;
import com.bhawana.lms.repo.DisbursementIntentRepository;
import com.bhawana.lms.repo.LoanAccountRepository;
import com.bhawana.lms.repo.LoanApplicationDocumentChecklistRepository;
import com.bhawana.lms.repo.LoanApplicationRepository;
import com.bhawana.lms.repo.LoanApplicationStatusTransitionRepository;
import com.bhawana.lms.repo.LoanRepaymentScheduleInstallmentRepository;
import com.bhawana.lms.service.DisbursementIntentWorkflowService;
import com.bhawana.lms.service.LoanDisbursementCommandService;
import com.bhawana.lms.support.TenantContextTestExecutionListener;
import com.bhawana.lms.support.TestPanSequence;
import com.fasterxml.jackson.databind.JsonNode;
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
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * H31 — generic status commands must not write financial lifecycle states without the
 * bank/receipt evidence that makes those states true. Direct DISBURSED/CLOSED targets are
 * rejected from both the standard and the manual generic endpoints, while the legitimate
 * writers (accepted bank outcome, final EMI, foreclosure settlement) keep working.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestExecutionListeners(
        value = TenantContextTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS
)
class H31FinancialStatusGuardIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private LoanApplicationRepository loanApplicationRepository;
    @Autowired private LoanAccountRepository loanAccountRepository;
    @Autowired private LoanApplicationDocumentChecklistRepository loanApplicationDocumentChecklistRepository;
    @Autowired private LoanApplicationStatusTransitionRepository loanApplicationStatusTransitionRepository;
    @Autowired private LoanRepaymentScheduleInstallmentRepository loanRepaymentScheduleInstallmentRepository;
    @Autowired private DisbursementIntentRepository disbursementIntentRepository;
    @Autowired private DisbursementIntentWorkflowService disbursementIntentWorkflowService;
    @Autowired private LoanDisbursementCommandService loanDisbursementCommandService;

    @Test
    void standardTransitionToDisbursedIsRejectedWithoutBankEvidence() throws Exception {
        UUID applicationId = seedApproved("HDFC0001234", new BigDecimal("45000.00"));

        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/status-transitions", applicationId)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "targetStatus", "DISBURSED",
                                "note", "Force disbursal without bank evidence"
                        ))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("FINANCIAL_STATUS_REQUIRES_EVIDENCE"));

        // No unfunded loan becomes DISBURSED merely through a status request.
        assertEquals(LoanApplicationStatus.APPROVED_PENDING_DISBURSAL,
                loanApplicationRepository.findById(applicationId).orElseThrow().getStatus());
        assertEquals(LoanAccountStatus.PENDING_DISBURSEMENT,
                loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow().getStatus());
        assertEquals(0, loanApplicationStatusTransitionRepository
                .findByLoanApplication_IdAndToStatusOrderByCreatedAtAsc(applicationId, LoanApplicationStatus.DISBURSED)
                .size());
    }

    @Test
    void standardTransitionToClosedIsRejectedWithoutSettlement() throws Exception {
        UUID applicationId = seedDisbursed("HDFC0001234", new BigDecimal("45000.00"));

        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/status-transitions", applicationId)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "targetStatus", "CLOSED",
                                "note", "Force close without settlement evidence"
                        ))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("FINANCIAL_STATUS_REQUIRES_EVIDENCE"));

        // No unpaid loan closes merely through a status request.
        assertEquals(LoanApplicationStatus.DISBURSED,
                loanApplicationRepository.findById(applicationId).orElseThrow().getStatus());
        assertEquals(LoanAccountStatus.DISBURSED,
                loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow().getStatus());
        assertEquals(0, loanApplicationStatusTransitionRepository
                .findByLoanApplication_IdAndToStatusOrderByCreatedAtAsc(applicationId, LoanApplicationStatus.CLOSED)
                .size());
    }

    @Test
    void standardTransitionToForeclosedIsRejectedWithoutSettlement() throws Exception {
        UUID applicationId = seedDisbursed("HDFC0001234", new BigDecimal("45000.00"));

        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/status-transitions", applicationId)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "targetStatus", "FORECLOSED",
                                "note", "Force foreclosure without settlement evidence"
                        ))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("FINANCIAL_STATUS_REQUIRES_EVIDENCE"));

        // No unsettled loan forecloses merely through a status request.
        assertEquals(LoanApplicationStatus.DISBURSED,
                loanApplicationRepository.findById(applicationId).orElseThrow().getStatus());
        assertEquals(LoanAccountStatus.DISBURSED,
                loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow().getStatus());
        assertEquals(0, loanApplicationStatusTransitionRepository
                .findByLoanApplication_IdAndToStatusOrderByCreatedAtAsc(applicationId, LoanApplicationStatus.FORECLOSED)
                .size());
    }

    @Test
    void manualOverrideToDisbursedAndClosedIsRejectedWithFinancialEvidenceError() throws Exception {
        UUID applicationId = seedAwaitingApproval();

        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/manual-status", applicationId)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "targetStatus", "DISBURSED",
                                "note", "Manual disbursal without bank evidence",
                                "reasonCode", "MANUAL_ADMIN_OVERRIDE"
                        ))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("FINANCIAL_STATUS_REQUIRES_EVIDENCE"));

        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/manual-status", applicationId)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "targetStatus", "CLOSED",
                                "note", "Manual close without settlement evidence",
                                "reasonCode", "MANUAL_ADMIN_OVERRIDE"
                        ))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("FINANCIAL_STATUS_REQUIRES_EVIDENCE"));

        assertEquals(LoanApplicationStatus.AWAITING_APPROVAL,
                loanApplicationRepository.findById(applicationId).orElseThrow().getStatus());
    }

    @Test
    void unprivilegedActorIsStillBlockedByAuthorization() throws Exception {
        UUID applicationId = seedApproved("HDFC0001234", new BigDecimal("45000.00"));

        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/status-transitions", applicationId)
                        .with(opsUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "targetStatus", "DISBURSED",
                                "note", "Unprivileged bypass attempt"
                        ))))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/manual-status", applicationId)
                        .with(opsUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "targetStatus", "CLOSED",
                                "note", "Unprivileged bypass attempt",
                                "reasonCode", "MANUAL_ADMIN_OVERRIDE"
                        ))))
                .andExpect(status().isForbidden());
    }

    @Test
    void legitimateBankOutcomeStillDisburses() throws Exception {
        // Pending-first scenario: execution leaves the loan REQUESTED with a stored request,
        // so the mock-outcome route resolves it — the legitimate evidence-backed writer.
        UUID applicationId = seedApproved("MOCK0PENDOK", new BigDecimal("45000.00"));

        // C02's legitimate writer: accepted bank outcome via the mock-outcome route.
        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/disbursement-requests", applicationId)
                        .with(systemAdmin()))
                .andExpect(status().isOk());
        disbursementIntentWorkflowService.executeForApplication(applicationId);
        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/disbursement-requests/mock-outcome", applicationId)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("outcome", "DISBURSED"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISBURSED"))
                .andExpect(jsonPath("$.loanAccount.status").value("DISBURSED"));

        assertEquals(1, loanApplicationStatusTransitionRepository
                .findByLoanApplication_IdAndToStatusOrderByCreatedAtAsc(applicationId, LoanApplicationStatus.DISBURSED)
                .size());
    }

    @Test
    void legitimateFullRepaymentStillCloses() throws Exception {
        UUID applicationId = seedDisbursed("HDFC0001234", new BigDecimal("45000.00"));

        JsonNode schedule = fetchRepaymentSchedule(applicationId.toString());
        for (int index = 0; index < schedule.size(); index++) {
            JsonNode installment = schedule.get(index);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("targetInstallmentId", installment.get("id").asText());
            body.put("amount", new BigDecimal(installment.get("outstandingAmount").asText()));
            body.put("postedAt", LocalDate.now().minusDays(schedule.size() - index).toString());
            body.put("channel", "BANK_TRANSFER");
            body.put("reference", "PAY-H31-CLOSE-" + String.format("%03d", index + 1));
            mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/payments", applicationId)
                            .with(systemAdmin())
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isOk());
        }

        mockMvc.perform(get("/api/v1/internal/ops/loan-applications/{applicationId}", applicationId)
                        .with(systemAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.loanAccount.status").value("CLOSED"));
    }

    private UUID seedDisbursed(String ifsc, BigDecimal requestedAmount) throws Exception {
        UUID applicationId = seedApproved(ifsc, requestedAmount);
        String borrowerId = loanApplicationRepository.findById(applicationId).orElseThrow()
                .getBorrower().getId().toString();
        mockMvc.perform(patch("/api/v1/internal/admin/borrowers/{borrowerId}/bank-details", borrowerId)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "bankAccountNumber", "123456789012",
                                "bankName", "H31 Bank",
                                "ifscCode", ifsc,
                                "accountHolderName", "H31 Borrower"
                        ))))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/disbursement-requests", applicationId)
                        .with(systemAdmin()))
                .andExpect(status().isOk());
        disbursementIntentWorkflowService.executeForApplication(applicationId);
        loanDisbursementCommandService.autoResolveAfterInitiate(applicationId, "ops.admin", null, "h31-seed");
        assertEquals(LoanApplicationStatus.DISBURSED,
                loanApplicationRepository.findById(applicationId).orElseThrow().getStatus());
        return applicationId;
    }

    private UUID seedApproved(String ifsc, BigDecimal requestedAmount) throws Exception {
        String lspId = createLspViaAdmin();
        String productId = createProductViaAdmin();
        mapProductToLsp(productId, lspId);
        String applicationId = createApplicationViaOps(lspId, productId, requestedAmount);
        transition(applicationId, "AWAITING_APPROVAL", "Ready for approval");
        markKycComplete(applicationId);
        transition(applicationId, "APPROVED_PENDING_DISBURSAL", "Approved for H31 guard test");
        seedBorrowerBankDetails(applicationId, ifsc);
        return UUID.fromString(applicationId);
    }

    private UUID seedAwaitingApproval() throws Exception {
        String lspId = createLspViaAdmin();
        String productId = createProductViaAdmin();
        mapProductToLsp(productId, lspId);
        String applicationId = createApplicationViaOps(lspId, productId, new BigDecimal("45000.00"));
        transition(applicationId, "AWAITING_APPROVAL", "Ready for approval");
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
                                "bankName", "H31 Bank",
                                "ifscCode", ifsc,
                                "accountHolderName", "H31 Borrower"
                        ))))
                .andExpect(status().isOk());
    }

    private String createLspViaAdmin() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/internal/admin/lsps")
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", "LSP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                                "name", "H31 LSP",
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
                                "name", "H31 product " + code,
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
        payload.put("borrowerFullName", "H31 Borrower");
        payload.put("borrowerMobile", mobileForPan(borrowerPan));
        payload.put("borrowerEmail", "h31+" + borrowerPan.toLowerCase() + "@example.com");
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
                            "Uploaded for H31 guard test",
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

    private JsonNode fetchRepaymentSchedule(String applicationId) throws Exception {
        MvcResult result = mockMvc.perform(get(
                                "/api/v1/internal/ops/loan-applications/{applicationId}/repayment-schedule",
                                applicationId)
                        .with(systemAdmin()))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
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
