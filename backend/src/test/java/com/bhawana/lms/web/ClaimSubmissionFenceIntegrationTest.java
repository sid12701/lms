package com.bhawana.lms.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bhawana.lms.domain.DisbursementDisposition;
import com.bhawana.lms.domain.DisbursementIntent;
import com.bhawana.lms.domain.DisbursementIntentState;
import com.bhawana.lms.domain.LoanAccount;
import com.bhawana.lms.domain.LoanAccountStatus;
import com.bhawana.lms.domain.LoanApplicationDocumentChecklistStatus;
import com.bhawana.lms.domain.LoanApplicationStatus;
import com.bhawana.lms.domain.LspStatus;
import com.bhawana.lms.repo.ClaimToken;
import com.bhawana.lms.repo.DisbursementIntentRepository;
import com.bhawana.lms.repo.LoanAccountRepository;
import com.bhawana.lms.repo.LoanApplicationDocumentChecklistRepository;
import com.bhawana.lms.repo.LoanApplicationRepository;
import com.bhawana.lms.repo.LoanDisbursementRequestLogRepository;
import com.bhawana.lms.repo.LspRepository;
import com.bhawana.lms.service.DisbursementIntentWorkflowService;
import com.bhawana.lms.service.LoanDisbursementAdapter;
import com.bhawana.lms.service.LoanDisbursementCommandService;
import com.bhawana.lms.support.IntegrationTestDatabaseCleaner;
import com.bhawana.lms.support.TenantContextTestExecutionListener;
import com.bhawana.lms.support.TestPanSequence;
import com.bhawana.lms.tenant.TenantScopedExecution;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
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
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Intent ownership and submission are claimed atomically.
 *
 * <p>Fast (single) and batch claims share one conditional primitive returning a
 * ClaimToken(intentId, owner, attemptCount). Preparation only grants submission permission when
 * owner + attempt + live lease still match under the shared application → account → intent locks.
 * A stale claim loses; a same-token duplicate loses on state; lease expiry on REQUESTED/UNKNOWN
 * never re-authorizes initiation — restart reconciles the same reference.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestExecutionListeners(
        value = TenantContextTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS
)
class ClaimSubmissionFenceIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private LoanApplicationRepository loanApplicationRepository;
    @Autowired private LoanAccountRepository loanAccountRepository;
    @Autowired private LoanApplicationDocumentChecklistRepository loanApplicationDocumentChecklistRepository;
    @Autowired private DisbursementIntentRepository disbursementIntentRepository;
    @Autowired private LoanDisbursementRequestLogRepository loanDisbursementRequestLogRepository;
    @Autowired private LspRepository lspRepository;
    @Autowired private DisbursementIntentWorkflowService disbursementIntentWorkflowService;
    @Autowired private LoanDisbursementCommandService loanDisbursementCommandService;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private IntegrationTestDatabaseCleaner integrationTestDatabaseCleaner;

    @MockitoSpyBean
    private LoanDisbursementAdapter loanDisbursementAdapter;

    @BeforeEach
    void setUp() {
        integrationTestDatabaseCleaner.cleanIntegrationTestData();
        reset(loanDisbursementAdapter);
    }

    @Test
    void fenceTakeover_StaleClaimLosesBeforeAndAfterWinnerPrepares() throws Exception {
        // Order 1: stale resumes BEFORE winner prepares — stale must still lose.
        UUID firstApp = seedApproved("HDFC0001234", new BigDecimal("45000.00"));
        initiate(firstApp);
        UUID firstIntent = liveCreatedIntentId(firstApp);

        ClaimToken stale = claimAs("process-A-takeover-1", firstIntent).orElseThrow();
        expireLease(firstIntent);
        ClaimToken winner = claimAs("process-B-takeover-1", firstIntent).orElseThrow();
        assertTrue(winner.attemptCount() > stale.attemptCount());

        // Stale resumes first: owner/attempt mismatch → no permission, zero provider calls.
        assertTrue(disbursementIntentWorkflowService.executeClaimedIntent(stale).isEmpty());
        verify(loanDisbursementAdapter, times(0)).requestDisbursement(any());
        assertEquals(0, loanDisbursementRequestLogRepository.countByLoanAccount_Id(
                loanAccountRepository.findByLoanApplication_Id(firstApp).orElseThrow().getId()));

        // Winner then prepares: exactly one provider call, loan moves.
        assertTrue(disbursementIntentWorkflowService.executeClaimedIntent(winner).isPresent());
        verify(loanDisbursementAdapter, times(1)).requestDisbursement(any());
        assertEquals(LoanAccountStatus.DISBURSED,
                loanAccountRepository.findByLoanApplication_Id(firstApp).orElseThrow().getStatus());

        // Order 2: stale resumes AFTER winner prepared — stale must still lose.
        reset(loanDisbursementAdapter);
        UUID secondApp = seedApproved("HDFC0001234", new BigDecimal("46000.00"));
        initiate(secondApp);
        UUID secondIntent = liveCreatedIntentId(secondApp);

        ClaimToken stale2 = claimAs("process-A-takeover-2", secondIntent).orElseThrow();
        expireLease(secondIntent);
        ClaimToken winner2 = claimAs("process-B-takeover-2", secondIntent).orElseThrow();

        assertTrue(disbursementIntentWorkflowService.executeClaimedIntent(winner2).isPresent());
        verify(loanDisbursementAdapter, times(1)).requestDisbursement(any());
        // Stale after winner: state is terminal now, must lose with no extra call.
        assertTrue(disbursementIntentWorkflowService.executeClaimedIntent(stale2).isEmpty());
        verify(loanDisbursementAdapter, times(1)).requestDisbursement(any());
    }

    @Test
    void sameToken_ConcurrentPrepare_ExactlyOneProviderCall() throws Exception {
        UUID applicationId = seedApproved("HDFC0001234", new BigDecimal("45000.00"));
        initiate(applicationId);
        UUID intentId = liveCreatedIntentId(applicationId);
        ClaimToken token = claimAs("concurrent-owner", intentId).orElseThrow();

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Callable<Optional<UUID>> task = () -> TenantScopedExecution.callAsAdmin(() -> {
                ready.countDown();
                await(start);
                return disbursementIntentWorkflowService.executeClaimedIntent(token);
            });
            Future<Optional<UUID>> first = executor.submit(task);
            Future<Optional<UUID>> second = executor.submit(task);
            assertTrue(ready.await(30, TimeUnit.SECONDS));
            start.countDown();

            Optional<UUID> firstResult = first.get(30, TimeUnit.SECONDS);
            Optional<UUID> secondResult = second.get(30, TimeUnit.SECONDS);
            // Exactly one guarded CREATED → REQUESTED transition wins.
            assertEquals(1, (firstResult.isPresent() ? 1 : 0) + (secondResult.isPresent() ? 1 : 0));
        }

        verify(loanDisbursementAdapter, times(1)).requestDisbursement(any());
        LoanAccount account = loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow();
        assertEquals(1L, loanDisbursementRequestLogRepository.countByLoanAccount_Id(account.getId()));
        assertEquals(LoanAccountStatus.DISBURSED, account.getStatus());
    }

    @Test
    void uncertainCrash_RestartReconcilesSameReferenceWithoutReinitiation() throws Exception {
        UUID applicationId = seedApproved("HDFC0001234", new BigDecimal("45000.00"));
        initiate(applicationId);
        UUID intentId = liveCreatedIntentId(applicationId);
        String tranRefNo = disbursementIntentRepository.findById(intentId).orElseThrow().getTranRefNo();

        // Simulate process death after REQUESTED commits but before the response is recorded:
        // the provider call escapes (Error, not caught as unknown) leaving REQUESTED + PENDING log.
        Mockito.doAnswer(invocation -> {
            throw new AssertionError("simulated death after provider accepted");
        }).when(loanDisbursementAdapter).requestDisbursement(any());
        assertThrows(AssertionError.class,
                () -> disbursementIntentWorkflowService.executeForApplication(applicationId));

        LoanAccount requested = loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow();
        assertEquals(LoanAccountStatus.DISBURSEMENT_REQUESTED, requested.getStatus());
        assertEquals(DisbursementIntentState.REQUESTED,
                disbursementIntentRepository.findById(intentId).orElseThrow().getState());

        // Restart + lease expiry: neither fast nor batch may initiate again.
        Mockito.reset(loanDisbursementAdapter);
        expireLease(intentId);
        assertTrue(disbursementIntentWorkflowService.executeForApplication(applicationId).isEmpty());
        assertTrue(disbursementIntentWorkflowService.executeClaimableIntents().stream()
                .noneMatch(applicationId::equals));
        verify(loanDisbursementAdapter, times(0)).requestDisbursement(any());

        // Reconciliation polls the ORIGINAL reference and completes once.
        Mockito.doAnswer(invocation -> new LoanDisbursementAdapter.DisbursementStatusResult(
                        "0", "Check Transaction Successful",
                        com.bhawana.lms.domain.DisbursementDisposition.SUCCESS,
                        com.bhawana.lms.domain.DisbursementDeclineKind.NONE,
                        "0", "RRN-CRASH-001", "recovered", "{}"))
                .when(loanDisbursementAdapter).checkStatus(any());
        assertTrue(loanDisbursementCommandService.pollPendingDisbursement(
                applicationId, "worker", null, "t03-crash"));
        assertEquals(LoanAccountStatus.DISBURSED,
                loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow().getStatus());
        assertEquals(tranRefNo, disbursementIntentRepository.findById(intentId).orElseThrow().getTranRefNo());
        verify(loanDisbursementAdapter, times(0)).requestDisbursement(any());
    }

    @Test
    void claimMatrix_FastFastFastBatchBatchBatch_ExactlyOneWinner() throws Exception {
        // fast-vs-fast: two concurrent single claims for the same id.
        UUID fastFastApp = seedApproved("HDFC0001234", new BigDecimal("45000.00"));
        initiate(fastFastApp);
        UUID fastFastIntent = liveCreatedIntentId(fastFastApp);
        List<ClaimToken> fastFastWinners = runConcurrentClaims(List.of(
                () -> claimAs("ff-A", fastFastIntent),
                () -> claimAs("ff-B", fastFastIntent)));
        assertEquals(1, fastFastWinners.size());
        assertTrue(disbursementIntentWorkflowService.executeClaimedIntent(fastFastWinners.get(0)).isPresent());

        // fast-vs-batch: single claim races batch claim for the same id.
        reset(loanDisbursementAdapter);
        UUID fastBatchApp = seedApproved("HDFC0001234", new BigDecimal("46000.00"));
        initiate(fastBatchApp);
        UUID fastBatchIntent = liveCreatedIntentId(fastBatchApp);
        List<ClaimToken> fastBatchWinners = runConcurrentClaims(List.of(
                () -> claimAs("fb-single", fastBatchIntent),
                () -> claimBatchAs("fb-batch").stream()
                        .filter(token -> token.intentId().equals(fastBatchIntent))
                        .findFirst()));
        assertEquals(1, fastBatchWinners.size());
        assertTrue(disbursementIntentWorkflowService.executeClaimedIntent(fastBatchWinners.get(0)).isPresent());

        // batch-vs-batch: two concurrent batch claims; the id appears in exactly one batch.
        reset(loanDisbursementAdapter);
        UUID batchBatchApp = seedApproved("HDFC0001234", new BigDecimal("47000.00"));
        initiate(batchBatchApp);
        UUID batchBatchIntent = liveCreatedIntentId(batchBatchApp);
        List<List<ClaimToken>> batches;
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            Callable<List<ClaimToken>> batchTask = () -> TenantScopedExecution.callAsAdmin(() -> {
                ready.countDown();
                await(start);
                return claimBatchAs("bb-" + UUID.randomUUID());
            });
            Future<List<ClaimToken>> first = executor.submit(batchTask);
            Future<List<ClaimToken>> second = executor.submit(batchTask);
            assertTrue(ready.await(30, TimeUnit.SECONDS));
            start.countDown();
            batches = List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
        }
        long appearances = batches.stream().flatMap(List::stream)
                .filter(token -> token.intentId().equals(batchBatchIntent)).count();
        assertEquals(1, appearances);
        ClaimToken batchWinner = batches.stream().flatMap(List::stream)
                .filter(token -> token.intentId().equals(batchBatchIntent)).findFirst().orElseThrow();
        assertTrue(disbursementIntentWorkflowService.executeClaimedIntent(batchWinner).isPresent());
        verify(loanDisbursementAdapter, times(1)).requestDisbursement(any());
    }

    @Test
    void failedPrepare_GrantsNoPermission_AndSameTokenRecovers() throws Exception {
        UUID applicationId = seedApproved("HDFC0001234", new BigDecimal("45000.00"));
        initiate(applicationId);
        UUID intentId = liveCreatedIntentId(applicationId);
        ClaimToken token = claimAs("recovery-owner", intentId).orElseThrow();

        // Fail preparation via eligibility (LSP disabled): no permission, zero provider calls,
        // no pre-call log, intent stays CREATED with the same fence.
        UUID lspId = loanApplicationRepository.findById(applicationId).orElseThrow().getLsp().getId();
        var lsp = lspRepository.findById(lspId).orElseThrow();
        lsp.updateStatus(LspStatus.INACTIVE);
        lspRepository.save(lsp);

        LoanAccount account = loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow();
        long logsBefore = loanDisbursementRequestLogRepository.countByLoanAccount_Id(account.getId());
        assertTrue(disbursementIntentWorkflowService.executeClaimedIntent(token).isEmpty());
        verify(loanDisbursementAdapter, times(0)).requestDisbursement(any());
        assertEquals(logsBefore, loanDisbursementRequestLogRepository.countByLoanAccount_Id(account.getId()));
        DisbursementIntent stillCreated = disbursementIntentRepository.findById(intentId).orElseThrow();
        assertEquals(DisbursementIntentState.CREATED, stillCreated.getState());
        assertEquals(token.attemptCount(), stillCreated.getAttemptCount());

        // Restore eligibility: the same token recovers safely with exactly one provider call.
        var reactivated = lspRepository.findById(lspId).orElseThrow();
        reactivated.updateStatus(LspStatus.ACTIVE);
        lspRepository.save(reactivated);
        assertTrue(disbursementIntentWorkflowService.executeClaimedIntent(token).isPresent());
        verify(loanDisbursementAdapter, times(1)).requestDisbursement(any());
        assertEquals(LoanAccountStatus.DISBURSED,
                loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow().getStatus());
    }

    // ---- helpers ----

    private Optional<ClaimToken> claimAs(String owner, UUID intentId) {
        Instant now = Instant.now();
        return TenantScopedExecution.callAsAdmin(() -> transactionTemplate.execute(
                tx -> disbursementIntentRepository.claimSingle(
                        intentId, now, now.plusSeconds(120), owner)));
    }

    private List<ClaimToken> claimBatchAs(String owner) {
        Instant now = Instant.now();
        List<ClaimToken> claimed = TenantScopedExecution.callAsAdmin(() -> transactionTemplate.execute(
                tx -> disbursementIntentRepository.claimBatch(now, 10, now.plusSeconds(120), owner)));
        return claimed == null ? List.of() : claimed;
    }

    private List<ClaimToken> runConcurrentClaims(List<Callable<Optional<ClaimToken>>> claimants) throws Exception {
        try (ExecutorService executor = Executors.newFixedThreadPool(claimants.size())) {
            CountDownLatch ready = new CountDownLatch(claimants.size());
            CountDownLatch start = new CountDownLatch(1);
            List<Callable<Optional<ClaimToken>>> tasks = new ArrayList<>();
            for (Callable<Optional<ClaimToken>> claimant : claimants) {
                tasks.add(() -> TenantScopedExecution.callAsAdmin(() -> {
                    ready.countDown();
                    await(start);
                    try {
                        return claimant.call();
                    } catch (Exception exception) {
                        throw new IllegalStateException(exception);
                    }
                }));
            }
            List<Future<Optional<ClaimToken>>> futures = new ArrayList<>();
            for (Callable<Optional<ClaimToken>> task : tasks) {
                futures.add(executor.submit(task));
            }
            assertTrue(ready.await(30, TimeUnit.SECONDS));
            start.countDown();
            List<ClaimToken> winners = new ArrayList<>();
            for (Future<Optional<ClaimToken>> future : futures) {
                future.get(30, TimeUnit.SECONDS).ifPresent(winners::add);
            }
            return winners;
        }
    }

    private void expireLease(UUID intentId) {
        jdbcTemplate.update(
                "UPDATE disbursement_intent SET lease_expires_at = now() - interval '1 second' WHERE id = ?::uuid",
                intentId.toString());
    }

    private void initiate(UUID applicationId) throws Exception {
        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/disbursement-requests", applicationId)
                        .with(systemAdmin()))
                .andExpect(status().isOk());
    }

    private UUID liveCreatedIntentId(UUID applicationId) {
        LoanAccount account = loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow();
        DisbursementIntent intent = disbursementIntentRepository.findLiveByLoanAccountId(account.getId())
                .filter(candidate -> candidate.getState() == DisbursementIntentState.CREATED)
                .orElseThrow();
        return intent.getId();
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(30, TimeUnit.SECONDS));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private UUID seedApproved(String ifsc, BigDecimal requestedAmount) throws Exception {
        String lspId = createLspViaAdmin();
        String productId = createProductViaAdmin();
        mapProductToLsp(productId, lspId);
        String applicationId = createApplicationViaOps(lspId, productId, requestedAmount);
        transition(applicationId, "AWAITING_APPROVAL", "Ready for approval");
        markKycComplete(applicationId);
        transition(applicationId, "APPROVED_PENDING_DISBURSAL", "Approved for fence test");
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
        payload.put("borrowerEmail", "t03+" + borrowerPan.toLowerCase() + "@example.com");
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
                            "Uploaded for fence test",
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
