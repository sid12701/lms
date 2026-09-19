package com.bhawana.lms.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import com.bhawana.lms.repo.LoanApplicationStatusTransitionRepository;
import com.bhawana.lms.repo.LoanDisbursementRequestLogRepository;
import com.bhawana.lms.service.DisbursementIntentWorkflowService;
import com.bhawana.lms.service.LoanApplicationLifecycleService;
import com.bhawana.lms.service.LoanDisbursementAdapter;
import com.bhawana.lms.service.LoanDisbursementCommandService;
import com.bhawana.lms.service.LoanDisbursementWorkerService;
import com.bhawana.lms.support.TenantContextTestExecutionListener;
import com.bhawana.lms.support.TestPanSequence;
import com.bhawana.lms.tenant.TenantScopedExecution;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Generic in-flight guard — the narrow money-safety correction for the generic
 * status endpoints.
 *
 * <p>Root cause: {@code LoanApplicationLifecycleService.transitionStatus} with a generic
 * {@code INVALID} target called the status writer directly instead of the invalidation
 * guard, and {@code manuallyOverrideStatus} could mutate a parked
 * {@code DISBURSEMENT_RETRY} loan to {@code REJECTED}, hiding it from terminal-outcome recovery.
 * The existing {@code FINANCIAL_STATUS_REQUIRES_EVIDENCE} guard protects only
 * {@code DISBURSED}/{@code CLOSED} direct writes. This test proves both generic endpoints
 * now share the invalidation cancellation boundary: borrower → application → account → live
 * intent, rejecting with stable {@code DISBURSEMENT_IN_PROGRESS} while an account is
 * {@code REQUESTED}/{@code PENDING_RECONCILIATION} or a live intent exists.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestExecutionListeners(
        value = TenantContextTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS
)
class GenericInFlightGuardIntegrationTest {

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
    @Autowired private LoanApplicationLifecycleService loanApplicationLifecycleService;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private JdbcTemplate jdbcTemplate;

    @MockitoSpyBean
    private LoanDisbursementAdapter loanDisbursementAdapter;

    @BeforeEach
    void resetMocks() {
        reset(loanDisbursementAdapter);
    }

    @Test
    void genericInvalidIsRejectedWhenIntentQueued() throws Exception {
        UUID applicationId = seedApproved("HDFC0001234", new BigDecimal("45000.00"));
        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/disbursement-requests", applicationId)
                        .with(systemAdmin()))
                .andExpect(status().isOk());

        LoanAccount account = loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow();
        assertEquals(LoanAccountStatus.DISBURSEMENT_REQUESTED, account.getStatus());
        assertEquals(DisbursementIntentState.CREATED,
                disbursementIntentRepository.findLiveByLoanAccountId(account.getId()).orElseThrow().getState());
        long invalidBefore = countInvalidTransitions(applicationId);

        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/status-transitions", applicationId)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "targetStatus", "INVALID",
                                "note", "Generic invalid while queued"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("DISBURSEMENT_IN_PROGRESS"));

        assertEquals(LoanApplicationStatus.APPROVED_PENDING_DISBURSAL,
                loanApplicationRepository.findById(applicationId).orElseThrow().getStatus());
        assertEquals(LoanAccountStatus.DISBURSEMENT_REQUESTED,
                loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow().getStatus());
        assertEquals(DisbursementIntentState.CREATED,
                disbursementIntentRepository.findLiveByLoanAccountId(account.getId()).orElseThrow().getState());
        assertEquals(invalidBefore, countInvalidTransitions(applicationId));
    }

    @Test
    void genericInvalidIsRejectedWhenIntentRequested() throws Exception {
        UUID applicationId = seedApproved("MOCK0PENDOK", new BigDecimal("45000.00"));
        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/disbursement-requests", applicationId)
                        .with(systemAdmin()))
                .andExpect(status().isOk());
        disbursementIntentWorkflowService.executeForApplication(applicationId);

        LoanAccount account = loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow();
        assertEquals(LoanAccountStatus.DISBURSEMENT_REQUESTED, account.getStatus());
        assertEquals(DisbursementIntentState.REQUESTED,
                disbursementIntentRepository.findLiveByLoanAccountId(account.getId()).orElseThrow().getState());

        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/status-transitions", applicationId)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "targetStatus", "INVALID",
                                "note", "Generic invalid while requested"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("DISBURSEMENT_IN_PROGRESS"));

        assertEquals(LoanApplicationStatus.APPROVED_PENDING_DISBURSAL,
                loanApplicationRepository.findById(applicationId).orElseThrow().getStatus());
    }

    @Test
    void genericInvalidIsRejectedWhenIntentUnknown() throws Exception {
        UUID applicationId = seedApproved("HDFC0001234", new BigDecimal("45000.00"));
        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/disbursement-requests", applicationId)
                        .with(systemAdmin()))
                .andExpect(status().isOk());
        doAnswer(invocation -> {
            throw new IllegalStateException("provider response lost");
        }).when(loanDisbursementAdapter).requestDisbursement(any());
        disbursementIntentWorkflowService.executeForApplication(applicationId);

        LoanAccount account = loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow();
        assertEquals(DisbursementIntentState.UNKNOWN,
                disbursementIntentRepository.findLiveByLoanAccountId(account.getId()).orElseThrow().getState());

        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/status-transitions", applicationId)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "targetStatus", "INVALID",
                                "note", "Generic invalid while unknown"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("DISBURSEMENT_IN_PROGRESS"));

        assertTrue(disbursementIntentWorkflowService.loadStatusPollContext(applicationId).isPresent());
        assertEquals(DisbursementIntentState.UNKNOWN,
                disbursementIntentRepository.findLiveByLoanAccountId(account.getId()).orElseThrow().getState());
    }

    @Test
    void manualRejectedIsRejectedWhenParked() throws Exception {
        UUID applicationId = seedApproved("MOCK0STUCK0", new BigDecimal("45000.00"));
        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/disbursement-requests", applicationId)
                        .with(systemAdmin()))
                .andExpect(status().isOk());
        disbursementIntentWorkflowService.executeForApplication(applicationId);
        makeAllQueueRowsDue();
        loanDisbursementWorkerService.processReconciliationQueue();
        makeAllQueueRowsDue();
        loanDisbursementWorkerService.processReconciliationQueue();

        LoanAccount parked = loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow();
        assertEquals(LoanAccountStatus.DISBURSEMENT_PENDING_RECONCILIATION, parked.getStatus());
        assertEquals(LoanApplicationStatus.DISBURSEMENT_RETRY,
                loanApplicationRepository.findById(applicationId).orElseThrow().getStatus());

        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/manual-status", applicationId)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "targetStatus", "REJECTED",
                                "note", "Manual reject while parked",
                                "reasonCode", "MANUAL_ADMIN_OVERRIDE"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("DISBURSEMENT_IN_PROGRESS"));

        assertEquals(LoanApplicationStatus.DISBURSEMENT_RETRY,
                loanApplicationRepository.findById(applicationId).orElseThrow().getStatus());
        assertEquals(LoanAccountStatus.DISBURSEMENT_PENDING_RECONCILIATION,
                loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow().getStatus());
    }

    @Test
    void intentCommittedFirst_genericInvalidLoses() throws Exception {
        UUID raceId = seedApproved("HDFC0001234", new BigDecimal("45000.00"));
        // Park the generic mutation before it opens its transaction; the intent commits
        // first on a real independent transaction, then the generic transaction's
        // locked re-read (borrower → application → account → live intent) observes it.
        CountDownLatch releaseGeneric = new CountDownLatch(1);
        CompletableFuture<Object> genericResult = CompletableFuture.supplyAsync(() ->
                TenantScopedExecution.callAsAdmin(() -> {
                    try {
                        assertTrue(releaseGeneric.await(30, TimeUnit.SECONDS), "race barrier timed out");
                        loanApplicationLifecycleService.transitionStatus(
                                raceId, "ops.admin", LoanApplicationStatus.INVALID,
                                "Raced generic invalid", null);
                        return "invalid-applied";
                    } catch (ApiConflictException conflict) {
                        return conflict;
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return interrupted;
                    }
                }));

        loanDisbursementCommandService.initiateDisbursement(raceId, "race.intent");
        releaseGeneric.countDown();

        Object outcome = genericResult.get(60, TimeUnit.SECONDS);
        assertTrue(outcome instanceof ApiConflictException, "expected DISBURSEMENT_IN_PROGRESS, got " + outcome);
        assertEquals("DISBURSEMENT_IN_PROGRESS", ((ApiConflictException) outcome).getErrorCode());

        LoanAccount account = loanAccountRepository.findByLoanApplication_Id(raceId).orElseThrow();
        assertEquals(LoanAccountStatus.DISBURSEMENT_REQUESTED, account.getStatus());
        assertEquals(1, intentsForAccount(account.getId()));
        assertEquals(LoanApplicationStatus.APPROVED_PENDING_DISBURSAL,
                loanApplicationRepository.findById(raceId).orElseThrow().getStatus());
        assertEquals(0, countInvalidTransitions(raceId));
    }

    @Test
    void genericInvalidCommittedFirst_intentCreationLosesWithZeroSubmission() throws Exception {
        UUID raceId = seedApproved("HDFC0001234", new BigDecimal("45000.00"));
        // Reverse order: park the intent creation before its transaction; the generic
        // INVALID commits first, then the intent transaction's locked re-read observes
        // the INVALID loan and refuses to mint a new reference.
        CountDownLatch releaseIntent = new CountDownLatch(1);
        CompletableFuture<Object> intentResult = CompletableFuture.supplyAsync(() ->
                TenantScopedExecution.callAsAdmin(() -> {
                    try {
                        assertTrue(releaseIntent.await(30, TimeUnit.SECONDS), "race barrier timed out");
                        loanDisbursementCommandService.initiateDisbursement(raceId, "race.intent");
                        return "intent-created";
                    } catch (RuntimeException conflict) {
                        return conflict;
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return interrupted;
                    }
                }));

        loanApplicationLifecycleService.transitionStatus(
                raceId, "ops.admin", LoanApplicationStatus.INVALID,
                "Raced generic invalid wins", null);
        releaseIntent.countDown();

        Object outcome = intentResult.get(60, TimeUnit.SECONDS);
        assertTrue(outcome instanceof RuntimeException, "expected intent rejection, got " + outcome);
        assertEquals(LoanApplicationStatus.INVALID,
                loanApplicationRepository.findById(raceId).orElseThrow().getStatus());
        LoanAccount account = loanAccountRepository.findByLoanApplication_Id(raceId).orElseThrow();
        assertEquals(0, intentsForAccount(account.getId()));
        assertEquals(0, loanDisbursementRequestLogRepository.countByLoanAccount_Id(account.getId()));
    }

    @Test
    void genericInvalidHoldsApplicationLock_initiateBlocksThenLosesWithZeroReference() throws Exception {
        UUID raceId = seedApproved("HDFC0001234", new BigDecimal("45000.00"));
        // True lock contention (not a pre-transaction barrier): the winner runs the generic
        // INVALID inside an outer transaction and holds it open after the mutation, so the
        // borrower → application → account row locks stay held. The service joins the outer
        // transaction (REQUIRED), so nothing is visible yet. The loser starts its initiation
        // before the winner commits, reaches the contested application lock, and must block
        // in a PostgreSQL lock wait. Releasing the winner lets the loser re-read the
        // committed INVALID loan and refuse to mint a reference.
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch winnerMutated = new CountDownLatch(1);
        CountDownLatch releaseWinner = new CountDownLatch(1);
        try {
            Future<?> winner = executor.submit(() -> TenantScopedExecution.callAsAdmin(() ->
                    transactionTemplate.execute(tx -> {
                        loanApplicationLifecycleService.transitionStatus(
                                raceId, "ops.admin", LoanApplicationStatus.INVALID,
                                "Contended generic invalid wins", null);
                        winnerMutated.countDown();
                        awaitLatch(releaseWinner);
                        return null;
                    })));

            assertTrue(winnerMutated.await(30, TimeUnit.SECONDS), "winner never mutated under held locks");

            Future<Object> loser = executor.submit(() -> TenantScopedExecution.callAsAdmin(() -> {
                try {
                    loanDisbursementCommandService.initiateDisbursement(raceId, "race.intent");
                    return "intent-created";
                } catch (RuntimeException conflict) {
                    return conflict;
                }
            }));

            int lockWaiters = awaitPostgresLockWait();
            assertTrue(lockWaiters >= 1,
                    "expected initiate to block in a PostgreSQL lock wait while generic holds the application lock, saw "
                            + lockWaiters);
            assertFalse(loser.isDone(), "initiate must stay blocked while generic holds the application lock");
            assertStillBlocked(loser);

            releaseWinner.countDown();
            winner.get(60, TimeUnit.SECONDS);

            Object outcome = loser.get(60, TimeUnit.SECONDS);
            assertTrue(outcome instanceof RuntimeException, "expected intent rejection, got " + outcome);
            assertEquals(LoanApplicationStatus.INVALID,
                    loanApplicationRepository.findById(raceId).orElseThrow().getStatus());
            LoanAccount account = loanAccountRepository.findByLoanApplication_Id(raceId).orElseThrow();
            assertEquals(0, intentsForAccount(account.getId()));
            assertEquals(0, loanDisbursementRequestLogRepository.countByLoanAccount_Id(account.getId()));
            verify(loanDisbursementAdapter, never()).requestDisbursement(any());
        } finally {
            releaseWinner.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void initiateHoldsApplicationLock_genericInvalidBlocksThenConflicts() throws Exception {
        UUID raceId = seedApproved("HDFC0001234", new BigDecimal("45000.00"));
        // Mirror order: the winner runs the disbursement initiation inside an outer
        // transaction and holds it open, so the borrower → application → account row locks
        // stay held. The loser starts its generic INVALID before the winner commits and must
        // block on the contested shared locks (both paths serialize borrower-first on the same
        // borrower row, then on the application row). After release the loser's locked
        // re-read observes the committed live intent and conflicts.
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch winnerMutated = new CountDownLatch(1);
        CountDownLatch releaseWinner = new CountDownLatch(1);
        try {
            Future<?> winner = executor.submit(() -> TenantScopedExecution.callAsAdmin(() ->
                    transactionTemplate.execute(tx -> {
                        loanDisbursementCommandService.initiateDisbursement(raceId, "race.intent");
                        winnerMutated.countDown();
                        awaitLatch(releaseWinner);
                        return null;
                    })));

            assertTrue(winnerMutated.await(30, TimeUnit.SECONDS), "winner never mutated under held locks");

            Future<Object> loser = executor.submit(() -> TenantScopedExecution.callAsAdmin(() -> {
                try {
                    loanApplicationLifecycleService.transitionStatus(
                            raceId, "ops.admin", LoanApplicationStatus.INVALID,
                            "Contended generic invalid", null);
                    return "invalid-applied";
                } catch (ApiConflictException conflict) {
                    return conflict;
                }
            }));

            int lockWaiters = awaitPostgresLockWait();
            assertTrue(lockWaiters >= 1,
                    "expected generic INVALID to block in a PostgreSQL lock wait while initiation holds the application lock, saw "
                            + lockWaiters);
            assertFalse(loser.isDone(), "generic INVALID must stay blocked while initiation holds the application lock");
            assertStillBlocked(loser);

            releaseWinner.countDown();
            winner.get(60, TimeUnit.SECONDS);

            Object outcome = loser.get(60, TimeUnit.SECONDS);
            assertTrue(outcome instanceof ApiConflictException,
                    "expected DISBURSEMENT_IN_PROGRESS, got " + outcome);
            assertEquals("DISBURSEMENT_IN_PROGRESS", ((ApiConflictException) outcome).getErrorCode());

            LoanAccount account = loanAccountRepository.findByLoanApplication_Id(raceId).orElseThrow();
            assertEquals(LoanAccountStatus.DISBURSEMENT_REQUESTED, account.getStatus());
            assertEquals(1, intentsForAccount(account.getId()));
            assertEquals(LoanApplicationStatus.APPROVED_PENDING_DISBURSAL,
                    loanApplicationRepository.findById(raceId).orElseThrow().getStatus());
            assertEquals(0, countInvalidTransitions(raceId));
        } finally {
            releaseWinner.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void lateBankTerminalAppliesExactlyOnceAfterRejectedGenericMutation() throws Exception {
        UUID applicationId = seedApproved("MOCK0PENDOK", new BigDecimal("45000.00"));
        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/disbursement-requests", applicationId)
                        .with(systemAdmin()))
                .andExpect(status().isOk());
        disbursementIntentWorkflowService.executeForApplication(applicationId);

        LoanAccount account = loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow();
        String originalRef = disbursementIntentRepository.findLiveByLoanAccountId(account.getId())
                .orElseThrow().getTranRefNo();

        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/status-transitions", applicationId)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "targetStatus", "INVALID",
                                "note", "Rejected generic invalid before terminal"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("DISBURSEMENT_IN_PROGRESS"));

        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/disbursement-requests/mock-outcome", applicationId)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("outcome", "DISBURSED"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISBURSED"))
                .andExpect(jsonPath("$.loanAccount.status").value("DISBURSED"));

        assertEquals(LoanApplicationStatus.DISBURSED,
                loanApplicationRepository.findById(applicationId).orElseThrow().getStatus());
        assertEquals(1, loanApplicationStatusTransitionRepository
                .findByLoanApplication_IdAndToStatusOrderByCreatedAtAsc(applicationId, LoanApplicationStatus.DISBURSED)
                .size());
        assertEquals(originalRef, loanDisbursementRequestLogRepository
                .findTopByLoanAccount_IdOrderByCreatedAtDesc(account.getId()).orElseThrow().getTranRefNo());
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            assertTrue(latch.await(30, TimeUnit.SECONDS), "race barrier timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private static void assertStillBlocked(Future<?> loser) throws Exception {
        try {
            Object early = loser.get(2, TimeUnit.SECONDS);
            fail("loser must stay blocked on the contested application lock, but completed with " + early);
        } catch (TimeoutException expected) {
            // Contention proven: the loser is parked in a PostgreSQL lock wait.
        }
    }

    private int awaitPostgresLockWait() throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(15);
        while (Instant.now().isBefore(deadline)) {
            Integer waiters = TenantScopedExecution.callAsAdmin(() -> jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM pg_stat_activity"
                            + " WHERE datname = current_database() AND wait_event_type = 'Lock'",
                    Integer.class));
            if (waiters != null && waiters >= 1) {
                return waiters;
            }
            Thread.sleep(200);
        }
        return 0;
    }

    private long countInvalidTransitions(UUID applicationId) {
        return loanApplicationStatusTransitionRepository
                .findTop20ByLoanApplication_IdOrderByCreatedAtDesc(applicationId)
                .stream()
                .filter(transition -> transition.getToStatus() == LoanApplicationStatus.INVALID)
                .count();
    }

    private long intentsForAccount(UUID accountId) {
        return disbursementIntentRepository.findAll().stream()
                .filter(intent -> intent.getLoanAccount().getId().equals(accountId))
                .count();
    }

    private UUID seedApproved(String ifsc, BigDecimal requestedAmount) throws Exception {
        String lspId = createLspViaAdmin();
        String productId = createProductViaAdmin();
        mapProductToLsp(productId, lspId);
        String applicationId = createApplicationViaOps(lspId, productId, requestedAmount);
        transition(applicationId, "AWAITING_APPROVAL", "Ready for approval");
        markKycComplete(applicationId);
        transition(applicationId, "APPROVED_PENDING_DISBURSAL", "Approved for in-flight guard test");
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
                                "accountHolderName", "Test Borrower"))))
                .andExpect(status().isOk());
    }

    private String createLspViaAdmin() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/internal/admin/lsps")
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", "LSP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                                "name", "Test LSP",
                                "status", "ACTIVE"))))
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
                                "status", "ACTIVE"))))
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
        payload.put("borrowerFullName", "Test Borrower");
        payload.put("borrowerMobile", mobileForPan(borrowerPan));
        payload.put("borrowerEmail", "h31inflight+" + borrowerPan.toLowerCase() + "@example.com");
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
                                "note", note))))
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
                            "Uploaded for in-flight guard test",
                            "ops.user",
                            documentKey + ".pdf",
                            "storage://" + applicationId + "/" + documentKey + ".pdf",
                            null,
                            "application/pdf",
                            1024L,
                            "checksum-" + documentKey,
                            "storage-key/" + applicationId + "/" + documentKey,
                            true);
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

    private void makeAllQueueRowsDue() {
        jdbcTemplate.update(
                "update disbursement_reconciliation_queue set next_poll_at = now() - interval '1 second'");
    }
}
