package com.bhawana.lms.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

import com.bhawana.lms.common.api.error.ApiConflictException;
import com.bhawana.lms.common.api.error.BusinessRuleViolationException;
import com.bhawana.lms.common.money.Money;
import com.bhawana.lms.config.TimeConfig;
import com.bhawana.lms.domain.DisbursementDeclineKind;
import com.bhawana.lms.domain.DisbursementDisposition;
import com.bhawana.lms.domain.DisbursementIntent;
import com.bhawana.lms.domain.DisbursementIntentState;
import com.bhawana.lms.domain.LoanAccount;
import com.bhawana.lms.domain.LoanAccountStatus;
import com.bhawana.lms.domain.LoanApplicationDocumentChecklistStatus;
import com.bhawana.lms.domain.LoanPaymentChannel;
import com.bhawana.lms.domain.LoanPaymentStatus;
import com.bhawana.lms.domain.LoanPaymentTransaction;
import com.bhawana.lms.domain.LoanRepaymentScheduleInstallment;
import com.bhawana.lms.repo.DisbursementIntentRepository;
import com.bhawana.lms.repo.LoanAccountRepository;
import com.bhawana.lms.repo.LoanApplicationDocumentChecklistRepository;
import com.bhawana.lms.repo.LoanApplicationRepository;
import com.bhawana.lms.repo.LoanDisbursementRequestLogRepository;
import com.bhawana.lms.repo.LoanPaymentTransactionRepository;
import com.bhawana.lms.repo.LoanRepaymentScheduleInstallmentRepository;
import com.bhawana.lms.service.DisbursementIntentWorkflowService;
import com.bhawana.lms.service.LoanDisbursementAdapter;
import com.bhawana.lms.service.LoanDisbursementCommandService;
import com.bhawana.lms.service.LoanRepaymentScheduleService;
import com.bhawana.lms.support.IntegrationTestDatabaseCleaner;
import com.bhawana.lms.support.TenantContextTestExecutionListener;
import com.bhawana.lms.support.TestPanSequence;
import com.bhawana.lms.tenant.TenantScopedExecution;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.function.Supplier;import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
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
 * Schedule replacement shares the disbursement locks and the frozen schedule hash.
 *
 * <p>Generated and provided replacements lock application → account → intent and recheck the
 * live intent before deleting anything, so a replacement cannot commit after the disbursement
 * eligibility check. Intent creation freezes the canonical schedule hash; submission
 * preparation validates it and grants no provider call on mismatch. A CREATED intent without
 * frozen evidence (legacy) stays blocked, while an already-submitted instruction remains
 * reconcilable.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestExecutionListeners(
        value = TenantContextTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS
)
class ScheduleReplacementFreezeIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private LoanApplicationRepository loanApplicationRepository;
    @Autowired private LoanAccountRepository loanAccountRepository;
    @Autowired private LoanApplicationDocumentChecklistRepository loanApplicationDocumentChecklistRepository;
    @Autowired private DisbursementIntentRepository disbursementIntentRepository;
    @Autowired private LoanDisbursementRequestLogRepository loanDisbursementRequestLogRepository;
    @MockitoSpyBean private LoanRepaymentScheduleInstallmentRepository installmentRepository;
    @Autowired private LoanPaymentTransactionRepository loanPaymentTransactionRepository;
    @Autowired private DisbursementIntentWorkflowService disbursementIntentWorkflowService;
    @Autowired private LoanDisbursementCommandService loanDisbursementCommandService;
    @Autowired private LoanRepaymentScheduleService loanRepaymentScheduleService;
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
    void generatedReplacementFirst_Succeeds_AndFrozenHashMatches() throws Exception {
        UUID applicationId = seedApproved("HDFC0001234", new BigDecimal("45000.00"));
        UUID lspId = owningLsp(applicationId);
        LoanAccount account = loanAccountOf(applicationId);
        String hashBefore = scheduleHash(account.getId());

        replaceGenerated(lspId, applicationId);

        initiate(applicationId);
        DisbursementIntent intent = liveCreatedIntent(applicationId);
        assertEquals(scheduleHash(account.getId()), intent.getScheduleHash());
        assertEquals(hashBefore, intent.getScheduleHash());

        assertTrue(execute(applicationId).isPresent());
        verify(loanDisbursementAdapter, times(1)).requestDisbursement(any());
        assertEquals(LoanAccountStatus.DISBURSED, loanAccountOf(applicationId).getStatus());
    }

    @Test
    void providedDifferentScheduleFirst_FrozenHashReflectsNewRevision_AndSubmissionSucceeds() throws Exception {
        UUID applicationId = seedApproved("HDFC0001234", new BigDecimal("45000.00"));
        UUID lspId = owningLsp(applicationId);
        LoanAccount account = loanAccountOf(applicationId);
        String originalHash = scheduleHash(account.getId());

        replaceProvidedNudged(lspId, applicationId);
        String replacedHash = scheduleHash(account.getId());
        assertTrue(!originalHash.equals(replacedHash), "nudged schedule must change the canonical hash");

        initiate(applicationId);
        assertEquals(replacedHash, liveCreatedIntent(applicationId).getScheduleHash());

        assertTrue(execute(applicationId).isPresent());
        verify(loanDisbursementAdapter, times(1)).requestDisbursement(any());
        assertEquals(LoanAccountStatus.DISBURSED, loanAccountOf(applicationId).getStatus());
    }

    @Test
    void commitmentFirst_GeneratedReplacementRejected_OldRowsUntouched() throws Exception {
        UUID applicationId = seedApproved("HDFC0001234", new BigDecimal("45000.00"));
        UUID lspId = owningLsp(applicationId);
        LoanAccount account = loanAccountOf(applicationId);
        String originalHash = scheduleHash(account.getId());
        int rowsBefore = installmentRepository
                .findByLoanAccount_IdOrderByInstallmentNumberAsc(account.getId()).size();

        initiate(applicationId);
        String frozenHash = liveCreatedIntent(applicationId).getScheduleHash();
        assertEquals(originalHash, frozenHash);

        ApiConflictException conflict = assertThrows(
                ApiConflictException.class, () -> replaceGenerated(lspId, applicationId));
        assertEquals("REPAYMENT_SCHEDULE_LOCKED", conflict.getErrorCode());

        assertEquals(rowsBefore, installmentRepository
                .findByLoanAccount_IdOrderByInstallmentNumberAsc(account.getId()).size());
        assertEquals(originalHash, scheduleHash(account.getId()));

        assertTrue(execute(applicationId).isPresent());
        verify(loanDisbursementAdapter, times(1)).requestDisbursement(any());
        assertEquals(LoanAccountStatus.DISBURSED, loanAccountOf(applicationId).getStatus());
    }

    @Test
    void commitmentFirst_ProvidedReplacementRejected_OldRowsUntouched() throws Exception {
        UUID applicationId = seedApproved("HDFC0001234", new BigDecimal("45000.00"));
        UUID lspId = owningLsp(applicationId);
        LoanAccount account = loanAccountOf(applicationId);
        String originalHash = scheduleHash(account.getId());
        int rowsBefore = installmentRepository
                .findByLoanAccount_IdOrderByInstallmentNumberAsc(account.getId()).size();

        initiate(applicationId);

        ApiConflictException conflict = assertThrows(
                ApiConflictException.class, () -> replaceProvidedNudged(lspId, applicationId));
        assertEquals("REPAYMENT_SCHEDULE_LOCKED", conflict.getErrorCode());

        assertEquals(rowsBefore, installmentRepository
                .findByLoanAccount_IdOrderByInstallmentNumberAsc(account.getId()).size());
        assertEquals(originalHash, scheduleHash(account.getId()));

        assertTrue(execute(applicationId).isPresent());
        verify(loanDisbursementAdapter, times(1)).requestDisbursement(any());
    }

    @Test
    void invalidProvidedSchedule_BeforeIntent_RejectedWithAuditCode_AndRowsUntouched() throws Exception {
        UUID applicationId = seedApproved("HDFC0001234", new BigDecimal("45000.00"));
        UUID lspId = owningLsp(applicationId);
        LoanAccount account = loanAccountOf(applicationId);
        String originalHash = scheduleHash(account.getId());
        int rowsBefore = installmentRepository
                .findByLoanAccount_IdOrderByInstallmentNumberAsc(account.getId()).size();

        List<LoanRepaymentScheduleService.InstallmentDraft> drafts = persistedDrafts(account.getId());
        List<LoanRepaymentScheduleService.InstallmentDraft> broken = new ArrayList<>(drafts);
        LoanRepaymentScheduleService.InstallmentDraft first = broken.get(0);
        broken.set(0, new LoanRepaymentScheduleService.InstallmentDraft(
                first.installmentNumber(),
                first.dueDate(),
                first.openingPrincipal().add(new BigDecimal("1000.00")),
                first.principalDue(),
                first.interestDue(),
                first.installmentAmount(),
                first.closingPrincipal()));

        BusinessRuleViolationException violation = assertThrows(
                BusinessRuleViolationException.class,
                () -> TenantScopedExecution.callAsAdmin(() -> loanRepaymentScheduleService
                        .replaceWithProvidedScheduleForLsp(lspId, applicationId, broken)));
        assertEquals("REPAYMENT_SCHEDULE_INVALID", violation.getErrorCode());

        assertEquals(rowsBefore, installmentRepository
                .findByLoanAccount_IdOrderByInstallmentNumberAsc(account.getId()).size());
        assertEquals(originalHash, scheduleHash(account.getId()));

        // The loan is still eligible: validation failure after lock acquisition deleted nothing.
        initiate(applicationId);
        assertEquals(originalHash, liveCreatedIntent(applicationId).getScheduleHash());
    }

    @Test
    void receiptGuard_ReplacementRejectedAfterRepaymentsStarted() throws Exception {
        UUID applicationId = seedApproved("HDFC0001234", new BigDecimal("45000.00"));
        UUID lspId = owningLsp(applicationId);
        LoanAccount account = loanAccountOf(applicationId);
        String originalHash = scheduleHash(account.getId());
        int rowsBefore = installmentRepository
                .findByLoanAccount_IdOrderByInstallmentNumberAsc(account.getId()).size();

        LoanRepaymentScheduleInstallment firstInstallment = installmentRepository
                .findByLoanAccount_IdOrderByInstallmentNumberAsc(account.getId()).get(0);
        LoanAccount attached = loanAccountRepository.findById(account.getId()).orElseThrow();
        loanPaymentTransactionRepository.save(new LoanPaymentTransaction(
                attached,
                firstInstallment,
                "t15.test",
                new BigDecimal("1000.00"),
                LocalDate.now(TimeConfig.BUSINESS_ZONE),
                "RCPT-001",
                LoanPaymentChannel.UPI,
                LoanPaymentStatus.RECEIVED,
                "receipt guard seed",
                "t15-receipt",
                UUID.randomUUID().toString()));

        ApiConflictException generatedConflict = assertThrows(
                ApiConflictException.class, () -> replaceGenerated(lspId, applicationId));
        assertEquals("REPAYMENT_SCHEDULE_LOCKED", generatedConflict.getErrorCode());
        ApiConflictException providedConflict = assertThrows(
                ApiConflictException.class, () -> replaceProvidedNudged(lspId, applicationId));
        assertEquals("REPAYMENT_SCHEDULE_LOCKED", providedConflict.getErrorCode());

        assertEquals(rowsBefore, installmentRepository
                .findByLoanAccount_IdOrderByInstallmentNumberAsc(account.getId()).size());
        assertEquals(originalHash, scheduleHash(account.getId()));
    }

    @Test
    void scheduleChangedAfterFreeze_BlocksSubmission_ZeroProviderCalls() throws Exception {
        UUID applicationId = seedApproved("HDFC0001234", new BigDecimal("45000.00"));
        initiate(applicationId);
        LoanAccount account = loanAccountOf(applicationId);
        UUID intentId = liveCreatedIntent(applicationId).getId();

        // A change that commits after the eligibility check without passing the shared locks
        // (e.g. a legacy writer): submission must observe it via the frozen hash and stop.
        jdbcTemplate.update(
                "UPDATE loan_repayment_schedule_installment SET interest_due = interest_due + 100"
                        + " WHERE loan_account_id = ? AND installment_number = 1",
                account.getId());

        assertTrue(execute(applicationId).isEmpty());
        verify(loanDisbursementAdapter, times(0)).requestDisbursement(any());
        assertEquals(DisbursementIntentState.CREATED,
                disbursementIntentRepository.findById(intentId).orElseThrow().getState());
        assertEquals(LoanAccountStatus.DISBURSEMENT_REQUESTED, loanAccountOf(applicationId).getStatus());
        assertEquals(0L, loanDisbursementRequestLogRepository.countByLoanAccount_Id(account.getId()));
    }

    @Test
    void legacyCreatedIntentWithoutHash_Blocked_WhileSubmittedReconciles() throws Exception {
        // Order 1: legacy CREATED intent (no frozen evidence) must not submit — evidence is
        // never invented, so preparation grants no permission.
        UUID blockedApp = seedApproved("HDFC0001234", new BigDecimal("45000.00"));
        initiate(blockedApp);
        UUID blockedIntent = liveCreatedIntent(blockedApp).getId();
        jdbcTemplate.update("UPDATE disbursement_intent SET schedule_hash = NULL WHERE id = ?::uuid",
                blockedIntent.toString());

        assertTrue(execute(blockedApp).isEmpty());
        verify(loanDisbursementAdapter, times(0)).requestDisbursement(any());
        assertEquals(DisbursementIntentState.CREATED,
                disbursementIntentRepository.findById(blockedIntent).orElseThrow().getState());

        // Order 2: an already-submitted instruction without frozen evidence stays reconcilable.
        reset(loanDisbursementAdapter);
        UUID reconciledApp = seedApproved("HDFC0001234", new BigDecimal("46000.00"));
        initiate(reconciledApp);
        UUID reconciledIntent = liveCreatedIntent(reconciledApp).getId();
        Mockito.doThrow(new RuntimeException("simulated provider failure with unknown outcome"))
                .when(loanDisbursementAdapter).requestDisbursement(any());
        assertTrue(execute(reconciledApp).isPresent());
        assertEquals(DisbursementIntentState.UNKNOWN,
                disbursementIntentRepository.findById(reconciledIntent).orElseThrow().getState());
        jdbcTemplate.update("UPDATE disbursement_intent SET schedule_hash = NULL WHERE id = ?::uuid",
                reconciledIntent.toString());

        Mockito.doAnswer(invocation -> new LoanDisbursementAdapter.DisbursementStatusResult(
                        "0", "Check Transaction Successful",
                        DisbursementDisposition.SUCCESS,
                        DisbursementDeclineKind.NONE,
                        "0", "RRN-LEGACY-001", "recovered", "{}"))
                .when(loanDisbursementAdapter).checkStatus(any());
        assertTrue(TenantScopedExecution.callAsAdmin(() -> loanDisbursementCommandService
                .pollPendingDisbursement(reconciledApp, "worker", null, "t15-legacy")));
        assertEquals(LoanAccountStatus.DISBURSED, loanAccountOf(reconciledApp).getStatus());
        assertEquals(DisbursementIntentState.SUCCEEDED,
                disbursementIntentRepository.findById(reconciledIntent).orElseThrow().getState());
        verify(loanDisbursementAdapter, times(1)).requestDisbursement(any());
    }

    @Test
    void generatedReplacementVsCreation_LockContention_BothOrders() throws Exception {
        // Order 1: the generated replacement runs its REAL mutation inside an outer transaction
        // and HOLDS the uncommitted application → account locks. Intent creation starts while
        // those locks are held, must reach PostgreSQL lock wait (proven below), and only
        // proceeds after the winner commits — freezing the rebuilt schedule.
        UUID firstApp = seedApproved("HDFC0001234", new BigDecimal("45000.00"));
        UUID firstLsp = owningLsp(firstApp);
        LoanAccount firstAccount = loanAccountOf(firstApp);
        List<UUID> idsBeforeReplace = installmentIds(firstAccount.getId());
        CountDownLatch winnerHolding = new CountDownLatch(1);
        CountDownLatch releaseWinner = new CountDownLatch(1);
        ExecutorService firstExecutor = Executors.newFixedThreadPool(2);
        try {
            Future<?> winner = firstExecutor.submit(() -> TenantScopedExecution.callAsAdmin(() ->
                    transactionTemplate.execute(tx -> {
                        replaceGenerated(firstLsp, firstApp);
                        winnerHolding.countDown();
                        await(releaseWinner);
                        return null;
                    })));
            assertTrue(winnerHolding.await(30, TimeUnit.SECONDS));
            AtomicReference<Object> loserOutcome = new AtomicReference<>();
            Future<?> loser = firstExecutor.submit(() -> TenantScopedExecution.callAsAdmin(() -> {
                try {
                    loanDisbursementCommandService.initiateDisbursement(firstApp, "t15.contention");
                    loserOutcome.set("SUCCESS");
                } catch (RuntimeException exception) {
                    loserOutcome.set(exception);
                }
                return null;
            }));
            awaitLockContention(loser);
            releaseWinner.countDown();
            winner.get(60, TimeUnit.SECONDS);
            loser.get(60, TimeUnit.SECONDS);
            assertEquals("SUCCESS", loserOutcome.get());
        } finally {
            releaseWinner.countDown();
            firstExecutor.shutdownNow();
        }
        assertTrue(!installmentIds(firstAccount.getId()).equals(idsBeforeReplace),
                "winner replacement must have rebuilt rows before creation froze them");
        assertEquals(scheduleHash(firstAccount.getId()),
                liveCreatedIntent(firstApp).getScheduleHash());
        assertTrue(execute(firstApp).isPresent());
        verify(loanDisbursementAdapter, times(1)).requestDisbursement(any());

        // Order 2: intent creation holds its uncommitted application → account → intent locks
        // while the generated replacement starts, reaches lock wait, and only proceeds after
        // the winner commits — hitting the locked live-intent recheck with zero deletes.
        reset(loanDisbursementAdapter);
        UUID secondApp = seedApproved("HDFC0001234", new BigDecimal("46000.00"));
        UUID secondLsp = owningLsp(secondApp);
        LoanAccount secondAccount = loanAccountOf(secondApp);
        List<String> rowsBefore = snapshotInstallments(secondAccount.getId());
        CountDownLatch winnerHolding2 = new CountDownLatch(1);
        CountDownLatch releaseWinner2 = new CountDownLatch(1);
        ExecutorService secondExecutor = Executors.newFixedThreadPool(2);
        try {
            Future<?> winner = secondExecutor.submit(() -> TenantScopedExecution.callAsAdmin(() ->
                    transactionTemplate.execute(tx -> {
                        loanDisbursementCommandService.initiateDisbursement(secondApp, "t15.contention");
                        winnerHolding2.countDown();
                        await(releaseWinner2);
                        return null;
                    })));
            assertTrue(winnerHolding2.await(30, TimeUnit.SECONDS));
            AtomicReference<Object> loserOutcome = new AtomicReference<>();
            Future<?> loser = secondExecutor.submit(() -> TenantScopedExecution.callAsAdmin(() -> {
                try {
                    replaceGenerated(secondLsp, secondApp);
                    loserOutcome.set("SUCCESS");
                } catch (RuntimeException exception) {
                    loserOutcome.set(exception);
                }
                return null;
            }));
            awaitLockContention(loser);
            releaseWinner2.countDown();
            winner.get(60, TimeUnit.SECONDS);
            loser.get(60, TimeUnit.SECONDS);
            assertTrue(loserOutcome.get() instanceof ApiConflictException conflict
                    && "REPAYMENT_SCHEDULE_LOCKED".equals(conflict.getErrorCode()),
                    "losing replacement must be rejected, got: " + loserOutcome.get());
        } finally {
            releaseWinner2.countDown();
            secondExecutor.shutdownNow();
        }
        assertEquals(rowsBefore, snapshotInstallments(secondAccount.getId()));

        assertTrue(execute(secondApp).isPresent());
        verify(loanDisbursementAdapter, times(1)).requestDisbursement(any());
        assertEquals(LoanAccountStatus.DISBURSED, loanAccountOf(secondApp).getStatus());
    }

    @Test
    void providedReplacementVsCreation_LockContention_BothOrders() throws Exception {
        // Order 1: a *changed* provided replacement holds its uncommitted locks while intent
        // creation reaches lock wait; after the winner commits, creation must freeze the NEW
        // revision — never the pre-replacement one.
        UUID firstApp = seedApproved("HDFC0001234", new BigDecimal("45000.00"));
        UUID firstLsp = owningLsp(firstApp);
        LoanAccount firstAccount = loanAccountOf(firstApp);
        String originalHash = scheduleHash(firstAccount.getId());
        List<LoanRepaymentScheduleService.InstallmentDraft> changedDrafts =
                persistedDrafts(firstAccount.getId());
        CountDownLatch winnerHolding = new CountDownLatch(1);
        CountDownLatch releaseWinner = new CountDownLatch(1);
        ExecutorService firstExecutor = Executors.newFixedThreadPool(2);
        try {
            Future<?> winner = firstExecutor.submit(() -> TenantScopedExecution.callAsAdmin(() ->
                    transactionTemplate.execute(tx -> {
                        loanRepaymentScheduleService.replaceWithProvidedScheduleForLsp(
                                firstLsp, firstApp, changedDrafts);
                        winnerHolding.countDown();
                        await(releaseWinner);
                        return null;
                    })));
            assertTrue(winnerHolding.await(30, TimeUnit.SECONDS));
            AtomicReference<Object> loserOutcome = new AtomicReference<>();
            Future<?> loser = firstExecutor.submit(() -> TenantScopedExecution.callAsAdmin(() -> {
                try {
                    loanDisbursementCommandService.initiateDisbursement(firstApp, "t15.contention");
                    loserOutcome.set("SUCCESS");
                } catch (RuntimeException exception) {
                    loserOutcome.set(exception);
                }
                return null;
            }));
            awaitLockContention(loser);
            releaseWinner.countDown();
            winner.get(60, TimeUnit.SECONDS);
            loser.get(60, TimeUnit.SECONDS);
            assertEquals("SUCCESS", loserOutcome.get());
        } finally {
            releaseWinner.countDown();
            firstExecutor.shutdownNow();
        }
        String replacedHash = scheduleHash(firstAccount.getId());
        assertTrue(!originalHash.equals(replacedHash), "nudged schedule must change the canonical hash");
        assertEquals(replacedHash, liveCreatedIntent(firstApp).getScheduleHash());
        assertTrue(execute(firstApp).isPresent());
        verify(loanDisbursementAdapter, times(1)).requestDisbursement(any());

        // Order 2: creation holds its uncommitted locks while the provided replacement reaches
        // lock wait; after the winner commits, the loser hits the locked live-intent recheck
        // with the exact pre-existing rows (IDs and values) untouched.
        reset(loanDisbursementAdapter);
        UUID secondApp = seedApproved("HDFC0001234", new BigDecimal("46000.00"));
        UUID secondLsp = owningLsp(secondApp);
        LoanAccount secondAccount = loanAccountOf(secondApp);
        List<String> rowsBefore = snapshotInstallments(secondAccount.getId());
        List<LoanRepaymentScheduleService.InstallmentDraft> secondDrafts =
                persistedDrafts(secondAccount.getId());
        CountDownLatch winnerHolding2 = new CountDownLatch(1);
        CountDownLatch releaseWinner2 = new CountDownLatch(1);
        ExecutorService secondExecutor = Executors.newFixedThreadPool(2);
        try {
            Future<?> winner = secondExecutor.submit(() -> TenantScopedExecution.callAsAdmin(() ->
                    transactionTemplate.execute(tx -> {
                        loanDisbursementCommandService.initiateDisbursement(secondApp, "t15.contention");
                        winnerHolding2.countDown();
                        await(releaseWinner2);
                        return null;
                    })));
            assertTrue(winnerHolding2.await(30, TimeUnit.SECONDS));
            AtomicReference<Object> loserOutcome = new AtomicReference<>();
            Future<?> loser = secondExecutor.submit(() -> TenantScopedExecution.callAsAdmin(() -> {
                try {
                    loanRepaymentScheduleService.replaceWithProvidedScheduleForLsp(
                            secondLsp, secondApp, secondDrafts);
                    loserOutcome.set("SUCCESS");
                } catch (RuntimeException exception) {
                    loserOutcome.set(exception);
                }
                return null;
            }));
            awaitLockContention(loser);
            releaseWinner2.countDown();
            winner.get(60, TimeUnit.SECONDS);
            loser.get(60, TimeUnit.SECONDS);
            assertTrue(loserOutcome.get() instanceof ApiConflictException conflict
                    && "REPAYMENT_SCHEDULE_LOCKED".equals(conflict.getErrorCode()),
                    "losing replacement must be rejected, got: " + loserOutcome.get());
        } finally {
            releaseWinner2.countDown();
            secondExecutor.shutdownNow();
        }
        assertEquals(rowsBefore, snapshotInstallments(secondAccount.getId()));

        assertTrue(execute(secondApp).isPresent());
        verify(loanDisbursementAdapter, times(1)).requestDisbursement(any());
    }

    @Test
    void preparationCommitFirst_ReplacementRejected_ZeroDeletes() throws Exception {
        UUID applicationId = seedApproved("HDFC0001234", new BigDecimal("45000.00"));
        UUID lspId = owningLsp(applicationId);
        LoanAccount account = loanAccountOf(applicationId);
        List<String> rowsBefore = snapshotInstallments(account.getId());
        initiate(applicationId);

        // Force preparation to commit first while the provider call is held open: the
        // replacement attempt below runs strictly after the CREATED → REQUESTED commit.
        CountDownLatch providerEntered = new CountDownLatch(1);
        CountDownLatch providerRelease = new CountDownLatch(1);
        Mockito.doAnswer(invocation -> {
            providerEntered.countDown();
            assertTrue(providerRelease.await(60, TimeUnit.SECONDS));
            return invocation.callRealMethod();
        }).when(loanDisbursementAdapter).requestDisbursement(any());
        ExecutorService executor = Executors.newFixedThreadPool(1);
        try {
            Future<Optional<UUID>> execution = executor.submit(() -> TenantScopedExecution.callAsAdmin(
                    () -> disbursementIntentWorkflowService.executeForApplication(applicationId)));
            assertTrue(providerEntered.await(60, TimeUnit.SECONDS));
            assertEquals(DisbursementIntentState.REQUESTED,
                    disbursementIntentRepository.findLiveByLoanAccountId(account.getId())
                            .orElseThrow().getState());

            ApiConflictException generatedConflict = assertThrows(
                    ApiConflictException.class, () -> replaceGenerated(lspId, applicationId));
            assertEquals("REPAYMENT_SCHEDULE_LOCKED", generatedConflict.getErrorCode());
            ApiConflictException providedConflict = assertThrows(
                    ApiConflictException.class, () -> replaceProvidedNudged(lspId, applicationId));
            assertEquals("REPAYMENT_SCHEDULE_LOCKED", providedConflict.getErrorCode());
            assertEquals(rowsBefore, snapshotInstallments(account.getId()));

            providerRelease.countDown();
            assertTrue(execution.get(60, TimeUnit.SECONDS).isPresent());
        } finally {
            providerRelease.countDown();
            executor.shutdownNow();
        }
        verify(loanDisbursementAdapter, times(1)).requestDisbursement(any());
        assertEquals(LoanAccountStatus.DISBURSED, loanAccountOf(applicationId).getStatus());
    }

    @Test
    void replacementBlocksOnSharedLock_UntilHolderCommits() throws Exception {
        UUID applicationId = seedApproved("HDFC0001234", new BigDecimal("45000.00"));
        UUID lspId = owningLsp(applicationId);
        LoanAccount account = loanAccountOf(applicationId);
        List<UUID> idsBefore = installmentIds(account.getId());

        // An independent transaction holds the shared application → account locks. The
        // replacement must block on PostgreSQL (not fail fast, not slip through) until the
        // holder commits.
        CountDownLatch holderHasLock = new CountDownLatch(1);
        CountDownLatch releaseHolder = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = executor.submit(() -> TenantScopedExecution.callAsAdmin(() ->
                    transactionTemplate.execute(tx -> {
                        loanApplicationRepository.findByIdForUpdate(applicationId).orElseThrow();
                        loanAccountRepository.findByLoanApplication_IdForUpdate(applicationId).orElseThrow();
                        holderHasLock.countDown();
                        await(releaseHolder);
                        return null;
                    })));
            assertTrue(holderHasLock.await(30, TimeUnit.SECONDS));
            Future<?> replacement = executor.submit(() -> TenantScopedExecution.callAsAdmin(() -> {
                replaceGenerated(lspId, applicationId);
                return null;
            }));
            Thread.sleep(2000);
            assertFalse(replacement.isDone(),
                    "replacement must block on the held shared locks, not complete concurrently");
            releaseHolder.countDown();
            replacement.get(60, TimeUnit.SECONDS);
            holder.get(60, TimeUnit.SECONDS);
        } finally {
            releaseHolder.countDown();
            executor.shutdownNow();
        }

        assertTrue(!installmentIds(account.getId()).equals(idsBefore),
                "released replacement must have rebuilt the schedule");
    }

    @Test
    void postDeleteInsertionFailure_RollsBack_PreservingExactRows() throws Exception {
        UUID applicationId = seedApproved("HDFC0001234", new BigDecimal("45000.00"));
        UUID lspId = owningLsp(applicationId);
        LoanAccount account = loanAccountOf(applicationId);
        List<String> rowsBefore = snapshotInstallments(account.getId());
        String hashBefore = scheduleHash(account.getId());

        // Fail *after* the locked delete is flushed but before the insert commits: the whole
        // replacement must roll back to the exact rows. The explicit flush() in the service
        // forces the DELETE SQL out before saveAll, so stubbing saveAll to throw exercises a
        // genuine post-delete failure — the verifications below prove the delete really ran.
        List<LoanRepaymentScheduleService.InstallmentDraft> valid = persistedDrafts(account.getId());
        Mockito.doThrow(new IllegalStateException("simulated post-delete persistence failure"))
                .when(installmentRepository).saveAll(any());
        // Drop approval-path interactions so the verifications below count only the failing call.
        Mockito.clearInvocations(installmentRepository);
        try {
            IllegalStateException failure = assertThrows(
                    IllegalStateException.class,
                    () -> TenantScopedExecution.callAsAdmin(() -> loanRepaymentScheduleService
                            .replaceWithProvidedScheduleForLsp(lspId, applicationId, valid)));
            assertEquals("simulated post-delete persistence failure", failure.getMessage());
            verify(installmentRepository, times(1)).deleteByLoanAccountId(account.getId());
            verify(installmentRepository, times(1)).saveAll(any());
        } finally {
            Mockito.reset(installmentRepository);
        }

        assertEquals(rowsBefore, snapshotInstallments(account.getId()));
        assertEquals(hashBefore, scheduleHash(account.getId()));

        // Recovery still works on the intact schedule.
        initiate(applicationId);
        assertEquals(hashBefore, liveCreatedIntent(applicationId).getScheduleHash());
    }

    @Test
    void liveIntentGuard_HoldsWhenAccountStatusMismatched() throws Exception {
        UUID applicationId = seedApproved("HDFC0001234", new BigDecimal("45000.00"));
        UUID lspId = owningLsp(applicationId);
        LoanAccount account = loanAccountOf(applicationId);
        List<String> rowsBefore = snapshotInstallments(account.getId());
        initiate(applicationId);
        UUID intentId = liveCreatedIntent(applicationId).getId();

        // Legacy mismatch fixture: the account row claims PENDING while a live intent exists.
        // The status check alone would admit replacement; the locked live-intent recheck must
        // still reject it with zero deletes.
        jdbcTemplate.update("UPDATE loan_account SET status = 'PENDING_DISBURSEMENT' WHERE id = ?",
                account.getId());

        ApiConflictException generatedConflict = assertThrows(
                ApiConflictException.class, () -> replaceGenerated(lspId, applicationId));
        assertEquals("REPAYMENT_SCHEDULE_LOCKED", generatedConflict.getErrorCode());
        ApiConflictException providedConflict = assertThrows(
                ApiConflictException.class, () -> replaceProvidedNudged(lspId, applicationId));
        assertEquals("REPAYMENT_SCHEDULE_LOCKED", providedConflict.getErrorCode());
        assertEquals(rowsBefore, snapshotInstallments(account.getId()));
        assertEquals(DisbursementIntentState.CREATED,
                disbursementIntentRepository.findById(intentId).orElseThrow().getState());

        jdbcTemplate.update("UPDATE loan_account SET status = 'DISBURSEMENT_REQUESTED' WHERE id = ?",
                account.getId());
    }

    @Test
    void generateIfAbsent_SkippedUnderLiveIntent_RebuiltForNewAccount() throws Exception {
        // Live intent + empty schedule: generation must not rebuild terms under a submitted
        // instruction.
        UUID blockedApp = seedApproved("HDFC0001234", new BigDecimal("45000.00"));
        LoanAccount blockedAccount = loanAccountOf(blockedApp);
        initiate(blockedApp);
        jdbcTemplate.update("DELETE FROM loan_repayment_schedule_installment WHERE loan_account_id = ?",
                blockedAccount.getId());
        TenantScopedExecution.callAsAdmin(() -> {
            loanRepaymentScheduleService.generateIfAbsent(loanAccountOf(blockedApp));
            return null;
        });
        assertTrue(installmentRepository
                .findByLoanAccount_IdOrderByInstallmentNumberAsc(blockedAccount.getId()).isEmpty());

        // New-account approval shape (PENDING, no intent, no receipts): empty schedule rebuilds.
        UUID freshApp = seedApproved("HDFC0001234", new BigDecimal("46000.00"));
        LoanAccount freshAccount = loanAccountOf(freshApp);
        String freshHash = scheduleHash(freshAccount.getId());
        jdbcTemplate.update("DELETE FROM loan_repayment_schedule_installment WHERE loan_account_id = ?",
                freshAccount.getId());
        TenantScopedExecution.callAsAdmin(() -> {
            loanRepaymentScheduleService.generateIfAbsent(loanAccountOf(freshApp));
            return null;
        });
        assertEquals(12, installmentRepository
                .findByLoanAccount_IdOrderByInstallmentNumberAsc(freshAccount.getId()).size());
        assertEquals(freshHash, scheduleHash(freshAccount.getId()));
    }

    // ---- helpers ----

    private int lockWaiters() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_stat_activity"
                        + " WHERE wait_event_type = 'Lock' AND pid <> pg_backend_pid()",
                Integer.class);
        return count == null ? 0 : count;
    }

    /**
     * Positive proof that the loser reached PostgreSQL lock wait while the winner holds its
     * uncommitted row locks: polls (bounded) until another backend shows a lock wait event
     * with the loser still pending. A loser that completes without ever waiting — i.e. no
     * shared-lock serialization — fails here instead of racing silently.
     */
    private void awaitLockContention(Future<?> loser) {
        long deadline = System.currentTimeMillis() + 15000;
        int waiters = 0;
        while (System.currentTimeMillis() < deadline) {
            waiters = lockWaiters();
            if (waiters >= 1 && !loser.isDone()) {
                return;
            }
            if (loser.isDone()) {
                break;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
        }
        assertTrue(waiters >= 1,
                "expected the loser to reach PostgreSQL lock wait before the winner commits");
        assertFalse(loser.isDone(),
                "loser must still be pending while the winner holds uncommitted locks");
    }

    private void runInThread(Supplier<Void> task) throws Exception {
        try (ExecutorService executor = Executors.newFixedThreadPool(1)) {
            executor.submit(() -> TenantScopedExecution.callAsAdmin(task)).get(60, TimeUnit.SECONDS);
        }
    }

    private List<UUID> installmentIds(UUID loanAccountId) {
        return installmentRepository.findByLoanAccount_IdOrderByInstallmentNumberAsc(loanAccountId)
                .stream().map(LoanRepaymentScheduleInstallment::getId).toList();
    }

    private List<String> snapshotInstallments(UUID loanAccountId) {
        return installmentRepository.findByLoanAccount_IdOrderByInstallmentNumberAsc(loanAccountId)
                .stream()
                .map(installment -> String.join("|",
                        installment.getId().toString(),
                        String.valueOf(installment.getInstallmentNumber()),
                        installment.getDueDate().toString(),
                        installment.getOpeningPrincipal().toPlainString(),
                        installment.getPrincipalDue().toPlainString(),
                        installment.getInterestDue().toPlainString(),
                        installment.getInstallmentAmount().toPlainString(),
                        installment.getClosingPrincipal().toPlainString()))
                .toList();
    }

    private void replaceGenerated(UUID lspId, UUID applicationId) {
        TenantScopedExecution.callAsAdmin(() -> loanRepaymentScheduleService
                .replaceWithGeneratedScheduleForLsp(lspId, applicationId));
    }

    private void replaceProvidedNudged(UUID lspId, UUID applicationId) {
        LoanAccount account = loanAccountOf(applicationId);
        List<LoanRepaymentScheduleService.InstallmentDraft> nudged = persistedDrafts(account.getId());
        TenantScopedExecution.callAsAdmin(() -> loanRepaymentScheduleService
                .replaceWithProvidedScheduleForLsp(lspId, applicationId, nudged));
    }

    private List<LoanRepaymentScheduleService.InstallmentDraft> persistedDrafts(UUID loanAccountId) {
        List<LoanRepaymentScheduleInstallment> stored = installmentRepository
                .findByLoanAccount_IdOrderByInstallmentNumberAsc(loanAccountId);
        List<LoanRepaymentScheduleService.InstallmentDraft> drafts = new ArrayList<>();
        for (LoanRepaymentScheduleInstallment installment : stored) {
            BigDecimal interestDue = installment.getInterestDue();
            BigDecimal installmentAmount = installment.getInstallmentAmount();
            if (installment.getInstallmentNumber() == 1) {
                interestDue = Money.scale(interestDue.add(new BigDecimal("5.00")));
                installmentAmount = Money.scale(installment.getPrincipalDue().add(interestDue));
            }
            drafts.add(new LoanRepaymentScheduleService.InstallmentDraft(
                    installment.getInstallmentNumber(),
                    installment.getDueDate(),
                    installment.getOpeningPrincipal(),
                    installment.getPrincipalDue(),
                    interestDue,
                    installmentAmount,
                    installment.getClosingPrincipal()));
        }
        return drafts;
    }

    private String scheduleHash(UUID loanAccountId) {
        return TenantScopedExecution.callAsAdmin(
                () -> loanRepaymentScheduleService.currentScheduleHash(loanAccountId));
    }

    private DisbursementIntent liveCreatedIntent(UUID applicationId) {
        LoanAccount account = loanAccountOf(applicationId);
        return disbursementIntentRepository.findLiveByLoanAccountId(account.getId())
                .filter(candidate -> candidate.getState() == DisbursementIntentState.CREATED)
                .orElseThrow();
    }

    private Optional<UUID> execute(UUID applicationId) {
        return TenantScopedExecution.callAsAdmin(
                () -> disbursementIntentWorkflowService.executeForApplication(applicationId));
    }

    private LoanAccount loanAccountOf(UUID applicationId) {
        return loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow();
    }

    private UUID owningLsp(UUID applicationId) {
        return loanApplicationRepository.findById(applicationId).orElseThrow().getLsp().getId();
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(30, TimeUnit.SECONDS));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private void initiate(UUID applicationId) throws Exception {
        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/disbursement-requests", applicationId)
                        .with(systemAdmin()))
                .andExpect(status().isOk());
    }

    private UUID seedApproved(String ifsc, BigDecimal requestedAmount) throws Exception {
        String lspId = createLspViaAdmin();
        String productId = createProductViaAdmin();
        mapProductToLsp(productId, lspId);
        String applicationId = createApplicationViaOps(lspId, productId, requestedAmount);
        transition(applicationId, "AWAITING_APPROVAL", "Ready for approval");
        markKycComplete(applicationId);
        transition(applicationId, "APPROVED_PENDING_DISBURSAL", "Approved for freeze test");
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
        payload.put("borrowerEmail", "t15+" + borrowerPan.toLowerCase() + "@example.com");
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
                            "Uploaded for freeze test",
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
