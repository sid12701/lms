package com.bhawana.lms.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bhawana.lms.domain.DisbursementReconciliationQueueEntry;
import com.bhawana.lms.domain.DisbursementReconciliationReason;
import com.bhawana.lms.domain.LoanAccount;
import com.bhawana.lms.domain.LoanAccountStatus;
import com.bhawana.lms.domain.LoanApplicationDocumentChecklistStatus;
import com.bhawana.lms.repo.DisbursementIntentRepository;
import com.bhawana.lms.repo.DisbursementReconciliationQueueRepository;
import com.bhawana.lms.repo.LoanAccountRepository;
import com.bhawana.lms.repo.LoanApplicationDocumentChecklistRepository;
import com.bhawana.lms.repo.LoanApplicationRepository;
import com.bhawana.lms.service.DisbursementIntentWorkflowService;
import com.bhawana.lms.service.DisbursementObservationWriter;
import com.bhawana.lms.service.DisbursementReconciliationService;
import com.bhawana.lms.service.LoanDisbursementCommandService;
import com.bhawana.lms.support.IntegrationTestDatabaseCleaner;
import com.bhawana.lms.support.TenantContextTestExecutionListener;
import com.bhawana.lms.support.TestPanSequence;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Queue-age regression: a submitted account with old original evidence and no
 * queue row that polls pending must seed {@code firstSeenAt} from the original durable
 * evidence, not from discovery time — and repeats must never reset it. Covers both the
 * modern shape (live intent present) and the true legacy shape (valid stored request,
 * no intent row). Held {@code CONFLICTING_EVIDENCE} rows stay untouched by later pending
 * refreshes. Also pins true offset pagination for non-multiple offsets.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestExecutionListeners(
        value = TenantContextTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS
)
class ReconciliationQueueFirstSeenRegressionTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private LoanApplicationRepository loanApplicationRepository;
    @Autowired private LoanAccountRepository loanAccountRepository;
    @Autowired private LoanApplicationDocumentChecklistRepository loanApplicationDocumentChecklistRepository;
    @Autowired private DisbursementIntentRepository disbursementIntentRepository;
    @Autowired private DisbursementReconciliationQueueRepository queueRepository;
    @Autowired private DisbursementIntentWorkflowService disbursementIntentWorkflowService;
    @Autowired private LoanDisbursementCommandService loanDisbursementCommandService;
    @Autowired private DisbursementReconciliationService reconciliationService;
    @Autowired private DisbursementObservationWriter observationWriter;
    @Autowired private org.springframework.transaction.support.TransactionTemplate transactionTemplate;
    @Autowired private IntegrationTestDatabaseCleaner databaseCleaner;

    @BeforeEach
    void setUp() {
        databaseCleaner.cleanIntegrationTestData();
    }

    @AfterEach
    void tearDown() {
        databaseCleaner.cleanIntegrationTestData();
    }

    @Test
    void unqueuedSubmittedAccountSeedsFirstSeenFromOriginalEvidenceAndRepeatsNeverReset() throws Exception {
        // Valid submitted shape on a never-resolving rail: every poll stays pending.
        UUID applicationId = seedApproved("MOCK0STUCK0", new BigDecimal("45000.00"));
        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/disbursement-requests",
                        applicationId)
                        .with(systemAdmin()))
                .andExpect(status().isOk());
        disbursementIntentWorkflowService.executeForApplication(applicationId);
        LoanAccount account = loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow();
        assertEquals(LoanAccountStatus.DISBURSEMENT_REQUESTED, account.getStatus());
        // Model the never-queued shape: drop the fresh queue row from initiation so the
        // next pending poll must seed age from the original evidence, not discovery time.
        jdbcTemplate.update(
                "DELETE FROM disbursement_reconciliation_queue WHERE loan_account_id = ?",
                account.getId());
        assertTrue(queueRepository.findById(account.getId()).isEmpty());

        // Backdate the original durable evidence: queue age must follow the money, not discovery.
        Instant originalEvidence = Instant.parse("2023-11-05T10:00:00Z");
        jdbcTemplate.update(
                "UPDATE loan_disbursement_request_log SET created_at = ?, updated_at = ? WHERE loan_account_id = ?",
                java.sql.Timestamp.from(originalEvidence), java.sql.Timestamp.from(originalEvidence),
                account.getId());
        jdbcTemplate.update(
                "UPDATE disbursement_intent SET created_at = ?, updated_at = ? WHERE loan_account_id = ?",
                java.sql.Timestamp.from(originalEvidence), java.sql.Timestamp.from(originalEvidence),
                account.getId());

        // First pending poll creates the queue row seeded from the old evidence.
        assertFalse(loanDisbursementCommandService.pollPendingDisbursement(
                applicationId, "worker", null, "t02-age-1"));
        DisbursementReconciliationQueueEntry created =
                queueRepository.findById(account.getId()).orElseThrow();
        assertEquals(DisbursementReconciliationReason.REQUESTED, created.getReason());
        assertEquals(originalEvidence, created.getFirstSeenAt());

        // Repeat pending poll refreshes evidence but never resets the original age
        // (test profile parks on the second poll — either way the age must stand).
        loanDisbursementCommandService.pollPendingDisbursement(
                applicationId, "worker", null, "t02-age-2");
        DisbursementReconciliationQueueEntry repeated =
                queueRepository.findById(account.getId()).orElseThrow();
        assertEquals(originalEvidence, repeated.getFirstSeenAt());

        // A held conflict keeps its reason, reference and age under later pending refreshes.
        UUID accountId = account.getId();
        String ref = disbursementIntentRepository.findLiveByLoanAccountId(accountId)
                .orElseThrow().getTranRefNo();
        transactionTemplate.executeWithoutResult(tx -> observationWriter.enqueue(
                loanAccountRepository.findById(accountId).orElseThrow(), null, ref,
                DisbursementReconciliationReason.CONFLICTING_EVIDENCE,
                "Age probe: held conflict."));
        DisbursementReconciliationQueueEntry held =
                queueRepository.findById(accountId).orElseThrow();
        assertEquals(DisbursementReconciliationReason.CONFLICTING_EVIDENCE, held.getReason());
        transactionTemplate.executeWithoutResult(tx -> observationWriter.enqueue(
                loanAccountRepository.findById(accountId).orElseThrow(), null, ref,
                DisbursementReconciliationReason.REQUESTED,
                "Age probe: pending refresh must not overwrite the hold."));
        DisbursementReconciliationQueueEntry afterHold =
                queueRepository.findById(accountId).orElseThrow();
        assertEquals(DisbursementReconciliationReason.CONFLICTING_EVIDENCE, afterHold.getReason());
        assertEquals(ref, afterHold.getTranRefNo());
        assertEquals(originalEvidence, afterHold.getFirstSeenAt());
    }

    @Test
    void legacyRequestWithoutIntentSeedsFirstSeenFromOriginalEvidence() throws Exception {
        // True legacy shape: a valid stored request with old evidence and no intent row.
        UUID applicationId = seedApproved("MOCK0STUCK0", new BigDecimal("45000.00"));
        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/disbursement-requests",
                        applicationId)
                        .with(systemAdmin()))
                .andExpect(status().isOk());
        disbursementIntentWorkflowService.executeForApplication(applicationId);
        LoanAccount account = loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow();
        String originalRef = disbursementIntentRepository.findLiveByLoanAccountId(account.getId())
                .orElseThrow().getTranRefNo();
        // Shape the legacy pre-intent row (test databases only): truncate the append-only
        // Trail first — the trigger rejects row deletes — then drop the intent. The
        // stored request keeps its valid instruction, so polling stays trustworthy.
        jdbcTemplate.execute("TRUNCATE TABLE disbursement_reconciliation_queue");
        jdbcTemplate.execute("TRUNCATE TABLE disbursement_observation");
        disbursementIntentRepository.delete(disbursementIntentRepository
                .findLiveByLoanAccountId(account.getId()).orElseThrow());
        assertTrue(disbursementIntentRepository.findLiveByLoanAccountId(account.getId()).isEmpty());

        Instant originalEvidence = Instant.parse("2023-11-05T10:00:00Z");
        jdbcTemplate.update(
                "UPDATE loan_disbursement_request_log SET created_at = ?, updated_at = ? WHERE loan_account_id = ?",
                java.sql.Timestamp.from(originalEvidence), java.sql.Timestamp.from(originalEvidence),
                account.getId());

        // Discovery poll stays pending on the original reference and ages from the
        // original request — and a repeat never resets it.
        assertFalse(loanDisbursementCommandService.pollPendingDisbursement(
                applicationId, "worker", null, "t02-legacy-age-1"));
        DisbursementReconciliationQueueEntry created =
                queueRepository.findById(account.getId()).orElseThrow();
        assertEquals(DisbursementReconciliationReason.REQUESTED, created.getReason());
        assertEquals(originalRef, created.getTranRefNo());
        assertEquals(originalEvidence, created.getFirstSeenAt());

        loanDisbursementCommandService.pollPendingDisbursement(
                applicationId, "worker", null, "t02-legacy-age-2");
        assertEquals(originalEvidence,
                queueRepository.findById(account.getId()).orElseThrow().getFirstSeenAt());
    }

    @Test
    void queuePageHonoursNonMultipleOffsets() throws Exception {
        // Three queued accounts with distinct ages, oldest first: t0 < t1 < t2.
        UUID[] accountIds = new UUID[3];
        Instant[] ages = new Instant[] {
                Instant.parse("2023-11-05T10:00:00Z"),
                Instant.parse("2023-11-05T11:00:00Z"),
                Instant.parse("2023-11-05T12:00:00Z") };
        for (int i = 0; i < 3; i++) {
            UUID applicationId = seedApproved("MOCK0STUCK0", new BigDecimal("45000.00"));
            mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/disbursement-requests",
                            applicationId)
                            .with(systemAdmin()))
                    .andExpect(status().isOk());
            disbursementIntentWorkflowService.executeForApplication(applicationId);
            LoanAccount account = loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow();
            assertFalse(loanDisbursementCommandService.pollPendingDisbursement(
                    applicationId, "worker", null, "t02-page-" + i));
            accountIds[i] = account.getId();
            jdbcTemplate.update(
                    "UPDATE disbursement_reconciliation_queue SET first_seen_at = ? WHERE loan_account_id = ?",
                    java.sql.Timestamp.from(ages[i]), accountIds[i]);
        }

        // limit=2, offset=1 is not page-aligned: it must return rows 1..2, not 0..1.
        List<UUID> first = reconciliationService.queuePage(2, 0).stream()
                .map(DisbursementReconciliationService.QueueEntryView::loanAccountId).toList();
        assertEquals(List.of(accountIds[0], accountIds[1]), first);
        List<UUID> second = reconciliationService.queuePage(2, 1).stream()
                .map(DisbursementReconciliationService.QueueEntryView::loanAccountId).toList();
        assertEquals(List.of(accountIds[1], accountIds[2]), second);
        List<UUID> tail = reconciliationService.queuePage(2, 2).stream()
                .map(DisbursementReconciliationService.QueueEntryView::loanAccountId).toList();
        assertEquals(List.of(accountIds[2]), tail);
        assertTrue(reconciliationService.queuePage(2, 3).isEmpty());
    }

    // --- helpers (mirrors DisbursementReconciliationIntegrationTest seeding) ---

    private UUID seedApproved(String ifsc, BigDecimal requestedAmount) throws Exception {
        String lspId = createLspViaAdmin();
        String productId = createProductViaAdmin();
        mapProductToLsp(productId, lspId);
        String applicationId = createApplicationViaOps(lspId, productId, requestedAmount);
        transition(applicationId, "AWAITING_APPROVAL", "Ready for approval");
        markKycComplete(applicationId);
        transition(applicationId, "APPROVED_PENDING_DISBURSAL", "Approved for age test");
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
                                "accountHolderName", "Test Borrower"
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
        payload.put("borrowerFullName", "Test Borrower");
        payload.put("borrowerMobile", mobileForPan(borrowerPan));
        payload.put("borrowerEmail", "t02age+" + borrowerPan.toLowerCase() + "@example.com");
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
                            "Uploaded for age test",
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
