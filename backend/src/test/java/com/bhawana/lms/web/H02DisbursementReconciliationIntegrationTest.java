package com.bhawana.lms.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bhawana.lms.common.api.error.ApiConflictException;
import com.bhawana.lms.domain.DisbursementDeclineKind;
import com.bhawana.lms.domain.DisbursementDisposition;
import com.bhawana.lms.domain.DisbursementIntent;
import com.bhawana.lms.domain.DisbursementIntentState;
import com.bhawana.lms.domain.DisbursementObservation;
import com.bhawana.lms.domain.DisbursementObservationKind;
import com.bhawana.lms.domain.DisbursementObservationProvenance;
import com.bhawana.lms.domain.DisbursementPaymentMode;
import com.bhawana.lms.domain.DisbursementReconciliationQueueEntry;
import com.bhawana.lms.domain.DisbursementReconciliationReason;
import com.bhawana.lms.domain.LoanAccount;
import com.bhawana.lms.domain.LoanAccountStatus;
import com.bhawana.lms.domain.LoanApplicationDocumentChecklistStatus;
import com.bhawana.lms.domain.LoanApplicationStatus;
import com.bhawana.lms.repo.DisbursementIntentRepository;
import com.bhawana.lms.repo.DisbursementObservationRepository;
import com.bhawana.lms.repo.DisbursementReconciliationQueueRepository;
import com.bhawana.lms.repo.LoanAccountRepository;
import com.bhawana.lms.repo.LoanApplicationDocumentChecklistRepository;
import com.bhawana.lms.repo.LoanApplicationRepository;
import com.bhawana.lms.repo.LoanApplicationStatusTransitionRepository;
import com.bhawana.lms.repo.LoanDisbursementRequestLogRepository;
import com.bhawana.lms.service.DisbursementIntentWorkflowService;
import com.bhawana.lms.service.DisbursementObservationWriter;
import com.bhawana.lms.service.DisbursementReconciliationService;
import com.bhawana.lms.service.LoanApplicationStatusWriter;
import com.bhawana.lms.service.LoanDisbursementAdapter;
import com.bhawana.lms.service.LoanDisbursementCommandService;
import com.bhawana.lms.support.TenantContextTestExecutionListener;
import com.bhawana.lms.support.TestPanSequence;
import com.bhawana.lms.support.IntegrationTestDatabaseCleaner;
import com.bhawana.lms.tenant.TenantScopedExecution;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
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

/**
 * H02 — immutable per-call observation evidence plus the explicit bounded reconciliation
 * queue, against real PostgreSQL. Covers: N polls produce N observations on the original
 * reference; timeouts/duplicates are preserved without regressing accepted outcomes;
 * evidence-free manual resolution is rejected; crash-rollback stays recoverable by the
 * original reference; replay and concurrent poll/manual apply once; exhausted polling keeps
 * polling the original reference to success (never re-initiates); legacy missing evidence
 * stays visible operator-only; and the queue API exposes counts plus oldest age.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestExecutionListeners(
        value = TenantContextTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS
)
class H02DisbursementReconciliationIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private LoanApplicationRepository loanApplicationRepository;
    @Autowired private LoanAccountRepository loanAccountRepository;
    @Autowired private LoanApplicationDocumentChecklistRepository loanApplicationDocumentChecklistRepository;
    @Autowired private DisbursementIntentRepository disbursementIntentRepository;
    @Autowired private LoanDisbursementRequestLogRepository loanDisbursementRequestLogRepository;
    @Autowired private DisbursementObservationRepository observationRepository;
    @Autowired private DisbursementReconciliationQueueRepository queueRepository;
    @Autowired private LoanApplicationStatusTransitionRepository loanApplicationStatusTransitionRepository;
    @Autowired private DisbursementIntentWorkflowService disbursementIntentWorkflowService;
    @Autowired private LoanDisbursementCommandService loanDisbursementCommandService;
    @Autowired private DisbursementReconciliationService reconciliationService;
    @Autowired private DisbursementObservationWriter observationWriter;
    @Autowired private com.bhawana.lms.service.DisbursementReconciliationProperties reconciliationProperties;
    @Autowired private org.springframework.transaction.support.TransactionTemplate transactionTemplate;
    @Autowired private IntegrationTestDatabaseCleaner databaseCleaner;

    @MockitoSpyBean
    private LoanDisbursementAdapter loanDisbursementAdapter;

    @MockitoSpyBean
    private LoanApplicationStatusWriter loanApplicationStatusWriter;

    @BeforeEach
    void setUp() {
        reset(loanDisbursementAdapter, loanApplicationStatusWriter);
        // Full clean: the reconciliation sweep discovers unresolved loans without queue rows,
        // so leftover REQUESTED/PARKED loans from any earlier test would otherwise join this
        // test's sweep and pollute queue assertions.
        databaseCleaner.cleanIntegrationTestData();
    }

    @AfterEach
    void tearDown() {
        reset(loanDisbursementAdapter, loanApplicationStatusWriter);
        databaseCleaner.cleanIntegrationTestData();
    }

    @Test
    void pollsProduceOneImmutableObservationEachOnTheOriginalReference() throws Exception {
        UUID applicationId = seedApproved("MOCK0PENDOK", new BigDecimal("45000.00"));
        initiate(applicationId);
        disbursementIntentWorkflowService.executeForApplication(applicationId);

        LoanAccount account = loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow();
        String ref = liveRef(account.getId());
        assertEquals(1L, observationRepository.countByLoanAccount_Id(account.getId()));

        // Test profile min-polls=1: first poll is not yet queryable, second resolves terminally.
        assertFalse(loanDisbursementCommandService.pollPendingDisbursement(applicationId, "worker", null, "h02-p1"));
        assertTrue(loanDisbursementCommandService.pollPendingDisbursement(applicationId, "worker", null, "h02-p2"));

        assertEquals(LoanAccountStatus.DISBURSED,
                loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow().getStatus());

        List<DisbursementObservation> observations =
                observationRepository.findTop50ByLoanAccount_IdOrderByObservedAtDesc(account.getId());
        assertEquals(3, observations.size());
        assertEquals(
                List.of(DisbursementObservationKind.POLL, DisbursementObservationKind.POLL,
                        DisbursementObservationKind.INITIATE),
                observations.stream().map(DisbursementObservation::getKind).toList());
        assertTrue(observations.stream().allMatch(o -> ref.equals(o.getTranRefNo())));
        assertTrue(observations.stream().allMatch(o -> !o.isDuplicate()));
        // No observation may invent instruction fields: all carry the frozen values.
        assertTrue(observations.stream().allMatch(o -> "MOCK0PENDOK".equals(o.getBeneficiaryIfsc())));
        // Terminal outcome accepted: the queue row for this loan is gone.
        assertTrue(queueRepository.findById(account.getId()).isEmpty());
    }

    @Test
    void timedOutPollIsPreservedAsUnresolvedEvidenceAndStillCounts() throws Exception {
        UUID applicationId = seedApproved("MOCK0PENDOK", new BigDecimal("45000.00"));
        initiate(applicationId);
        disbursementIntentWorkflowService.executeForApplication(applicationId);
        LoanAccount account = loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow();
        String ref = liveRef(account.getId());

        doThrow(new RuntimeException("simulated provider timeout"))
                .when(loanDisbursementAdapter).checkStatus(any());
        assertFalse(loanDisbursementCommandService.pollPendingDisbursement(applicationId, "worker", null, "h02-t1"));

        // Attempted-call provenance survived: count bumped pre-call, observation stored unresolved.
        assertEquals(1, loanDisbursementRequestLogRepository
                .findTopByLoanAccount_IdOrderByCreatedAtDesc(account.getId()).orElseThrow().getStatusCheckCount());
        List<DisbursementObservation> afterTimeout =
                observationRepository.findTop50ByLoanAccount_IdOrderByObservedAtDesc(account.getId());
        assertEquals(2, afterTimeout.size());
        DisbursementObservation timeout = afterTimeout.get(0);
        assertEquals(DisbursementObservationKind.POLL, timeout.getKind());
        assertEquals(DisbursementDisposition.PENDING, timeout.getDisposition());
        assertFalse(timeout.isQueryResolved());
        assertFalse(timeout.isDuplicate());
        assertEquals(ref, timeout.getTranRefNo());
        assertEquals(LoanAccountStatus.DISBURSEMENT_REQUESTED,
                loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow().getStatus());
        assertEquals(DisbursementReconciliationReason.REQUESTED,
                queueRepository.findById(account.getId()).orElseThrow().getReason());

        // Recovery re-polls the same reference exactly once more (prior count 1 is queryable).
        Mockito.doCallRealMethod().when(loanDisbursementAdapter).checkStatus(any());
        assertTrue(loanDisbursementCommandService.pollPendingDisbursement(applicationId, "worker", null, "h02-t2"));
        assertEquals(LoanAccountStatus.DISBURSED,
                loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow().getStatus());
        assertEquals(3L, observationRepository.countByLoanAccount_Id(account.getId()));
    }

    @Test
    void failedLocalApplyRollsBackObservationAndStaysRecoverableByOriginalReference() throws Exception {
        UUID applicationId = seedApproved("MOCK0SUCCSS", new BigDecimal("45000.00"));
        initiate(applicationId);

        doThrow(new IllegalStateException("simulated crash before result commit"))
                .when(loanApplicationStatusWriter).updateStatus(any(), any());
        try {
            disbursementIntentWorkflowService.executeForApplication(applicationId);
        } catch (IllegalStateException expected) {
            assertEquals("simulated crash before result commit", expected.getMessage());
        }

        // Observed result + accepted outcome are atomic: both rolled back together. The
        // pre-call prepare (REQUESTED intent + stored request placeholder) stays committed —
        // that durable identity is exactly what recovery reuses.
        LoanAccount account = loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow();
        assertEquals(LoanAccountStatus.DISBURSEMENT_REQUESTED, account.getStatus());
        assertEquals(DisbursementIntentState.REQUESTED,
                disbursementIntentRepository.findLiveByLoanAccountId(account.getId()).orElseThrow().getState());
        assertEquals(0L, observationRepository.countByLoanAccount_Id(account.getId()));
        String ref = liveRef(account.getId());

        // Recovery polls the ORIGINAL reference — the crashed submission is never re-sent.
        // The stub stands in for the bank's terminal status answer on that same reference.
        Mockito.doCallRealMethod().when(loanApplicationStatusWriter).updateStatus(any(), any());
        doAnswer(invocation -> new LoanDisbursementAdapter.DisbursementStatusResult(
                        "0", "Check Transaction Successful",
                        DisbursementDisposition.SUCCESS, DisbursementDeclineKind.NONE,
                        "0", "RRN-H02-CRASH-RECOVER", "recovered terminal success",
                        "{\"disposition\":\"SUCCESS\"}"))
                .when(loanDisbursementAdapter).checkStatus(any());
        assertTrue(loanDisbursementCommandService.pollPendingDisbursement(
                applicationId, "worker", null, "h02-crash-recover"));
        assertEquals(LoanAccountStatus.DISBURSED,
                loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow().getStatus());
        assertEquals(ref, jdbcTemplate.queryForObject(
                "SELECT tran_ref_no FROM disbursement_intent WHERE loan_account_id = ?", String.class,
                account.getId()));
        verify(loanDisbursementAdapter, times(1)).requestDisbursement(any());
        assertEquals(1L, observationRepository.countByLoanAccount_Id(account.getId()));
    }

    @Test
    void terminalDuplicatePollAndManualReplayApplyExactlyOnce() throws Exception {
        UUID applicationId = seedApproved("MOCK0PENDOK", new BigDecimal("45000.00"));
        initiate(applicationId);
        disbursementIntentWorkflowService.executeForApplication(applicationId);
        assertFalse(loanDisbursementCommandService.pollPendingDisbursement(applicationId, "worker", null, "h02-d1"));
        assertTrue(loanDisbursementCommandService.pollPendingDisbursement(applicationId, "worker", null, "h02-d2"));
        // Late poll after terminal: blocked with no provider call and no new observation.
        assertFalse(loanDisbursementCommandService.pollPendingDisbursement(applicationId, "worker", null, "h02-d3"));
        LoanAccount account = loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow();
        DisbursementObservation success = observationRepository
                .findTop50ByLoanAccount_IdOrderByObservedAtDesc(account.getId()).stream()
                .filter(o -> o.getDisposition() == DisbursementDisposition.SUCCESS && !o.isDuplicate())
                .findFirst().orElseThrow();
        long observations = observationRepository.countByLoanAccount_Id(account.getId());

        // Late poll after terminal: no provider call, no new observation, outcome preserved.
        reset(loanDisbursementAdapter);
        assertFalse(loanDisbursementCommandService.pollPendingDisbursement(applicationId, "worker", null, "h02-dup"));
        verify(loanDisbursementAdapter, times(0)).checkStatus(any());
        assertEquals(observations, observationRepository.countByLoanAccount_Id(account.getId()));

        // Manual replay of the same definitive evidence succeeds without a second application.
        assertTrue(reconciliationService.resolveManually(
                applicationId, success.getId(), "ops.admin", null, "h02-replay-1"));
        assertTrue(reconciliationService.resolveManually(
                applicationId, success.getId(), "ops.admin", null, "h02-replay-2"));
        assertEquals(1, loanApplicationStatusTransitionRepository
                .findByLoanApplication_IdAndToStatusOrderByCreatedAtAsc(applicationId, LoanApplicationStatus.DISBURSED)
                .size());
        assertEquals(observations, observationRepository.countByLoanAccount_Id(account.getId()));
    }

    @Test
    void concurrentPollAndManualResolutionDoNotDoubleApply() throws Exception {
        UUID applicationId = seedApproved("MOCK0PENDOK", new BigDecimal("45000.00"));
        initiate(applicationId);
        disbursementIntentWorkflowService.executeForApplication(applicationId);
        assertFalse(loanDisbursementCommandService.pollPendingDisbursement(applicationId, "worker", null, "h02-c1"));
        assertTrue(loanDisbursementCommandService.pollPendingDisbursement(applicationId, "worker", null, "h02-c2"));
        LoanAccount account = loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow();
        UUID successId = observationRepository.findTop50ByLoanAccount_IdOrderByObservedAtDesc(account.getId())
                .stream()
                .filter(o -> o.getDisposition() == DisbursementDisposition.SUCCESS && !o.isDuplicate())
                .findFirst().orElseThrow().getId();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // Pool threads carry no tenant context: enter admin scope inside each callable,
            // mirroring how the worker wraps its own execution.
            Future<Boolean> poll = pool.submit(() -> TenantScopedExecution.callAsAdmin(
                    () -> loanDisbursementCommandService.pollPendingDisbursement(
                            applicationId, "worker", null, "h02-conc-poll")));
            Future<Boolean> manual = pool.submit(() -> TenantScopedExecution.callAsAdmin(
                    () -> reconciliationService.resolveManually(
                            applicationId, successId, "ops.admin", null, "h02-conc-manual")));
            assertFalse(poll.get());
            assertTrue(manual.get());
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, loanApplicationStatusTransitionRepository
                .findByLoanApplication_IdAndToStatusOrderByCreatedAtAsc(applicationId, LoanApplicationStatus.DISBURSED)
                .size());
        assertEquals(LoanAccountStatus.DISBURSED,
                loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow().getStatus());
    }

    @Test
    void terminalDuplicateNeverClearsConflictingEvidenceQueue() throws Exception {
        UUID applicationId = seedApproved("MOCK0PENDOK", new BigDecimal("45000.00"));
        initiate(applicationId);
        disbursementIntentWorkflowService.executeForApplication(applicationId);
        assertFalse(loanDisbursementCommandService.pollPendingDisbursement(applicationId, "worker", null, "h02-q1"));
        assertTrue(loanDisbursementCommandService.pollPendingDisbursement(applicationId, "worker", null, "h02-q2"));
        LoanAccount account = loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow();
        String ref = jdbcTemplate.queryForObject(
                "SELECT tran_ref_no FROM disbursement_observation WHERE loan_account_id = ? "
                        + "AND disposition = 'SUCCESS' AND is_duplicate = false LIMIT 1",
                String.class, account.getId());

        // Operator holds conflicting evidence for this loan (queued inside a transaction,
        // like every production writer path).
        transactionTemplate.executeWithoutResult(tx -> observationWriter.enqueue(
                loanAccountRepository.findById(account.getId()).orElseThrow(), null, ref,
                DisbursementReconciliationReason.CONFLICTING_EVIDENCE, "H02 test conflict hold."));
        assertEquals(DisbursementReconciliationReason.CONFLICTING_EVIDENCE,
                queueRepository.findById(account.getId()).orElseThrow().getReason());

        // Late duplicate traffic (blocked poll, manual replay) must preserve the held entry.
        reset(loanDisbursementAdapter);
        assertFalse(loanDisbursementCommandService.pollPendingDisbursement(applicationId, "worker", null, "h02-qdup"));
        verify(loanDisbursementAdapter, times(0)).checkStatus(any());
        UUID successId = observationRepository.findTop50ByLoanAccount_IdOrderByObservedAtDesc(account.getId())
                .stream()
                .filter(o -> o.getDisposition() == DisbursementDisposition.SUCCESS && !o.isDuplicate())
                .findFirst().orElseThrow().getId();
        assertTrue(reconciliationService.resolveManually(
                applicationId, successId, "ops.admin", null, "h02-qreplay"));
        assertEquals(DisbursementReconciliationReason.CONFLICTING_EVIDENCE,
                queueRepository.findById(account.getId()).orElseThrow().getReason());
    }

    @Test
    void evidenceFreeManualResolutionIsRejected() throws Exception {
        UUID applicationId = seedApproved("MOCK0PENDOK", new BigDecimal("45000.00"));
        initiate(applicationId);
        doThrow(new RuntimeException("simulated initiate timeout"))
                .when(loanDisbursementAdapter).requestDisbursement(any());
        disbursementIntentWorkflowService.executeForApplication(applicationId);
        Mockito.doCallRealMethod().when(loanDisbursementAdapter).requestDisbursement(any());

        LoanAccount account = loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow();
        assertEquals(DisbursementIntentState.UNKNOWN,
                disbursementIntentRepository.findLiveByLoanAccountId(account.getId()).orElseThrow().getState());
        DisbursementObservation unknownObservation = observationRepository
                .findTop50ByLoanAccount_IdOrderByObservedAtDesc(account.getId()).stream()
                .findFirst().orElseThrow();

        // Unknown outcome: not definitive evidence.
        ApiConflictException notDefinitive = assertThrows(ApiConflictException.class, () ->
                reconciliationService.resolveManually(
                        applicationId, unknownObservation.getId(), "ops.admin", null, "h02-m1"));
        assertEquals("RECONCILIATION_EVIDENCE_NOT_DEFINITIVE", notDefinitive.getErrorCode());

        // Nothing stored under this id at all.
        ApiConflictException missing = assertThrows(ApiConflictException.class, () ->
                reconciliationService.resolveManually(
                        applicationId, UUID.randomUUID(), "ops.admin", null, "h02-m2"));
        assertEquals("RECONCILIATION_EVIDENCE_MISSING", missing.getErrorCode());

        // Privileged endpoint agrees: 409, never an invented outcome.
        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/disbursement-requests/reconcile",
                        applicationId)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "observationId", UUID.randomUUID().toString()))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("RECONCILIATION_EVIDENCE_MISSING"));
        assertEquals(LoanAccountStatus.DISBURSEMENT_REQUESTED,
                loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow().getStatus());
    }

    @Test
    void unknownLoanResolvesThroughEvidenceBackedManualApply() throws Exception {
        UUID applicationId = seedApproved("MOCK0PENDOK", new BigDecimal("45000.00"));
        initiate(applicationId);
        doThrow(new RuntimeException("simulated initiate timeout"))
                .when(loanDisbursementAdapter).requestDisbursement(any());
        disbursementIntentWorkflowService.executeForApplication(applicationId);
        Mockito.doCallRealMethod().when(loanDisbursementAdapter).requestDisbursement(any());
        LoanAccount account = loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow();
        DisbursementIntent intent =
                disbursementIntentRepository.findLiveByLoanAccountId(account.getId()).orElseThrow();

        // Stored definitive bank evidence arrives for the original reference/instruction.
        DisbursementObservation bankEvidence = observationRepository.save(new DisbursementObservation(
                loanAccountRepository.findById(account.getId()).orElseThrow(),
                null,
                intent.getTranRefNo(),
                intent.getAttemptCount(),
                null,
                DisbursementObservationKind.POLL,
                DisbursementDisposition.SUCCESS,
                true,
                false,
                "MOCK_ICICI",
                intent.getTranRefNo(),
                "0",
                "RRN-H02-MANUAL",
                DisbursementDeclineKind.NONE,
                intent.getBeneficiaryIfsc(),
                intent.getBeneficiaryAccountNumber(),
                intent.getPaymentMode(),
                "{\"tranRefNo\":\"" + intent.getTranRefNo() + "\"}",
                "{\"disposition\":\"SUCCESS\",\"BankRRN\":\"RRN-H02-MANUAL\"}",
                "h02-manual-apply",
                DisbursementObservationProvenance.LIVE,
                "bank.evidence"));

        assertTrue(reconciliationService.resolveManually(
                applicationId, bankEvidence.getId(), "ops.admin", null, "h02-manual-apply"));
        assertEquals(LoanAccountStatus.DISBURSED,
                loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow().getStatus());
        assertTrue(queueRepository.findById(account.getId()).isEmpty());

        // Same evidence via the privileged endpoint replays without a second application.
        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/disbursement-requests/reconcile",
                        applicationId)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "observationId", bankEvidence.getId().toString()))))
                .andExpect(status().isOk());
        assertEquals(1, loanApplicationStatusTransitionRepository
                .findByLoanApplication_IdAndToStatusOrderByCreatedAtAsc(applicationId, LoanApplicationStatus.DISBURSED)
                .size());
    }

    @Test
    void exhaustedPollKeepsPollingOriginalReferenceToSuccessWithoutReinitiation() throws Exception {
        UUID applicationId = seedApproved("MOCK0STUCK0", new BigDecimal("45000.00"));
        initiate(applicationId);
        disbursementIntentWorkflowService.executeForApplication(applicationId);
        LoanAccount account = loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow();
        String ref = liveRef(account.getId());

        // Test profile max-polls=2: two unresolved polls exhaust normal polling into PARKED.
        assertFalse(loanDisbursementCommandService.pollPendingDisbursement(applicationId, "worker", null, "h02-e1"));
        assertTrue(loanDisbursementCommandService.pollPendingDisbursement(applicationId, "worker", null, "h02-e2"));
        assertEquals(LoanAccountStatus.DISBURSEMENT_PENDING_RECONCILIATION,
                loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow().getStatus());
        DisbursementReconciliationQueueEntry parked =
                queueRepository.findById(account.getId()).orElseThrow();
        assertEquals(DisbursementReconciliationReason.PARKED, parked.getReason());
        assertEquals(ref, parked.getTranRefNo());
        java.time.Instant firstSeen = parked.getFirstSeenAt();
        verify(loanDisbursementAdapter, times(1)).requestDisbursement(any());

        // A reconciliation sweep re-polls the same reference: still pending, no new intent,
        // first_seen untouched, backoff advanced exactly once more. Force the entry due first
        // (parking armed the backoff), without touching first_seen.
        int pollsBefore = parked.getPollCount();
        jdbcTemplate.update(
                "UPDATE disbursement_reconciliation_queue SET next_poll_at = NOW() - INTERVAL '1 second' "
                        + "WHERE loan_account_id = ?",
                account.getId());
        reconciliationService.pollDueQueue("worker", null, "h02-esweep");
        DisbursementReconciliationQueueEntry afterSweep =
                queueRepository.findById(account.getId()).orElseThrow();
        assertEquals(DisbursementReconciliationReason.PARKED, afterSweep.getReason());
        assertEquals(ref, afterSweep.getTranRefNo());
        assertEquals(firstSeen, afterSweep.getFirstSeenAt());
        assertEquals(pollsBefore + 1, afterSweep.getPollCount());
        assertEquals(1L, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM disbursement_intent WHERE loan_account_id = ?", Long.class,
                account.getId()).longValue());
        verify(loanDisbursementAdapter, times(1)).requestDisbursement(any());
        assertEquals(LoanAccountStatus.DISBURSEMENT_PENDING_RECONCILIATION,
                loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow().getStatus());

        // The bank finally reports success for the ORIGINAL reference: one apply, queue drains.
        doAnswer(invocation -> new LoanDisbursementAdapter.DisbursementStatusResult(
                        "0", "Check Transaction Successful",
                        DisbursementDisposition.SUCCESS, DisbursementDeclineKind.NONE,
                        "0", "RRN-H02-EXHAUST", "recovered terminal success", "{\"disposition\":\"SUCCESS\"}"))
                .when(loanDisbursementAdapter).checkStatus(any());
        // Force the entry due again (backoff pushed it out) without touching first_seen.
        jdbcTemplate.update(
                "UPDATE disbursement_reconciliation_queue SET next_poll_at = NOW() - INTERVAL '1 second' "
                        + "WHERE loan_account_id = ?",
                account.getId());
        assertTrue(reconciliationService.pollDueQueue("worker", null, "h02-erecover") >= 1);
        assertEquals(LoanAccountStatus.DISBURSED,
                loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow().getStatus());
        assertTrue(queueRepository.findById(account.getId()).isEmpty());
        assertEquals(1L, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM disbursement_intent WHERE loan_account_id = ?", Long.class,
                account.getId()).longValue());
    }

    @Test
    void legacyMissingEvidenceStaysVisibleOperatorOnly() throws Exception {
        UUID applicationId = seedApproved("HDFC0001099", new BigDecimal("45000.00"));
        initiate(applicationId);
        LoanAccount account = loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow();
        // Legacy shape: intent row postdates the evidence; stored log lacks ref/rail/keys.
        disbursementIntentRepository.findLiveByLoanAccountId(account.getId())
                .ifPresent(disbursementIntentRepository::delete);
        jdbcTemplate.update(
                "INSERT INTO loan_disbursement_request_log (id, loan_account_id, actor_username, amount, "
                        + "provider_name, provider_request_id, provider_status, request_payload_json, "
                        + "response_payload_json, correlation_id) VALUES (?, ?, 'legacy.fixture', 44000.00, "
                        + "'MOCK_ICICI', 'REQ-LEGACY-1', 'PENDING', '{}', '{}', 'corr-legacy')",
                UUID.randomUUID(), account.getId());
        // Backdate the original evidence: discovery must age the queue row from when the money
        // moved, not from sweep time.
        java.time.Instant originalEvidence =
                java.time.Instant.parse("2023-11-05T10:00:00Z");
        jdbcTemplate.update(
                "UPDATE loan_disbursement_request_log SET created_at = ?, updated_at = ? WHERE loan_account_id = ?",
                java.sql.Timestamp.from(originalEvidence), java.sql.Timestamp.from(originalEvidence),
                account.getId());

        // Normal polling is blocked (no trustworthy instruction): zero provider calls.
        reset(loanDisbursementAdapter);
        assertFalse(loanDisbursementCommandService.pollPendingDisbursement(
                applicationId, "worker", null, "h02-leg"));
        verify(loanDisbursementAdapter, times(0)).checkStatus(any());

        // The reconciliation sweep queues it operator-only with a NULL reference — visible,
        // never fabricated.
        reconciliationService.pollDueQueue("worker", null, "h02-legsweep");
        DisbursementReconciliationQueueEntry entry =
                queueRepository.findById(account.getId()).orElseThrow();
        assertEquals(DisbursementReconciliationReason.LEGACY_MISMATCH, entry.getReason());
        assertNull(entry.getTranRefNo());
        assertEquals(originalEvidence, entry.getFirstSeenAt());

        mockMvc.perform(get("/api/v1/internal/ops/disbursement-reconciliation/queue/summary")
                        .with(systemAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.byReason.LEGACY_MISMATCH").value(1));
    }

    @Test
    void queueApiExposesCountsOldestAgeAndOperatorClaim() throws Exception {
        UUID stuckId = seedApproved("MOCK0STUCK0", new BigDecimal("45000.00"));
        initiate(stuckId);
        disbursementIntentWorkflowService.executeForApplication(stuckId);
        assertFalse(loanDisbursementCommandService.pollPendingDisbursement(stuckId, "worker", null, "h02-qe1"));
        assertTrue(loanDisbursementCommandService.pollPendingDisbursement(stuckId, "worker", null, "h02-qe2"));

        UUID unknownId = seedApproved("MOCK0PENDOK", new BigDecimal("45000.00"));
        initiate(unknownId);
        doThrow(new RuntimeException("simulated initiate timeout"))
                .when(loanDisbursementAdapter).requestDisbursement(any());
        disbursementIntentWorkflowService.executeForApplication(unknownId);
        Mockito.doCallRealMethod().when(loanDisbursementAdapter).requestDisbursement(any());

        mockMvc.perform(get("/api/v1/internal/ops/disbursement-reconciliation/queue/summary")
                        .with(systemAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.byReason.PARKED").value(1))
                .andExpect(jsonPath("$.byReason.UNKNOWN").value(1))
                .andExpect(jsonPath("$.oldestAgeSeconds").exists());

        mockMvc.perform(get("/api/v1/internal/ops/disbursement-reconciliation/queue")
                        .param("limit", "1")
                        .param("offset", "0")
                        .with(systemAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        LoanAccount stuck = loanAccountRepository.findByLoanApplication_Id(stuckId).orElseThrow();
        mockMvc.perform(post("/api/v1/internal/ops/disbursement-reconciliation/queue/{loanAccountId}/claim",
                        stuck.getId())
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("owner", "ops.owner"))))
                .andExpect(status().isOk());
        assertEquals("ops.owner", queueRepository.findById(stuck.getId()).orElseThrow().getOwner());

        mockMvc.perform(post("/api/v1/internal/ops/disbursement-reconciliation/queue/{loanAccountId}/claim",
                        stuck.getId())
                        .with(opsUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("owner", "ops.other"))))
                .andExpect(status().isForbidden());
    }

    // --- focused regression tests for review corrections ---

    @Test
    void fullConflictBatchDoesNotStarveRecoverableEntry() throws Exception {
        List<UUID> conflictAccountIds = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            UUID appId = seedApproved("MOCK0PENDOK", new BigDecimal("45000.00"));
            initiate(appId);
            disbursementIntentWorkflowService.executeForApplication(appId);
            assertFalse(loanDisbursementCommandService.pollPendingDisbursement(appId, "worker", null, "h02-cb" + i));
            assertTrue(loanDisbursementCommandService.pollPendingDisbursement(appId, "worker", null, "h02-cb" + i));
            LoanAccount done = loanAccountRepository.findByLoanApplication_Id(appId).orElseThrow();
            conflictAccountIds.add(done.getId());
            String doneRef = jdbcTemplate.queryForObject(
                    "SELECT tran_ref_no FROM disbursement_observation WHERE loan_account_id = ? "
                            + "AND disposition = 'SUCCESS' AND is_duplicate = false LIMIT 1",
                    String.class, done.getId());
            UUID doneId = done.getId();
            final int probeIndex = i;
            transactionTemplate.executeWithoutResult(tx -> observationWriter.enqueue(
                    loanAccountRepository.findById(doneId).orElseThrow(), null, doneRef,
                    DisbursementReconciliationReason.CONFLICTING_EVIDENCE,
                    "H02 starvation probe " + probeIndex + "."));
        }
        // One recoverable loan still in flight.
        UUID liveApp = seedApproved("MOCK0PENDOK", new BigDecimal("45000.00"));
        initiate(liveApp);
        disbursementIntentWorkflowService.executeForApplication(liveApp);
        LoanAccount live = loanAccountRepository.findByLoanApplication_Id(liveApp).orElseThrow();
        long liveObsBefore = observationRepository.countByLoanAccount_Id(live.getId());

        // Everything due (including every conflict): the sweep must still reach the live loan.
        List<UUID> allIds = new ArrayList<>(conflictAccountIds);
        allIds.add(live.getId());
        Map<UUID, Map<String, Object>> conflictBefore = new LinkedHashMap<>();
        for (UUID id : allIds) {
            jdbcTemplate.update(
                    "UPDATE disbursement_reconciliation_queue SET next_poll_at = NOW() - INTERVAL '1 second' "
                            + "WHERE loan_account_id = ?",
                    id);
        }
        for (UUID id : conflictAccountIds) {
            conflictBefore.put(id, jdbcTemplate.queryForMap(
                    "SELECT reason, tran_ref_no, details, next_poll_at FROM disbursement_reconciliation_queue "
                            + "WHERE loan_account_id = ?",
                    id));
        }
        reconciliationService.pollDueQueue("worker", null, "h02-cbsweep");

        assertEquals(liveObsBefore + 1, observationRepository.countByLoanAccount_Id(live.getId()));
        for (UUID id : conflictAccountIds) {
            Map<String, Object> after = jdbcTemplate.queryForMap(
                    "SELECT reason, tran_ref_no, details, next_poll_at FROM disbursement_reconciliation_queue "
                            + "WHERE loan_account_id = ?",
                    id);
            assertEquals(conflictBefore.get(id), after);
        }
    }

    @Test
    void minBatchClampKeepsDiscoveryAlive() throws Exception {
        UUID applicationId = seedApproved("MOCK0STUCK0", new BigDecimal("45000.00"));
        initiate(applicationId);
        disbursementIntentWorkflowService.executeForApplication(applicationId);
        assertFalse(loanDisbursementCommandService.pollPendingDisbursement(applicationId, "worker", null, "h02-b1"));
        assertTrue(loanDisbursementCommandService.pollPendingDisbursement(applicationId, "worker", null, "h02-b2"));
        LoanAccount account = loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow();
        long obsBefore = observationRepository.countByLoanAccount_Id(account.getId());
        jdbcTemplate.update(
                "UPDATE disbursement_reconciliation_queue SET next_poll_at = NOW() - INTERVAL '1 second' "
                        + "WHERE loan_account_id = ?",
                account.getId());

        // Never-queued legacy inventory that must not be silently hidden at min batch.
        UUID legacyApp = seedApproved("HDFC0001091", new BigDecimal("45000.00"));
        initiate(legacyApp);
        LoanAccount legacy =
                loanAccountRepository.findByLoanApplication_Id(legacyApp).orElseThrow();
        disbursementIntentRepository.findLiveByLoanAccountId(legacy.getId())
                .ifPresent(disbursementIntentRepository::delete);
        jdbcTemplate.update(
                "INSERT INTO loan_disbursement_request_log (id, loan_account_id, actor_username, amount, "
                        + "provider_name, provider_request_id, provider_status, request_payload_json, "
                        + "response_payload_json, correlation_id) VALUES (?, ?, 'legacy.fixture', 44000.00, "
                        + "'MOCK_ICICI', 'REQ-LEGACY-B1', 'PENDING', '{}', '{}', 'corr-legacy')",
                UUID.randomUUID(), legacy.getId());

        // A batch of one is clamped to two so the discovery slice can never be silenced.
        int configured = reconciliationProperties.getQueuePollBatchSize();
        reconciliationProperties.setQueuePollBatchSize(1);
        try {
            assertEquals(2, reconciliationProperties.getQueuePollBatchSize());
            reconciliationService.pollDueQueue("worker", null, "h02-b1sweep");
        } finally {
            reconciliationProperties.setQueuePollBatchSize(configured);
        }
        // The parked loan re-polled its original reference once more...
        assertEquals(obsBefore + 1, observationRepository.countByLoanAccount_Id(account.getId()));
        assertEquals(LoanAccountStatus.DISBURSEMENT_PENDING_RECONCILIATION,
                loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow().getStatus());
        // ...and the unqueued legacy account was discovered, not hidden.
        DisbursementReconciliationQueueEntry discovered =
                queueRepository.findById(legacy.getId()).orElseThrow();
        assertEquals(DisbursementReconciliationReason.LEGACY_MISMATCH, discovered.getReason());
        assertNull(discovered.getTranRefNo());
    }

    @Test
    void concurrentClaimsSerializeWithoutLosingRow() throws Exception {
        UUID applicationId = seedApproved("MOCK0STUCK0", new BigDecimal("45000.00"));
        initiate(applicationId);
        disbursementIntentWorkflowService.executeForApplication(applicationId);
        assertFalse(loanDisbursementCommandService.pollPendingDisbursement(applicationId, "worker", null, "h02-cc1"));
        assertTrue(loanDisbursementCommandService.pollPendingDisbursement(applicationId, "worker", null, "h02-cc2"));
        LoanAccount account = loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow();

        List<String> owners = List.of("owner-a", "owner-b", "owner-c", "owner-d");
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (String owner : owners) {
                futures.add(pool.submit(() -> TenantScopedExecution.callAsAdmin(() -> {
                    reconciliationService.claimEntry(account.getId(), owner);
                    return null;
                })));
            }
            for (Future<?> future : futures) {
                future.get(120, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        DisbursementReconciliationQueueEntry entry =
                queueRepository.findById(account.getId()).orElseThrow();
        assertTrue(owners.contains(entry.getOwner()));
        assertEquals(DisbursementReconciliationReason.PARKED, entry.getReason());
    }

    @Test
    void heldConflictSurvivesRefreshAndAcceptance() throws Exception {
        UUID applicationId = seedApproved("MOCK0PENDOK", new BigDecimal("45000.00"));
        initiate(applicationId);
        disbursementIntentWorkflowService.executeForApplication(applicationId);
        LoanAccount account = loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow();
        DisbursementIntent intent =
                disbursementIntentRepository.findLiveByLoanAccountId(account.getId()).orElseThrow();

        // Contradictory evidence first: wrong reference, same loan.
        DisbursementObservation wrongRef = observationRepository.save(new DisbursementObservation(
                loanAccountRepository.findById(account.getId()).orElseThrow(), null, "ICI-OTHER-1", 0, null,
                DisbursementObservationKind.POLL, DisbursementDisposition.SUCCESS, true, false,
                "MOCK_ICICI", "ICI-OTHER-1", "0", "RRN-H02-OTHER",
                DisbursementDeclineKind.NONE, intent.getBeneficiaryIfsc(),
                intent.getBeneficiaryAccountNumber(), intent.getPaymentMode(),
                "{\"tranRefNo\":\"ICI-OTHER-1\"}", "{\"disposition\":\"SUCCESS\"}", "h02-hold",
                DisbursementObservationProvenance.LIVE, "bank.evidence"));
        ApiConflictException rejected = assertThrows(ApiConflictException.class, () ->
                reconciliationService.resolveManually(
                        applicationId, wrongRef.getId(), "ops.admin", null, "h02-hold"));
        assertEquals("RECONCILIATION_REFERENCE_MISMATCH", rejected.getErrorCode());
        DisbursementReconciliationQueueEntry held =
                queueRepository.findById(account.getId()).orElseThrow();
        assertEquals(DisbursementReconciliationReason.CONFLICTING_EVIDENCE, held.getReason());
        String heldDetails = held.getDetails();
        String heldRef = held.getTranRefNo();

        // A later pending poll refreshes evidence but must not rewrite the held conflict.
        assertFalse(loanDisbursementCommandService.pollPendingDisbursement(applicationId, "worker", null, "h02-h1"));
        DisbursementReconciliationQueueEntry afterRefresh =
                queueRepository.findById(account.getId()).orElseThrow();
        assertEquals(DisbursementReconciliationReason.CONFLICTING_EVIDENCE, afterRefresh.getReason());
        assertEquals(heldRef, afterRefresh.getTranRefNo());
        assertEquals(heldDetails, afterRefresh.getDetails());

        // Genuine terminal acceptance still leaves the real conflict operator-visible, with
        // no dismissal path in this scope: the hold persists alongside the immutable trail.
        assertTrue(loanDisbursementCommandService.pollPendingDisbursement(applicationId, "worker", null, "h02-h2"));
        assertEquals(LoanAccountStatus.DISBURSED,
                loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow().getStatus());
        DisbursementReconciliationQueueEntry afterAccept =
                queueRepository.findById(account.getId()).orElseThrow();
        assertEquals(DisbursementReconciliationReason.CONFLICTING_EVIDENCE, afterAccept.getReason());
        assertEquals(heldRef, afterAccept.getTranRefNo());
        assertEquals(heldDetails, afterAccept.getDetails());
    }

    @Test
    void manualRejectsWhenStoredRequestContradictsLiveIntent() throws Exception {
        UUID applicationId = seedApproved("MOCK0PENDOK", new BigDecimal("45000.00"));
        initiate(applicationId);
        disbursementIntentWorkflowService.executeForApplication(applicationId);
        LoanAccount account = loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow();
        DisbursementIntent intent =
                disbursementIntentRepository.findLiveByLoanAccountId(account.getId()).orElseThrow();

        // Evidence matching the live intent exactly...
        DisbursementObservation bankEvidence = observationRepository.save(new DisbursementObservation(
                loanAccountRepository.findById(account.getId()).orElseThrow(), null,
                intent.getTranRefNo(), intent.getAttemptCount(), null,
                DisbursementObservationKind.POLL, DisbursementDisposition.SUCCESS, true, false,
                "MOCK_ICICI", intent.getTranRefNo(), "0", "RRN-H02-XCHECK",
                DisbursementDeclineKind.NONE, intent.getBeneficiaryIfsc(),
                intent.getBeneficiaryAccountNumber(), intent.getPaymentMode(),
                "{\"tranRefNo\":\"" + intent.getTranRefNo() + "\"}",
                "{\"disposition\":\"SUCCESS\"}", "h02-xcheck",
                DisbursementObservationProvenance.LIVE, "bank.evidence"));
        // ...but the original stored request now carries a different beneficiary: the manual
        // path must enforce the same conjunction as the poll loader and reject.
        String tamperedPayload = objectMapper.writeValueAsString(Map.of(
                "beneficiaryIfsc", "HDFC0000999",
                "beneficiaryAccountNumber", intent.getBeneficiaryAccountNumber(),
                "tranRefNo", intent.getTranRefNo(),
                "paymentMode", intent.getPaymentMode().name()));
        jdbcTemplate.update(
                "UPDATE loan_disbursement_request_log SET request_payload_json = ?::jsonb WHERE loan_account_id = ?",
                tamperedPayload, account.getId());

        ApiConflictException rejected = assertThrows(ApiConflictException.class, () ->
                reconciliationService.resolveManually(
                        applicationId, bankEvidence.getId(), "ops.admin", null, "h02-xcheck"));
        assertEquals("RECONCILIATION_REFERENCE_MISMATCH", rejected.getErrorCode());
        assertEquals(DisbursementReconciliationReason.CONFLICTING_EVIDENCE,
                queueRepository.findById(account.getId()).orElseThrow().getReason());
        assertEquals(LoanAccountStatus.DISBURSEMENT_REQUESTED,
                loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow().getStatus());
    }

    @Test
    void conflictingEvidenceSurvivesSweepOnTerminalAccount() throws Exception {
        UUID applicationId = seedApproved("MOCK0PENDOK", new BigDecimal("45000.00"));
        initiate(applicationId);
        disbursementIntentWorkflowService.executeForApplication(applicationId);
        assertFalse(loanDisbursementCommandService.pollPendingDisbursement(applicationId, "worker", null, "h02-s1"));
        assertTrue(loanDisbursementCommandService.pollPendingDisbursement(applicationId, "worker", null, "h02-s2"));
        LoanAccount account = loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow();
        String ref = jdbcTemplate.queryForObject(
                "SELECT tran_ref_no FROM disbursement_observation WHERE loan_account_id = ? "
                        + "AND disposition = 'SUCCESS' AND is_duplicate = false LIMIT 1",
                String.class, account.getId());

        transactionTemplate.executeWithoutResult(tx -> observationWriter.enqueue(
                loanAccountRepository.findById(account.getId()).orElseThrow(), null, ref,
                DisbursementReconciliationReason.CONFLICTING_EVIDENCE, "H02 sweep-retention probe."));

        // The sweep must neither apply anything nor dismiss/overwrite the held conflict.
        reconciliationService.pollDueQueue("worker", null, "h02-ssweep");
        DisbursementReconciliationQueueEntry held =
                queueRepository.findById(account.getId()).orElseThrow();
        assertEquals(DisbursementReconciliationReason.CONFLICTING_EVIDENCE, held.getReason());
        assertEquals(ref, held.getTranRefNo());
        assertEquals(LoanAccountStatus.DISBURSED,
                loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow().getStatus());
        assertEquals(1, loanApplicationStatusTransitionRepository
                .findByLoanApplication_IdAndToStatusOrderByCreatedAtAsc(applicationId, LoanApplicationStatus.DISBURSED)
                .size());
    }

    @Test
    void contradictoryAndPendingReplaysOnResolvedLoanAreRejected() throws Exception {
        UUID applicationId = seedApproved("MOCK0PENDOK", new BigDecimal("45000.00"));
        initiate(applicationId);
        disbursementIntentWorkflowService.executeForApplication(applicationId);
        LoanAccount account = loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow();
        DisbursementIntent intent =
                disbursementIntentRepository.findLiveByLoanAccountId(account.getId()).orElseThrow();
        String ref = intent.getTranRefNo();
        String ifsc = intent.getBeneficiaryIfsc();
        String beneficiaryAccount = intent.getBeneficiaryAccountNumber();
        DisbursementPaymentMode mode = intent.getPaymentMode();
        assertFalse(loanDisbursementCommandService.pollPendingDisbursement(applicationId, "worker", null, "h02-r1"));
        assertTrue(loanDisbursementCommandService.pollPendingDisbursement(applicationId, "worker", null, "h02-r2"));

        // Contradictory terminal evidence (FAILED on a DISBURSED loan): rejected, stays visible.
        DisbursementObservation contradiction = observationRepository.save(new DisbursementObservation(
                loanAccountRepository.findById(account.getId()).orElseThrow(), null, ref, 0, null,
                DisbursementObservationKind.POLL, DisbursementDisposition.FAILED, true, false,
                "MOCK_ICICI", ref, "18", null, DisbursementDeclineKind.TECHNICAL,
                ifsc, beneficiaryAccount, mode,
                "{\"tranRefNo\":\"" + ref + "\"}",
                "{\"disposition\":\"FAILED\"}", "h02-contradiction",
                DisbursementObservationProvenance.LIVE, "bank.evidence"));
        ApiConflictException rejected = assertThrows(ApiConflictException.class, () ->
                reconciliationService.resolveManually(
                        applicationId, contradiction.getId(), "ops.admin", null, "h02-rcontra"));
        assertEquals("RECONCILIATION_REFERENCE_MISMATCH", rejected.getErrorCode());
        assertEquals(DisbursementReconciliationReason.CONFLICTING_EVIDENCE,
                queueRepository.findById(account.getId()).orElseThrow().getReason());

        // Pending evidence on a resolved loan: not definitive, rejected without touching the hold.
        DisbursementObservation pendingReplay = observationRepository.save(new DisbursementObservation(
                loanAccountRepository.findById(account.getId()).orElseThrow(), null, ref, 0, null,
                DisbursementObservationKind.POLL, DisbursementDisposition.PENDING, false, false,
                "MOCK_ICICI", ref, "11", null, DisbursementDeclineKind.NONE,
                ifsc, beneficiaryAccount, mode,
                "{\"tranRefNo\":\"" + ref + "\"}",
                "{\"disposition\":\"PENDING\"}", "h02-pending-replay",
                DisbursementObservationProvenance.LIVE, "bank.evidence"));
        ApiConflictException notDefinitive = assertThrows(ApiConflictException.class, () ->
                reconciliationService.resolveManually(
                        applicationId, pendingReplay.getId(), "ops.admin", null, "h02-rpending"));
        assertEquals("RECONCILIATION_EVIDENCE_NOT_DEFINITIVE", notDefinitive.getErrorCode());
        assertEquals(DisbursementReconciliationReason.CONFLICTING_EVIDENCE,
                queueRepository.findById(account.getId()).orElseThrow().getReason());
        assertEquals(LoanAccountStatus.DISBURSED,
                loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow().getStatus());
        assertEquals(1, loanApplicationStatusTransitionRepository
                .findByLoanApplication_IdAndToStatusOrderByCreatedAtAsc(applicationId, LoanApplicationStatus.DISBURSED)
                .size());
    }

    @Test
    void newerRequestDuringProviderCallIsRejectedAsStale() throws Exception {
        UUID applicationId = seedApproved("MOCK0PENDOK", new BigDecimal("45000.00"));
        initiate(applicationId);
        disbursementIntentWorkflowService.executeForApplication(applicationId);
        LoanAccount account = loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow();
        String capturedRef = liveRef(account.getId());
        UUID accountId = account.getId();
        BigDecimal principal = account.getPrincipalAmount();
        // Make the captured row strictly older so the mid-call insert is unambiguously newest.
        jdbcTemplate.update(
                "UPDATE loan_disbursement_request_log SET created_at = ?, updated_at = ? WHERE loan_account_id = ?",
                java.sql.Timestamp.from(java.time.Instant.parse("2024-01-05T10:00:00Z")),
                java.sql.Timestamp.from(java.time.Instant.parse("2024-01-05T10:00:00Z")), accountId);

        doAnswer(invocation -> {
            // A newer stored request appears while the provider call is in flight.
            transactionTemplate.executeWithoutResult(tx -> {
                LoanAccount locked = loanAccountRepository.findByIdForUpdate(accountId).orElseThrow();
                loanDisbursementRequestLogRepository.save(new com.bhawana.lms.domain.LoanDisbursementRequestLog(
                        locked, "concurrent.resubmit", principal, "MOCK_ICICI", "ICI-NEWER-1", "PENDING",
                        DisbursementPaymentMode.IMPS, "ICI-NEWER-1", "11", null,
                        DisbursementDeclineKind.NONE,
                        "{\"tranRefNo\":\"ICI-NEWER-1\",\"beneficiaryIfsc\":\"MOCK0PENDOK\","
                                + "\"beneficiaryAccountNumber\":\"123456789012\",\"paymentMode\":\"IMPS\"}",
                        "{\"disposition\":\"PENDING\"}", "h02-stale"));
            });
            return new LoanDisbursementAdapter.DisbursementStatusResult(
                    "0", "Check Transaction Successful",
                    DisbursementDisposition.SUCCESS, DisbursementDeclineKind.NONE,
                    "0", "RRN-H02-STALE", "late success for superseded capture", "{\"disposition\":\"SUCCESS\"}");
        }).when(loanDisbursementAdapter).checkStatus(any());

        assertFalse(loanDisbursementCommandService.pollPendingDisbursement(
                applicationId, "worker", null, "h02-stale"));
        // Stale identity: verdict kept as duplicate evidence, nothing applied, operator queued.
        assertEquals(LoanAccountStatus.DISBURSEMENT_REQUESTED,
                loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow().getStatus());
        List<DisbursementObservation> observations =
                observationRepository.findTop50ByLoanAccount_IdOrderByObservedAtDesc(accountId);
        assertTrue(observations.stream().anyMatch(o -> capturedRef.equals(o.getTranRefNo()) && o.isDuplicate()));
        DisbursementReconciliationQueueEntry queued =
                queueRepository.findById(accountId).orElseThrow();
        assertEquals(DisbursementReconciliationReason.LEGACY_MISMATCH, queued.getReason());
        assertEquals(capturedRef, queued.getTranRefNo());
    }

    @Test
    void strandedRepairTargetsOnlyQueuedAccount() throws Exception {
        List<UUID> applicationIds = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            applicationIds.add(seedApproved("MOCK0PENDOK", new BigDecimal("45000.00")));
        }
        List<UUID> accountIds = new ArrayList<>();
        for (UUID appId : applicationIds) {
            initiate(appId);
            disbursementIntentWorkflowService.executeForApplication(appId);
            LoanAccount account = loanAccountRepository.findByLoanApplication_Id(appId).orElseThrow();
            accountIds.add(account.getId());
            // Stored terminal evidence without the loan move (six unrelated + one target).
            DisbursementIntent stored = disbursementIntentRepository
                    .findLiveByLoanAccountId(account.getId()).orElseThrow();
            stored.recordProviderResponse(DisbursementIntentState.SUCCEEDED, stored.getTranRefNo(),
                    "0", "RRN-H02-STRANDED-" + accountIds.size(), DisbursementDeclineKind.NONE);
            disbursementIntentRepository.save(stored);
        }
        UUID targetAccountId = accountIds.get(0);
        // Queue every stranded loan, but only the target is due: the sweep must repair the
        // target alone, never the global first-N.
        transactionTemplate.executeWithoutResult(tx -> {
            for (UUID id : accountIds) {
                LoanAccount loaded = loanAccountRepository.findById(id).orElseThrow();
                observationWriter.enqueue(loaded, null,
                        jdbcTemplate.queryForObject(
                                "SELECT tran_ref_no FROM disbursement_intent WHERE loan_account_id = ? "
                                        + "AND state = 'SUCCEEDED' LIMIT 1", String.class, id),
                        DisbursementReconciliationReason.STRANDED_TERMINAL, "H02 targeting probe.");
            }
        });
        jdbcTemplate.update(
                "UPDATE disbursement_reconciliation_queue SET next_poll_at = NOW() - INTERVAL '1 second' "
                        + "WHERE loan_account_id = ?",
                targetAccountId);

        reconciliationService.pollDueQueue("worker", null, "h02-target");

        UUID targetApp = applicationIds.get(0);
        assertEquals(LoanAccountStatus.DISBURSED,
                loanAccountRepository.findByLoanApplication_Id(targetApp).orElseThrow().getStatus());
        assertTrue(queueRepository.findById(targetAccountId).isEmpty());
        for (int i = 1; i < applicationIds.size(); i++) {
            assertEquals(LoanAccountStatus.DISBURSEMENT_REQUESTED,
                    loanAccountRepository.findByLoanApplication_Id(applicationIds.get(i)).orElseThrow()
                            .getStatus(),
                    "unrelated stranded loan must stay untouched");
        }
    }

    @Test
    void concurrentPollsCarryDistinctIdentitiesAndApplyOnce() throws Exception {
        UUID applicationId = seedApproved("MOCK0PENDOK", new BigDecimal("45000.00"));
        initiate(applicationId);
        disbursementIntentWorkflowService.executeForApplication(applicationId);
        // Warmup poll consumes sequence 1 (unresolved); the race is over sequences 2 and 3.
        // Gate the provider call so both racers are in flight simultaneously: each claims its
        // durable sequence before either result commits, forcing the duplicate path on one side.
        assertFalse(loanDisbursementCommandService.pollPendingDisbursement(applicationId, "worker", null, "h02-w1"));
        LoanAccount account = loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow();
        CountDownLatch bothInCall = new CountDownLatch(2);
        doAnswer(invocation -> {
            bothInCall.countDown();
            if (!bothInCall.await(60, TimeUnit.SECONDS)) {
                throw new IllegalStateException("racers never met in the provider call");
            }
            return invocation.callRealMethod();
        }).when(loanDisbursementAdapter).checkStatus(any());

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = pool.submit(() -> TenantScopedExecution.callAsAdmin(() -> {
                ready.countDown();
                try {
                    go.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
                return loanDisbursementCommandService.pollPendingDisbursement(
                        applicationId, "worker", null, "h02-race-a");
            }));
            Future<Boolean> second = pool.submit(() -> TenantScopedExecution.callAsAdmin(() -> {
                ready.countDown();
                try {
                    go.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
                return loanDisbursementCommandService.pollPendingDisbursement(
                        applicationId, "worker", null, "h02-race-b");
            }));
            assertTrue(ready.await(30, TimeUnit.SECONDS));
            go.countDown();
            boolean a = first.get(120, TimeUnit.SECONDS);
            boolean b = second.get(120, TimeUnit.SECONDS);
            // Exactly one thread applies the terminal outcome; the other keeps duplicate evidence.
            assertTrue(a || b);
            assertFalse(a && b);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(LoanAccountStatus.DISBURSED,
                loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow().getStatus());
        List<Integer> pollSeqs = observationRepository.findTop50ByLoanAccount_IdOrderByObservedAtDesc(account.getId())
                .stream()
                .filter(o -> o.getKind() == DisbursementObservationKind.POLL)
                .map(DisbursementObservation::getPollSeq)
                .sorted()
                .toList();
        assertEquals(List.of(1, 2, 3), pollSeqs);
        assertEquals(1, loanApplicationStatusTransitionRepository
                .findByLoanApplication_IdAndToStatusOrderByCreatedAtAsc(applicationId, LoanApplicationStatus.DISBURSED)
                .size());
    }

    @Test
    void racingVerdictsRecordContradictionWithoutRegressingAcceptedOutcome() throws Exception {
        UUID applicationId = seedApproved("MOCK0PENDOK", new BigDecimal("45000.00"));
        initiate(applicationId);
        disbursementIntentWorkflowService.executeForApplication(applicationId);
        assertFalse(loanDisbursementCommandService.pollPendingDisbursement(applicationId, "worker", null, "h02-v1"));
        LoanAccount account = loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow();
        String ref = liveRef(account.getId());

        // Two polls race with opposite terminal answers; the loser must surface as conflicting
        // evidence while the winner's accepted outcome stands exactly once.
        CountDownLatch bothInCall = new CountDownLatch(2);
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(invocation -> {
            bothInCall.countDown();
            if (!bothInCall.await(60, TimeUnit.SECONDS)) {
                throw new IllegalStateException("racers never met in the provider call");
            }
            if (calls.incrementAndGet() == 1) {
                return invocation.callRealMethod();
            }
            return new LoanDisbursementAdapter.DisbursementStatusResult(
                    "0", "Check Transaction Successful",
                    DisbursementDisposition.FAILED, DisbursementDeclineKind.TECHNICAL,
                    "18", "RRN-H02-RACE", "late technical failure", "{\"disposition\":\"FAILED\"}");
        }).when(loanDisbursementAdapter).checkStatus(any());

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = pool.submit(() -> TenantScopedExecution.callAsAdmin(() -> {
                ready.countDown();
                try {
                    go.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
                return loanDisbursementCommandService.pollPendingDisbursement(
                        applicationId, "worker", null, "h02-race-v1");
            }));
            Future<Boolean> second = pool.submit(() -> TenantScopedExecution.callAsAdmin(() -> {
                ready.countDown();
                try {
                    go.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
                return loanDisbursementCommandService.pollPendingDisbursement(
                        applicationId, "worker", null, "h02-race-v2");
            }));
            assertTrue(ready.await(30, TimeUnit.SECONDS));
            go.countDown();
            boolean a = first.get(120, TimeUnit.SECONDS);
            boolean b = second.get(120, TimeUnit.SECONDS);
            assertTrue(a || b);
            assertFalse(a && b);
        } finally {
            pool.shutdownNow();
        }

        // Exactly one terminal outcome accepted; the contradictory duplicate is held visible.
        LoanAccountStatus finalStatus =
                loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow().getStatus();
        assertTrue(finalStatus == LoanAccountStatus.DISBURSED
                || finalStatus == LoanAccountStatus.DISBURSEMENT_FAILED);
        DisbursementReconciliationQueueEntry held =
                queueRepository.findById(account.getId()).orElseThrow();
        assertEquals(DisbursementReconciliationReason.CONFLICTING_EVIDENCE, held.getReason());
        assertEquals(ref, held.getTranRefNo());
        List<DisbursementObservation> observations =
                observationRepository.findTop50ByLoanAccount_IdOrderByObservedAtDesc(account.getId());
        assertTrue(observations.stream().anyMatch(o -> o.isDuplicate() && ref.equals(o.getTranRefNo())));
        assertTrue(observations.stream().allMatch(o -> ref.equals(o.getTranRefNo())));
    }

    @Test
    void crashedPollAttemptStaysVisibleAndRecoverySucceeds() throws Exception {
        UUID applicationId = seedApproved("MOCK0PENDOK", new BigDecimal("45000.00"));
        initiate(applicationId);
        disbursementIntentWorkflowService.executeForApplication(applicationId);
        LoanAccount account = loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow();
        String ref = liveRef(account.getId());
        // Warmup poll consumes sequence 1 (unresolved with min-polls=1); the crash lands on a
        // queryable sequence so the failure actually happens inside the atomic apply.
        assertFalse(loanDisbursementCommandService.pollPendingDisbursement(
                applicationId, "worker", null, "h02-gap-warm"));

        // Crash inside the atomic apply: the claimed sequence commits, the result does not.
        doThrow(new IllegalStateException("simulated crash inside poll apply"))
                .when(loanApplicationStatusWriter).updateStatus(any(), any());
        try {
            loanDisbursementCommandService.pollPendingDisbursement(applicationId, "worker", null, "h02-gap");
            org.junit.jupiter.api.Assertions.fail("expected the simulated crash to propagate");
        } catch (IllegalStateException expected) {
            assertEquals("simulated crash inside poll apply", expected.getMessage());
        }
        assertEquals(2, loanDisbursementRequestLogRepository
                .findTopByLoanAccount_IdOrderByCreatedAtDesc(account.getId()).orElseThrow().getStatusCheckCount());
        // No result row for sequence 2 — and no fabricated response in its place.
        assertEquals(List.of(2), reconciliationService.findMissingPollResults(account.getId()));
        assertEquals(LoanAccountStatus.DISBURSEMENT_REQUESTED,
                loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow().getStatus());

        // Recovery claims a fresh sequence and applies; the crashed gap stays visible forever.
        Mockito.doCallRealMethod().when(loanApplicationStatusWriter).updateStatus(any(), any());
        assertTrue(loanDisbursementCommandService.pollPendingDisbursement(
                applicationId, "worker", null, "h02-gap-recover"));
        assertEquals(LoanAccountStatus.DISBURSED,
                loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow().getStatus());
        List<DisbursementObservation> observations =
                observationRepository.findTop50ByLoanAccount_IdOrderByObservedAtDesc(account.getId());
        assertEquals(2, observations.stream().filter(o -> o.getKind() == DisbursementObservationKind.POLL).count());
        assertEquals(
                List.of(1, 3),
                observations.stream()
                        .filter(o -> o.getKind() == DisbursementObservationKind.POLL)
                        .map(DisbursementObservation::getPollSeq)
                        .sorted()
                        .toList());
        assertEquals(List.of(2), reconciliationService.findMissingPollResults(account.getId()));
        assertTrue(observations.stream().allMatch(o -> ref.equals(o.getTranRefNo())));
    }

    // --- helpers ---

    private void initiate(UUID applicationId) throws Exception {
        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/disbursement-requests", applicationId)
                        .with(systemAdmin()))
                .andExpect(status().isOk());
    }

    private String liveRef(UUID accountId) {
        return disbursementIntentRepository.findLiveByLoanAccountId(accountId).orElseThrow().getTranRefNo();
    }

    private UUID seedApproved(String ifsc, BigDecimal requestedAmount) throws Exception {
        String lspId = createLspViaAdmin();
        String productId = createProductViaAdmin();
        mapProductToLsp(productId, lspId);
        String applicationId = createApplicationViaOps(lspId, productId, requestedAmount);
        transition(applicationId, "AWAITING_APPROVAL", "Ready for approval");
        markKycComplete(applicationId);
        transition(applicationId, "APPROVED_PENDING_DISBURSAL", "Approved for H02 test");
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
                                "bankName", "H02 Bank",
                                "ifscCode", ifsc,
                                "accountHolderName", "H02 Borrower"
                        ))))
                .andExpect(status().isOk());
    }

    private String createLspViaAdmin() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/internal/admin/lsps")
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", "LSP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                                "name", "H02 LSP",
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
                                "name", "H02 product " + code,
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
        payload.put("borrowerFullName", "H02 Borrower");
        payload.put("borrowerMobile", mobileForPan(borrowerPan));
        payload.put("borrowerEmail", "h02+" + borrowerPan.toLowerCase() + "@example.com");
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
                            "Uploaded for H02 test",
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
