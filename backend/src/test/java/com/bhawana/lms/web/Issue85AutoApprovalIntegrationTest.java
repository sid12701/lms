package com.bhawana.lms.web;

import com.bhawana.lms.support.TenantContextTestExecutionListener;
import com.bhawana.lms.support.TestPanSequence;
import org.springframework.test.context.TestExecutionListeners;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bhawana.lms.common.api.error.BusinessRuleViolationException;
import com.bhawana.lms.domain.BorrowerProfile;
import com.bhawana.lms.domain.LoanApplicationDocumentChecklistStatus;
import com.bhawana.lms.domain.LoanApplicationStatus;
import com.bhawana.lms.repo.BorrowerRepository;
import com.bhawana.lms.repo.LoanAccountRepository;
import com.bhawana.lms.repo.LoanApplicationAuditEventRepository;
import com.bhawana.lms.repo.LoanApplicationDocumentChecklistRepository;
import com.bhawana.lms.repo.LoanApplicationRepository;
import com.bhawana.lms.service.BorrowerActiveLoanChecker;
import com.bhawana.lms.service.LoanApplicationLifecycleService;
import com.bhawana.lms.service.LoanDisbursementWorkerService;
import com.bhawana.lms.tenant.TenantScopedExecution;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Issue #85 — auto-approval must not run inside disbursement or document-persist transactions.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestExecutionListeners(
        value = TenantContextTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS
)
class Issue85AutoApprovalIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private LoanApplicationRepository loanApplicationRepository;

    @Autowired
    private BorrowerRepository borrowerRepository;

    @Autowired
    private LoanAccountRepository loanAccountRepository;

    @Autowired
    private LoanApplicationDocumentChecklistRepository loanApplicationDocumentChecklistRepository;

    @Autowired
    private LoanApplicationAuditEventRepository loanApplicationAuditEventRepository;

    @Autowired
    private LoanDisbursementWorkerService loanDisbursementWorkerService;

    @Autowired
    private LoanApplicationLifecycleService loanApplicationLifecycleService;

    @Test
    void workerDisbursesWithoutWritingRejectedAuditEvent() throws Exception {
        String applicationId = seedApprovedPendingApplication();
        UUID applicationUuid = UUID.fromString(applicationId);

        long rejectedAuditCountBefore = countRejectedAuditEvents(applicationUuid);

        loanDisbursementWorkerService.processApplication(applicationUuid);

        assertEquals(
                LoanApplicationStatus.DISBURSED,
                loanApplicationRepository.findById(applicationUuid).orElseThrow().getStatus()
        );
        assertEquals(
                rejectedAuditCountBefore,
                countRejectedAuditEvents(applicationUuid),
                "Disbursement worker must not re-run auto-approval and persist a reject decision"
        );
    }

    @Test
    void autoApproveThrowsWhenApplicationAlreadyApprovedPendingDisbursal() throws Exception {
        String applicationId = seedApprovedPendingApplication();
        UUID applicationUuid = UUID.fromString(applicationId);

        BusinessRuleViolationException exception = assertThrows(
                BusinessRuleViolationException.class,
                () -> loanApplicationLifecycleService.autoApproveIfEligibleForLsp(applicationUuid, "lsp.api")
        );
        assertEquals("AUTO_APPROVAL_NOT_ALLOWED", exception.getErrorCode());
        assertEquals(
                LoanApplicationStatus.APPROVED_PENDING_DISBURSAL,
                loanApplicationRepository.findById(applicationUuid).orElseThrow().getStatus()
        );
    }

    @Test
    void concurrentCrossLspApprovalsForSameBorrowerCreateOnlyOneOpenLoan() throws Exception {
        String firstLspId = createLspViaAdmin("APPROVAL-CONCURRENCY-A");
        String secondLspId = createLspViaAdmin("APPROVAL-CONCURRENCY-B");
        String productId = createProductViaAdmin();
        mapProductToLsps(productId, List.of(firstLspId, secondLspId));
        String borrowerPan = uniquePan();
        String firstApplicationId = createApplicationViaOps(firstLspId, productId, borrowerPan);
        String secondApplicationId = createApplicationViaOps(secondLspId, productId, borrowerPan);
        completeBorrowerProfile(firstApplicationId, borrowerPan);
        transitionToAwaitingApproval(firstApplicationId);
        transitionToAwaitingApproval(secondApplicationId);
        markKycComplete(firstApplicationId);
        markKycComplete(secondApplicationId);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<Future<LoanApplicationStatus>> approvals = List.of(
                    executor.submit(() -> approveWhenReleased(firstApplicationId, ready, start)),
                    executor.submit(() -> approveWhenReleased(secondApplicationId, ready, start))
            );

            assertTrue(ready.await(5, TimeUnit.SECONDS), "Both approval tasks must be ready");
            start.countDown();

            List<LoanApplicationStatus> results = List.of(
                    approvals.get(0).get(15, TimeUnit.SECONDS),
                    approvals.get(1).get(15, TimeUnit.SECONDS)
            );
            assertEquals(
                    1,
                    results.stream()
                            .filter(status -> status == LoanApplicationStatus.APPROVED_PENDING_DISBURSAL)
                            .count()
            );
            assertEquals(
                    1,
                    results.stream()
                            .filter(status -> status == LoanApplicationStatus.REJECTED)
                            .count()
            );
        }

        UUID borrowerId = loanApplicationRepository.findDetailedById(UUID.fromString(firstApplicationId))
                .orElseThrow()
                .getBorrower()
                .getId();
        assertEquals(
                1,
                loanAccountRepository.findByBorrower_IdAndStatusIn(
                        borrowerId,
                        BorrowerActiveLoanChecker.openStatuses()
                ).size()
        );
    }

    private void completeBorrowerProfile(String applicationId, String borrowerPan) {
        var borrower = loanApplicationRepository.findDetailedById(UUID.fromString(applicationId))
                .orElseThrow()
                .getBorrower();
        borrower.refreshProfile(BorrowerProfile.builder()
                .fullName("Issue 85 Borrower")
                .emailAddress("issue85+" + borrowerPan.toLowerCase() + "@example.com")
                .mobileNumber(mobileForPan(borrowerPan))
                .dateOfBirth(LocalDate.of(1990, 1, 1))
                .aadharNumber("123456789012")
                .panNumber(borrowerPan)
                .addressLine1("1 Test Street")
                .addressCity("Mumbai")
                .addressState("Maharashtra")
                .addressZipcode("400001")
                .employmentStatus("SALARIED")
                .monthlyIncome(new BigDecimal("50000.00"))
                .annualIncome(new BigDecimal("600000.00"))
                .referencePersonName("Reference Person")
                .referencePersonNumber("9876500000")
                .build());
        borrowerRepository.save(borrower);
    }

    private LoanApplicationStatus approveWhenReleased(
            String applicationId,
            CountDownLatch ready,
            CountDownLatch start
    ) {
        ready.countDown();
        try {
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting to start concurrent approval");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Concurrent approval was interrupted", exception);
        }
        return TenantScopedExecution.callAsAdmin(() -> loanApplicationLifecycleService
                .autoApproveIfEligibleForLsp(UUID.fromString(applicationId), "lsp.api")
                .getStatus());
    }

    private long countRejectedAuditEvents(UUID applicationId) {
        return loanApplicationAuditEventRepository
                .findTop25ByLoanApplication_IdOrderByCreatedAtDesc(applicationId)
                .stream()
                .filter(event -> event.getToStatus() == LoanApplicationStatus.REJECTED)
                .count();
    }

    private String seedApprovedPendingApplication() throws Exception {
        String lspId = createLspViaAdmin("ISSUE85-LSP");
        String productId = createProductViaAdmin();
        mapProductToLsp(productId, lspId);
        String applicationId = createApplicationViaOps(lspId, productId);
        transitionToAwaitingApproval(applicationId);
        markKycComplete(applicationId);
        transitionToApproved(applicationId);
        seedBorrowerBankDetails(applicationId);
        return applicationId;
    }

    private void seedBorrowerBankDetails(String applicationId) throws Exception {
        String borrowerId = loanApplicationRepository.findById(UUID.fromString(applicationId)).orElseThrow()
                .getBorrower().getId().toString();
        mockMvc.perform(patch("/api/v1/internal/admin/borrowers/{borrowerId}/bank-details", borrowerId)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "bankAccountNumber", "123456789012",
                                "bankName", "Issue 85 Bank",
                                "ifscCode", "HDFC0001234",
                                "accountHolderName", "Issue 85 Borrower"
                        ))))
                .andExpect(status().isOk());
    }

    private String createLspViaAdmin(String codeSuffix) throws Exception {
        String uniqueCode = "LSP-" + codeSuffix + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        MvcResult result = mockMvc.perform(post("/api/v1/internal/admin/lsps")
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", uniqueCode,
                                "name", "Issue 85 LSP " + codeSuffix,
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
                                "name", "Issue 85 product " + code,
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
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private void mapProductToLsp(String productId, String lspId) throws Exception {
        mapProductToLsps(productId, List.of(lspId));
    }

    private void mapProductToLsps(String productId, List<String> lspIds) throws Exception {
        mockMvc.perform(put("/api/v1/internal/admin/product-lsp-mappings/{productId}", productId)
                        .with(productAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("lspIds", lspIds))))
                .andExpect(status().isOk());
    }

    private String createApplicationViaOps(String lspId, String productId) throws Exception {
        return createApplicationViaOps(lspId, productId, uniquePan());
    }

    private String createApplicationViaOps(
            String lspId,
            String productId,
            String borrowerPan
    ) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("lspId", lspId);
        payload.put("productId", productId);
        payload.put("externalLoanId", "EXT-" + UUID.randomUUID().toString().substring(0, 8));
        payload.put("sourceChannel", "API");
        payload.put("borrowerPan", borrowerPan);
        payload.put("borrowerFullName", "Issue 85 Borrower");
        payload.put("borrowerMobile", mobileForPan(borrowerPan));
        payload.put("borrowerEmail", "issue85+" + borrowerPan.toLowerCase() + "@example.com");
        payload.put("borrowerDateOfBirth", LocalDate.of(1990, 1, 1));
        payload.put("borrowerCity", "Mumbai");
        payload.put("borrowerState", "Maharashtra");
        payload.put("borrowerEmploymentType", "SALARIED");
        payload.put("borrowerMonthlyIncome", new BigDecimal("50000.00"));
        payload.put("requestedAmount", new BigDecimal("45000.00"));
        payload.put("tenureMonths", 12);

        MvcResult result = mockMvc.perform(post("/api/v1/internal/ops/loan-applications")
                        .with(opsUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private void transitionToAwaitingApproval(String applicationId) throws Exception {
        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/status-transitions", applicationId)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "targetStatus", "AWAITING_APPROVAL",
                                "note", "Ready for approval"
                        ))))
                .andExpect(status().isOk());
    }

    private void transitionToApproved(String applicationId) throws Exception {
        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/status-transitions", applicationId)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "targetStatus", "APPROVED_PENDING_DISBURSAL",
                                "note", "Approved for issue 85 test"
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
                            "Uploaded for issue 85 test",
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
        return TestPanSequence.uniquePan();
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
