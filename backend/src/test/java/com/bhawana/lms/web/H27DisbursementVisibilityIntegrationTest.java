package com.bhawana.lms.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bhawana.lms.domain.DisbursementDeclineKind;
import com.bhawana.lms.domain.DisbursementIntent;
import com.bhawana.lms.domain.DisbursementIntentState;
import com.bhawana.lms.domain.LoanAccount;
import com.bhawana.lms.domain.LoanAccountStatus;
import com.bhawana.lms.domain.LoanApplicationDocumentChecklistStatus;
import com.bhawana.lms.repo.DisbursementIntentRepository;
import com.bhawana.lms.repo.LoanAccountRepository;
import com.bhawana.lms.repo.LoanApplicationDocumentChecklistRepository;
import com.bhawana.lms.repo.LoanApplicationRepository;
import com.bhawana.lms.service.DisbursementIntentWorkflowService;
import com.bhawana.lms.service.LoanDisbursementCommandService;
import com.bhawana.lms.support.IntegrationTestDatabaseCleaner;
import com.bhawana.lms.support.TenantContextTestExecutionListener;
import com.bhawana.lms.support.TestPanSequence;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
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

/**
 * H27 (disbursement slice) — the visibility gauges count the authoritative buckets through one
 * bounded aggregate each, age from the stable creation timestamps, include parked and legacy
 * rows with no live intent, and ignore applied terminal work.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestExecutionListeners(
        value = TenantContextTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS
)
class H27DisbursementVisibilityIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private MeterRegistry meterRegistry;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private LoanApplicationRepository loanApplicationRepository;
    @Autowired private LoanAccountRepository loanAccountRepository;
    @Autowired private LoanApplicationDocumentChecklistRepository loanApplicationDocumentChecklistRepository;
    @Autowired private DisbursementIntentRepository disbursementIntentRepository;
    @Autowired private DisbursementIntentWorkflowService disbursementIntentWorkflowService;
    @Autowired private LoanDisbursementCommandService loanDisbursementCommandService;
    @Autowired private IntegrationTestDatabaseCleaner integrationTestDatabaseCleaner;

    @BeforeEach
    void cleanDatabase() {
        integrationTestDatabaseCleaner.cleanIntegrationTestData();
    }

    @Test
    void bucketsCountAuthoritativeStatesWithCreationBasedAge() throws Exception {
        // Pending: CREATED intent (A) plus a live REQUESTED intent (F).
        UUID pendingCreated = seedApproved("HDFC0001234", new BigDecimal("45000.00"));
        initiate(pendingCreated);
        backdateIntentCreatedAt(pendingCreated, Instant.now().minusSeconds(2700));
        UUID pendingRequested = seedApproved("MOCK0PENDOK", new BigDecimal("45000.00"));
        initiate(pendingRequested);
        disbursementIntentWorkflowService.executeForApplication(pendingRequested);

        // Unknown: executed, then parked in uncertainty under its original reference (B).
        UUID unknown = seedApproved("MOCK0PENDOK", new BigDecimal("45000.00"));
        initiate(unknown);
        disbursementIntentWorkflowService.executeForApplication(unknown);
        markIntent(unknown, DisbursementIntentState.UNKNOWN, "11", null);

        // Unapplied: terminal evidence recorded while the loan move is still missing (C).
        UUID unapplied = seedApproved("MOCK0PENDOK", new BigDecimal("45000.00"));
        initiate(unapplied);
        disbursementIntentWorkflowService.executeForApplication(unapplied);
        markIntent(unapplied, DisbursementIntentState.SUCCEEDED, "0", "RRN-H27-UNAPPLIED");

        // Parked with no intent at all: manual-reconciliation parking, never issued (D).
        UUID parkedNoIntent = seedApproved("HDFC0001234", new BigDecimal("45000.00"));
        LoanAccount parkedAccount = accountOf(parkedNoIntent);
        parkedAccount.updateDisbursementStatus(
                LoanAccountStatus.DISBURSEMENT_PENDING_RECONCILIATION, Instant.now());
        loanAccountRepository.save(parkedAccount);
        backdateAccountCreatedAt(parkedNoIntent, Instant.now().minusSeconds(5400));

        // Ignored terminal: accepted success fully applied (E) — in no bucket.
        UUID applied = seedApproved("HDFC0001234", new BigDecimal("45000.00"));
        initiate(applied);
        disbursementIntentWorkflowService.executeForApplication(applied);
        loanDisbursementCommandService.autoResolveAfterInitiate(applied, "ops.admin", null, "h27-seed");

        assertEquals(2.0, gauge("lms.disbursement.intent.pending.count"));
        assertEquals(1.0, gauge("lms.disbursement.intent.unknown.count"));
        assertEquals(1.0, gauge("lms.disbursement.intent.unapplied.count"));
        // C (terminal evidence, loan still REQUESTED) also has no live intent, so it appears in
        // the parked bucket alongside D; the runbook treats unapplied as the repair backlog and
        // parked-without-intent as the reconciliation queue.
        assertEquals(2.0, gauge("lms.disbursement.account.parked_without_intent.count"));

        // Ages come from created_at: the backdated rows dominate, fresh rows cannot reset them.
        assertTrue(gauge("lms.disbursement.intent.pending.oldest_age_seconds") >= 2700);
        assertTrue(gauge("lms.disbursement.account.parked_without_intent.oldest_age_seconds") >= 5400);
        assertTrue(gauge("lms.disbursement.intent.unknown.oldest_age_seconds") < 600);
        assertTrue(gauge("lms.disbursement.intent.unapplied.oldest_age_seconds") < 600);
    }

    private double gauge(String name) {
        return meterRegistry.get(name).gauge().value();
    }

    private void initiate(UUID applicationId) throws Exception {
        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/disbursement-requests", applicationId)
                        .with(systemAdmin()))
                .andExpect(status().isOk());
    }

    private LoanAccount accountOf(UUID applicationId) {
        return loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow();
    }

    private void markIntent(
            UUID applicationId,
            DisbursementIntentState state,
            String actCode,
            String bankRrn
    ) {
        DisbursementIntent intent =
                disbursementIntentRepository.findLiveByLoanAccountId(accountOf(applicationId).getId()).orElseThrow();
        intent.recordProviderResponse(state, intent.getTranRefNo(), actCode, bankRrn, DisbursementDeclineKind.NONE);
        disbursementIntentRepository.save(intent);
    }

    private void backdateIntentCreatedAt(UUID applicationId, Instant createdAt) {
        DisbursementIntent intent =
                disbursementIntentRepository.findLiveByLoanAccountId(accountOf(applicationId).getId()).orElseThrow();
        jdbcTemplate.update(
                "UPDATE disbursement_intent SET created_at = ? WHERE id = ?",
                Timestamp.from(createdAt), intent.getId());
    }

    private void backdateAccountCreatedAt(UUID applicationId, Instant createdAt) {
        jdbcTemplate.update(
                "UPDATE loan_account SET created_at = ? WHERE loan_application_id = ?",
                Timestamp.from(createdAt), applicationId);
    }

    private UUID seedApproved(String ifsc, BigDecimal requestedAmount) throws Exception {
        String lspId = createLspViaAdmin();
        String productId = createProductViaAdmin();
        mapProductToLsp(productId, lspId);
        String applicationId = createApplicationViaOps(lspId, productId, requestedAmount);
        transition(applicationId, "AWAITING_APPROVAL", "Ready for approval");
        markKycComplete(applicationId);
        transition(applicationId, "APPROVED_PENDING_DISBURSAL", "Approved for H27 visibility test");
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
                                "bankName", "H27 Bank",
                                "ifscCode", ifsc,
                                "accountHolderName", "H27 Borrower"
                        ))))
                .andExpect(status().isOk());
    }

    private String createLspViaAdmin() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/internal/admin/lsps")
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", "LSP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                                "name", "H27 LSP",
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
                                "name", "H27 product " + code,
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
        payload.put("borrowerFullName", "H27 Borrower");
        payload.put("borrowerMobile", mobileForPan(borrowerPan));
        payload.put("borrowerEmail", "h27+" + borrowerPan.toLowerCase() + "@example.com");
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
                            "Uploaded for H27 visibility test",
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
