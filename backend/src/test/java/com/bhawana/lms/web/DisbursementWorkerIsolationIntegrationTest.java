package com.bhawana.lms.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bhawana.lms.domain.DisbursementIntent;
import com.bhawana.lms.domain.DisbursementIntentState;
import com.bhawana.lms.domain.LoanAccount;
import com.bhawana.lms.domain.LoanAccountStatus;
import com.bhawana.lms.domain.LoanApplication;
import com.bhawana.lms.domain.LoanApplicationDocumentChecklistStatus;
import com.bhawana.lms.domain.LoanApplicationStatus;
import com.bhawana.lms.repo.DisbursementIntentRepository;
import com.bhawana.lms.repo.LoanAccountRepository;
import com.bhawana.lms.repo.LoanApplicationDocumentChecklistRepository;
import com.bhawana.lms.repo.LoanApplicationRepository;
import com.bhawana.lms.repo.LoanApplicationAuditEventRepository;
import com.bhawana.lms.repo.LoanApplicationStatusTransitionRepository;
import com.bhawana.lms.repo.LoanDisbursementRequestLogRepository;
import com.bhawana.lms.service.DisbursementIntentWorkflowService;
import com.bhawana.lms.service.DisbursementPreflightValidator;
import com.bhawana.lms.service.LoanDisbursementAdapter;
import com.bhawana.lms.service.LoanDisbursementCommandService;
import com.bhawana.lms.service.LoanDisbursementWorkerProcessor;
import com.bhawana.lms.service.LoanDisbursementWorkerService;
import com.bhawana.lms.support.TenantContextTestExecutionListener;
import com.bhawana.lms.support.TestPanSequence;
import com.bhawana.lms.tenant.TenantScopedExecution;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
import org.springframework.test.web.servlet.MvcResult;

/**
 * A parked/conflicted loan must never abort a disbursement tick or starve
 * recovery. All tests run against the real PostgreSQL Testcontainers database (never
 * H2): failures below roll back real transactions, and the assertions prove later
 * eligible items plus expired-intent recovery still run and commit.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestExecutionListeners(
        value = TenantContextTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS
)
class DisbursementWorkerIsolationIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private LoanAccountRepository loanAccountRepository;
    @Autowired private LoanApplicationDocumentChecklistRepository loanApplicationDocumentChecklistRepository;
    @Autowired private LoanApplicationStatusTransitionRepository loanApplicationStatusTransitionRepository;
    @Autowired private LoanApplicationAuditEventRepository loanApplicationAuditEventRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DisbursementIntentWorkflowService disbursementIntentWorkflowService;
    @Autowired private LoanDisbursementWorkerService loanDisbursementWorkerService;
    @Autowired private MeterRegistry meterRegistry;

    @MockitoSpyBean
    private LoanApplicationRepository loanApplicationRepository;

    @MockitoSpyBean
    private DisbursementIntentRepository disbursementIntentRepository;

    @MockitoSpyBean
    private LoanDisbursementCommandService loanDisbursementCommandService;

    @MockitoSpyBean
    private LoanDisbursementRequestLogRepository loanDisbursementRequestLogRepository;

    @MockitoSpyBean
    private LoanDisbursementAdapter loanDisbursementAdapter;

    @MockitoSpyBean
    private DisbursementPreflightValidator disbursementPreflightValidator;

    @MockitoSpyBean
    private LoanDisbursementWorkerProcessor loanDisbursementWorkerProcessor;

    @BeforeEach
    void resetMocks() {
        reset(
                loanDisbursementAdapter,
                disbursementPreflightValidator,
                loanDisbursementWorkerProcessor,
                loanApplicationRepository,
                disbursementIntentRepository,
                loanDisbursementCommandService,
                loanDisbursementRequestLogRepository);
    }

    @Test
    void realRollbackOnFirstItemDoesNotStarveNeighborsOrExpiredRecovery() throws Exception {
        UUID poisonedId = seedApproved("HDFC0001234", new BigDecimal("45000.00"));
        UUID healthyA = seedApproved("HDFC0001234", new BigDecimal("45000.00"));
        UUID healthyB = seedApproved("HDFC0001234", new BigDecimal("45000.00"));
        // Expired-lease recovery candidate: a committed CREATED intent whose lease has
        // lapsed, claimed by the batch path in the same tick.
        UUID expiredId = seedApproved("HDFC0001234", new BigDecimal("45000.00"));
        loanDisbursementCommandService.initiateDisbursement(expiredId, "t01.setup");
        DisbursementIntent expiredIntent = disbursementIntentRepository
                .findLiveByLoanAccountId(loanAccountRepository.findByLoanApplication_Id(expiredId).orElseThrow().getId())
                .orElseThrow();
        expiredIntent.stampLease("t01-stale-owner", Instant.now().minusSeconds(3600));
        disbursementIntentRepository.save(expiredIntent);

        // Snapshots BEFORE the tick: seeding already wrote transitions/audits/events, so
        // any new row for the poisoned application after the tick proves leaked partial work.
        int transitionsBefore = loanApplicationStatusTransitionRepository
                .findTop20ByLoanApplication_IdOrderByCreatedAtDesc(poisonedId).size();
        int auditsBefore = loanApplicationAuditEventRepository
                .findTop25ByLoanApplication_IdOrderByCreatedAtDesc(poisonedId).size();
        long eventsBefore = loanEventsFor(poisonedId);

        // Failure AFTER real intent persistence inside the processor transaction: the spy
        // runs the REAL initiate (intent row saved, account marked REQUESTED in the Tx
        // persistence context) and only then throws. The throw joins the item's Tx, which
        // genuinely rolls back on real PostgreSQL — nothing is caught inside, so the
        // worker reports it after the transaction has ended.
        AtomicBoolean realInitiateCompleted = new AtomicBoolean(false);
        List<UUID> initiateOrder = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            UUID candidateId = invocation.getArgument(0);
            initiateOrder.add(candidateId);
            Object result = invocation.callRealMethod();
            if (poisonedId.equals(candidateId)) {
                realInitiateCompleted.set(true);
                throw new IllegalStateException("post-save rollback probe");
            }
            return result;
        }).when(loanDisbursementCommandService).initiateDisbursement(any(UUID.class), any());

        // Control scan order explicitly instead of assuming findByStatus order: snapshot
        // the pre-tick scan and serve the poisoned item FIRST, so committed neighbors
        // prove continuation after it. (doReturn snapshot: callRealMethod is unsupported
        // on interface spies; unstubbed calls still delegate to the real bean.)
        List<LoanApplication> approvedScan = new ArrayList<>(loanApplicationRepository
                .findByStatus(LoanApplicationStatus.APPROVED_PENDING_DISBURSAL));
        approvedScan.sort(Comparator.comparing(app -> !poisonedId.equals(app.getId())));
        doReturn(approvedScan).when(loanApplicationRepository)
                .findByStatus(eq(LoanApplicationStatus.APPROVED_PENDING_DISBURSAL));

        double failuresBefore = workerItemFailures();

        int processed = loanDisbursementWorkerService.processPendingDisbursements();

        // The poisoned item really ran first, its real initiate really completed, and the
        // failure was reported exactly once without aborting the tick.
        assertTrue(realInitiateCompleted.get(), "real initiate must complete before the injected throw");
        assertFalse(initiateOrder.isEmpty(), "expected initiate attempts during the tick");
        assertEquals(poisonedId, initiateOrder.get(0), "poisoned item must run first");
        assertTrue(initiateOrder.containsAll(List.of(healthyA, healthyB)),
                "later items must still attempt initiation after the rollback");
        assertEquals(1.0, workerItemFailures() - failuresBefore,
                "exactly the poisoned item must increment the failure counter");

        // Neighbors (2x worker scan) plus expired-intent recovery (1x claimable) commit.
        assertTrue(processed >= 3, "expected at least 3 processed, got " + processed);
        assertEquals(LoanApplicationStatus.DISBURSED,
                loanApplicationRepository.findById(healthyA).orElseThrow().getStatus());
        assertEquals(LoanApplicationStatus.DISBURSED,
                loanApplicationRepository.findById(healthyB).orElseThrow().getStatus());
        assertEquals(LoanApplicationStatus.DISBURSED,
                loanApplicationRepository.findById(expiredId).orElseThrow().getStatus());

        // Full rollback of the persisted partial work: intent row, account mutation,
        // request logs, and zero new transition/audit/event rows.
        assertEquals(LoanApplicationStatus.APPROVED_PENDING_DISBURSAL,
                loanApplicationRepository.findById(poisonedId).orElseThrow().getStatus());
        LoanAccount poisonedAccount = loanAccountRepository.findByLoanApplication_Id(poisonedId).orElseThrow();
        assertEquals(LoanAccountStatus.PENDING_DISBURSEMENT, poisonedAccount.getStatus());
        assertEquals(0, intentsForAccount(poisonedAccount.getId()));
        assertEquals(0, loanDisbursementRequestLogRepository.countByLoanAccount_Id(poisonedAccount.getId()));
        assertEquals(transitionsBefore, loanApplicationStatusTransitionRepository
                .findTop20ByLoanApplication_IdOrderByCreatedAtDesc(poisonedId).size());
        assertEquals(auditsBefore, loanApplicationAuditEventRepository
                .findTop25ByLoanApplication_IdOrderByCreatedAtDesc(poisonedId).size());
        assertEquals(eventsBefore, loanEventsFor(poisonedId));

        // Isolation cleanup (owned row only): resolve the poisoned item through the
        // production path so no eligible fixture leaks into later suites.
        reset(loanDisbursementCommandService, loanApplicationRepository);
        assertTrue(loanDisbursementWorkerService.processApplication(poisonedId));
        assertEquals(LoanApplicationStatus.DISBURSED,
                loanApplicationRepository.findById(poisonedId).orElseThrow().getStatus());
    }

    @Test
    void scanFailureInOnePhaseDoesNotStopOtherPhases() throws Exception {
        UUID recoveryId = seedApproved("HDFC0001234", new BigDecimal("45000.00"));
        loanDisbursementCommandService.initiateDisbursement(recoveryId, "t01.setup");
        DisbursementIntent recoveryIntent = disbursementIntentRepository
                .findLiveByLoanAccountId(loanAccountRepository.findByLoanApplication_Id(recoveryId).orElseThrow().getId())
                .orElseThrow();
        recoveryIntent.stampLease("t01-stale-owner", Instant.now().minusSeconds(3600));
        disbursementIntentRepository.save(recoveryIntent);

        // The APPROVED scan fails outright; the RETRY scan plus claimable and repair
        // recovery must still run and commit in the same tick.
        doThrow(new IllegalStateException("scan probe"))
                .when(loanApplicationRepository)
                .findByStatus(eq(LoanApplicationStatus.APPROVED_PENDING_DISBURSAL));

        double scanBefore = workerScanFailures();
        double itemBefore = workerItemFailures();

        int processed = loanDisbursementWorkerService.processPendingDisbursements();

        assertEquals(1.0, workerScanFailures() - scanBefore,
                "the failed scan must increment the scan counter exactly once");
        assertEquals(0.0, workerItemFailures() - itemBefore,
                "a scan failure is not a per-item failure");
        assertTrue(processed >= 1, "expired-intent recovery must still commit, got " + processed);
        assertEquals(LoanApplicationStatus.DISBURSED,
                loanApplicationRepository.findById(recoveryId).orElseThrow().getStatus());
    }

    @Test
    void workflowClaimPerItemFailureDoesNotStarveBatch() throws Exception {
        UUID probeFailedId = seedApproved("HDFC0001234", new BigDecimal("45000.00"));
        UUID unknownId = seedApproved("HDFC0001234", new BigDecimal("45000.00"));
        UUID healthyId = seedApproved("HDFC0001234", new BigDecimal("45000.00"));
        loanDisbursementCommandService.initiateDisbursement(probeFailedId, "t01.setup");
        loanDisbursementCommandService.initiateDisbursement(unknownId, "t01.setup");
        loanDisbursementCommandService.initiateDisbursement(healthyId, "t01.setup");
        UUID probeIntentId = liveIntentIdFor(probeFailedId);
        String unknownRef = liveIntentRefFor(unknownId);

        // Per-item failure inside the claim batch (outside the provider-call guard):
        // the probe read itself throws for one intent while the rest of the batch runs.
        // Targeted doThrow: unstubbed ids delegate to the real bean by default.
        doThrow(new IllegalStateException("claim probe failure"))
                .when(disbursementIntentRepository).findDetailedById(eq(probeIntentId));

        // Provider-side failure for another intent: persisted as UNKNOWN in its own fresh
        // transaction and still reported (not counted as a batch failure).
        doThrow(new IllegalStateException("provider probe failure"))
                .when(loanDisbursementAdapter).requestDisbursement(
                        argThat(command -> command != null && unknownRef.equals(command.tranRefNo())));

        double claimBefore = workflowFailures("lms.disbursement.workflow.claim.failures", "item");

        List<UUID> executed = disbursementIntentWorkflowService.executeClaimableIntents();

        assertEquals(1.0, workflowFailures("lms.disbursement.workflow.claim.failures", "item") - claimBefore,
                "exactly the probe-failed item must increment the claim failure counter");
        assertTrue(executed.containsAll(List.of(unknownId, healthyId)),
                "provider-failed (UNKNOWN) and healthy intents must still execute");
        assertFalse(executed.contains(probeFailedId), "probe-failed intent must contribute zero");
        assertEquals(DisbursementIntentState.CREATED,
                disbursementIntentRepository.findById(probeIntentId).orElseThrow().getState());
        assertEquals(DisbursementIntentState.UNKNOWN,
                disbursementIntentRepository.findById(liveIntentIdFor(unknownId)).orElseThrow().getState());
        assertEquals(LoanApplicationStatus.DISBURSED,
                loanApplicationRepository.findById(healthyId).orElseThrow().getStatus());

        // Isolation cleanup (owned rows only): resolve the probe-failed CREATED intent
        // via the claim batch and the UNKNOWN intent via normal status polling, so no
        // eligible fixture leaks into later suites. The failed claim holds a fresh lease,
        // so expire it first to make it claimable again.
        reset(disbursementIntentRepository, loanDisbursementAdapter);
        DisbursementIntent probeIntent =
                disbursementIntentRepository.findById(probeIntentId).orElseThrow();
        probeIntent.stampLease("t01-stale-owner", Instant.now().minusSeconds(3600));
        disbursementIntentRepository.save(probeIntent);
        disbursementIntentWorkflowService.executeClaimableIntents();
        loanDisbursementWorkerService.processPendingStatusChecks();
        loanDisbursementWorkerService.processPendingStatusChecks();
        assertEquals(LoanApplicationStatus.DISBURSED,
                loanApplicationRepository.findById(probeFailedId).orElseThrow().getStatus());
        assertEquals(LoanApplicationStatus.DISBURSED,
                loanApplicationRepository.findById(unknownId).orElseThrow().getStatus());
    }

    @Test
    void workflowRepairPerItemFailureDoesNotStarveBatch() throws Exception {
        UUID failedId = seedApproved("HDFC0001234", new BigDecimal("45000.00"));
        UUID repairedId = seedApproved("HDFC0001234", new BigDecimal("45000.00"));
        strandTerminalIntent(failedId);
        strandTerminalIntent(repairedId);
        UUID failedAccountId =
                loanAccountRepository.findByLoanApplication_Id(failedId).orElseThrow().getId();

        // Per-item failure inside the repair batch: the latest-request read itself
        // throws for one stranded intent while the other still repairs and commits.
        // Targeted doThrow: unstubbed accounts delegate to the real bean by default.
        doThrow(new IllegalStateException("repair probe failure"))
                .when(loanDisbursementRequestLogRepository)
                .findTopByLoanAccount_IdOrderByCreatedAtDesc(eq(failedAccountId));

        double repairBefore = workflowFailures("lms.disbursement.workflow.repair.failures", "item");

        int repaired = loanDisbursementWorkerService.processStrandedTerminalResults();

        assertEquals(1.0, workflowFailures("lms.disbursement.workflow.repair.failures", "item") - repairBefore,
                "exactly the probe-failed repair must increment the repair failure counter");
        assertTrue(repaired >= 1, "the healthy stranded intent must still repair, got " + repaired);
        assertEquals(LoanAccountStatus.DISBURSEMENT_REQUESTED,
                loanAccountRepository.findByLoanApplication_Id(failedId).orElseThrow().getStatus());
        assertEquals(LoanAccountStatus.DISBURSED,
                loanAccountRepository.findByLoanApplication_Id(repairedId).orElseThrow().getStatus());

        // Isolation cleanup (owned row only): with the probe stub restored, recover the
        // failed stranded item through the production repair path so no stranded fixture
        // leaks into later suites (e.g. the exact repair-count assertion).
        reset(loanDisbursementRequestLogRepository);
        loanDisbursementWorkerService.processStrandedTerminalResults();
        assertEquals(LoanAccountStatus.DISBURSED,
                loanAccountRepository.findByLoanApplication_Id(failedId).orElseThrow().getStatus());
    }

    @Test
    void repeatedParkedProcessingCreatesZeroNewReferences() throws Exception {
        UUID parkedId = seedApproved("MOCK0STUCK0", new BigDecimal("45000.00"));
        loanDisbursementCommandService.initiateDisbursement(parkedId, "t01.setup");
        disbursementIntentWorkflowService.executeForApplication(parkedId);
        loanDisbursementWorkerService.processPendingStatusChecks();
        loanDisbursementWorkerService.processPendingStatusChecks();

        LoanAccount parked = loanAccountRepository.findByLoanApplication_Id(parkedId).orElseThrow();
        assertEquals(LoanAccountStatus.DISBURSEMENT_PENDING_RECONCILIATION, parked.getStatus());
        assertEquals(1, intentsForAccount(parked.getId()));
        // Parking moves the application to DISBURSEMENT_RETRY while the account holds the
        // uncertain-money state; snapshot both — repeated ticks must change neither.
        LoanApplicationStatus parkedAppStatus =
                loanApplicationRepository.findById(parkedId).orElseThrow().getStatus();
        long logsAfterPark = loanDisbursementRequestLogRepository.countByLoanAccount_Id(parked.getId());
        int providerCallsAfterPark = providerRequestCount();
        double failuresBefore = workerItemFailures();

        loanDisbursementWorkerService.processPendingDisbursements();
        loanDisbursementWorkerService.processPendingDisbursements();
        loanDisbursementWorkerService.processPendingDisbursements();

        // Parked invariant: zero new references, zero new bank calls, zero failures —
        // skips are not failures and never re-initiate.
        LoanAccount stillParked = loanAccountRepository.findByLoanApplication_Id(parkedId).orElseThrow();
        assertEquals(LoanAccountStatus.DISBURSEMENT_PENDING_RECONCILIATION, stillParked.getStatus());
        assertEquals(1, intentsForAccount(stillParked.getId()));
        assertEquals(logsAfterPark, loanDisbursementRequestLogRepository.countByLoanAccount_Id(stillParked.getId()));
        assertEquals(providerCallsAfterPark, providerRequestCount());
        assertEquals(parkedAppStatus,
                loanApplicationRepository.findById(parkedId).orElseThrow().getStatus());
        assertEquals(0.0, workerItemFailures() - failuresBefore,
                "parked skips must not increment the failure counter");
    }

    @Test
    void rejectPreflightRacingCommittedIntentDoesNotReject() throws Exception {
        UUID raceId = seedApproved("HDFC0001234", new BigDecimal("45000.00"));
        AtomicBoolean armed = new AtomicBoolean(true);
        CountDownLatch workerEntered = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        // Barrier at processor entry — before any lock is acquired — so the concurrent
        // submission below commits fully while the worker is parked.
        doAnswer(invocation -> {
            if (armed.compareAndSet(true, false) && invocation.getArgument(0).equals(raceId)) {
                workerEntered.countDown();
                assertTrue(releaseWorker.await(30, TimeUnit.SECONDS), "race barrier timed out");
            }
            return invocation.callRealMethod();
        }).when(loanDisbursementWorkerProcessor).processApplication(eq(raceId));

        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<Boolean> workerResult;
        try {
            workerResult = executor.submit(() -> TenantScopedExecution.callAsAdmin(
                    () -> loanDisbursementWorkerService.processApplication(raceId)));
            assertTrue(workerEntered.await(30, TimeUnit.SECONDS), "worker never reached the entry barrier");
            // A concurrent submission commits while the worker is parked BEFORE it acquires
            // any lock. The worker's locked rechecks must then observe the live intent and
            // skip — never reject, never mint a second reference.
            loanDisbursementCommandService.initiateDisbursement(raceId, "t01.race");
        } finally {
            releaseWorker.countDown();
        }

        assertFalse(workerResult.get(60, TimeUnit.SECONDS), "raced worker must skip, not act");
        executor.shutdown();
        LoanAccount account = loanAccountRepository.findByLoanApplication_Id(raceId).orElseThrow();
        assertEquals(LoanAccountStatus.DISBURSEMENT_REQUESTED, account.getStatus());
        assertEquals(1, intentsForAccount(account.getId()));
        assertEquals(LoanApplicationStatus.APPROVED_PENDING_DISBURSAL,
                loanApplicationRepository.findById(raceId).orElseThrow().getStatus());

        // Isolation cleanup (owned row only): resolve the raced live intent through the
        // production path so no claimable fixture leaks into later suites.
        reset(loanDisbursementWorkerProcessor);
        disbursementIntentWorkflowService.executeForApplication(raceId);
        loanDisbursementCommandService.autoResolveAfterInitiate(raceId, "t01.cleanup", null, "t01-cleanup");
        assertEquals(LoanApplicationStatus.DISBURSED,
                loanApplicationRepository.findById(raceId).orElseThrow().getStatus());
    }

    // --- metrics ---

    private double workerItemFailures() {
        var counter = meterRegistry.find("lms.disbursement.worker.item.failures").counter();
        return counter == null ? 0.0 : counter.count();
    }

    private double workerScanFailures() {
        var counter = meterRegistry.find("lms.disbursement.worker.scan.failures").counter();
        return counter == null ? 0.0 : counter.count();
    }

    private double workflowFailures(String name, String scope) {
        var counter = meterRegistry.find(name).tag("scope", scope).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private long loanEventsFor(UUID applicationId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM loan_event WHERE loan_application_id = ?", Long.class, applicationId);
        return count == null ? 0L : count;
    }

    private UUID liveIntentIdFor(UUID applicationId) {
        LoanAccount account = loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow();
        return disbursementIntentRepository.findLiveByLoanAccountId(account.getId()).orElseThrow().getId();
    }

    private String liveIntentRefFor(UUID applicationId) {
        LoanAccount account = loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow();
        return disbursementIntentRepository.findLiveByLoanAccountId(account.getId()).orElseThrow().getTranRefNo();
    }

    /**
     * Seeds a genuinely stranded terminal intent through the public flow (initiate,
     * execute, resolve) and then re-opens the account, simulating the crash between the
     * terminal-intent commit and the outcome application that the repair path exists for.
     */
    private void strandTerminalIntent(UUID applicationId) throws Exception {
        loanDisbursementCommandService.initiateDisbursement(applicationId, "t01.setup");
        disbursementIntentWorkflowService.executeForApplication(applicationId);
        loanDisbursementCommandService.autoResolveAfterInitiate(applicationId, "t01.setup", null, "t01-strand");
        LoanAccount account = loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow();
        assertEquals(LoanAccountStatus.DISBURSED, account.getStatus());
        account.updateDisbursementStatus(LoanAccountStatus.DISBURSEMENT_REQUESTED, Instant.now());
        loanAccountRepository.save(account);
    }

    // --- helpers ---

    private long intentsForAccount(UUID accountId) {
        return disbursementIntentRepository.findAll().stream()
                .filter(intent -> intent.getLoanAccount().getId().equals(accountId))
                .count();
    }

    private int providerRequestCount() {
        return (int) org.mockito.Mockito.mockingDetails(loanDisbursementAdapter).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("requestDisbursement"))
                .count();
    }

    private UUID seedApproved(String ifsc, BigDecimal requestedAmount) throws Exception {
        String lspId = createLspViaAdmin();
        String productId = createProductViaAdmin();
        mapProductToLsp(productId, lspId);
        String applicationId = createApplicationViaOps(lspId, productId, requestedAmount);
        transition(applicationId, "AWAITING_APPROVAL", "Ready for approval");
        markKycComplete(applicationId);
        transition(applicationId, "APPROVED_PENDING_DISBURSAL", "Approved for isolation test");
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
                            "Uploaded for isolation test",
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
