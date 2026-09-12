package com.bhawana.lms.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.bhawana.lms.domain.Borrower;
import com.bhawana.lms.domain.DisbursementDeclineKind;
import com.bhawana.lms.domain.DisbursementDisposition;
import com.bhawana.lms.domain.DisbursementIntent;
import com.bhawana.lms.domain.DisbursementIntentState;
import com.bhawana.lms.domain.DisbursementPaymentMode;
import com.bhawana.lms.domain.LoanAccount;
import com.bhawana.lms.domain.LoanAccountStatus;
import com.bhawana.lms.domain.LoanApplicationDocumentChecklistStatus;
import com.bhawana.lms.domain.LoanApplicationStatus;
import com.bhawana.lms.domain.LoanDisbursementRequestLog;
import com.bhawana.lms.repo.BorrowerRepository;
import com.bhawana.lms.repo.DisbursementIntentRepository;
import com.bhawana.lms.repo.LoanAccountRepository;
import com.bhawana.lms.repo.LoanApplicationDocumentChecklistRepository;
import com.bhawana.lms.repo.LoanApplicationRepository;
import com.bhawana.lms.repo.LoanDisbursementRequestLogRepository;
import com.bhawana.lms.service.DisbursementIntentWorkflowService;
import com.bhawana.lms.service.LoanDisbursementAdapter;
import com.bhawana.lms.service.LoanDisbursementCommandService;
import com.bhawana.lms.support.TenantContextTestExecutionListener;
import com.bhawana.lms.support.TestPanSequence;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Status polling reuses the frozen payment instruction (intent snapshot, or the
 * original stored request for legacy rows without an intent) — never the borrower's live IFSC.
 *
 * <p>The frozen photo is taken at intent creation. Direct repository mutation of the borrower is a
 * snapshot-isolation fixture; it is not presented as proof that sequential onboarding bypasses its
 * active-loan guard. V111 backfilled intents copied borrower data and invented references/modes, so
 * any intent/payload/column disagreement blocks automatic polling with a diagnostic.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestExecutionListeners(
        value = TenantContextTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS
)
class SnapshotPollingIntegrationTest {

    private static final String FROZEN_IFSC = "MOCK0PENDOK";
    private static final String MUTATED_IFSC = "MOCK0PENDFL";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private LoanApplicationRepository loanApplicationRepository;
    @Autowired private LoanAccountRepository loanAccountRepository;
    @Autowired private LoanApplicationDocumentChecklistRepository loanApplicationDocumentChecklistRepository;
    @Autowired private DisbursementIntentRepository disbursementIntentRepository;
    @Autowired private LoanDisbursementRequestLogRepository loanDisbursementRequestLogRepository;
    @Autowired private BorrowerRepository borrowerRepository;
    @Autowired private DisbursementIntentWorkflowService disbursementIntentWorkflowService;
    @Autowired private LoanDisbursementCommandService loanDisbursementCommandService;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @MockitoSpyBean
    private LoanDisbursementAdapter loanDisbursementAdapter;

    @BeforeEach
    void resetMocks() {
        reset(loanDisbursementAdapter);
    }

    /**
     * Evidence cleanup for legacy-shape fixtures (test databases only): the observation
     * trail is append-only (row deletes rejected), so truncate before removing intent rows.
     * No test assertion reads the trail; production cleanup paths are untouched.
     */
    private void truncateReconciliationEvidence() {
        jdbcTemplate.execute("TRUNCATE TABLE disbursement_reconciliation_queue");
        jdbcTemplate.execute("TRUNCATE TABLE disbursement_observation");
    }

    @Test
    void frozenInstructionSurvivesLiveBorrowerMutation() throws Exception {
        UUID applicationId = seedApproved(FROZEN_IFSC, new BigDecimal("45000.00"));
        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/disbursement-requests", applicationId)
                        .with(systemAdmin()))
                .andExpect(status().isOk());
        disbursementIntentWorkflowService.executeForApplication(applicationId);

        LoanAccount account = loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow();
        DisbursementIntent intent = disbursementIntentRepository.findLiveByLoanAccountId(account.getId()).orElseThrow();
        assertEquals(FROZEN_IFSC, intent.getBeneficiaryIfsc());
        String frozenRef = intent.getTranRefNo();
        DisbursementPaymentMode frozenMode = intent.getPaymentMode();

        // Snapshot-isolation fixture: mutate the live borrower row directly (bypasses the
        // service-level edit guard, as a concurrent onboarding merge race could).
        mutateBorrowerIfscDirect(applicationId, MUTATED_IFSC);
        assertEquals(MUTATED_IFSC, liveBorrowerIfsc(applicationId));

        // Test profile min-polls=1: first poll is not yet queryable, second resolves terminally.
        assertFalse(loanDisbursementCommandService.pollPendingDisbursement(applicationId, "worker", null, "t06-frozen-1"));
        assertEquals(LoanAccountStatus.DISBURSEMENT_REQUESTED,
                loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow().getStatus());
        assertTrue(loanDisbursementCommandService.pollPendingDisbursement(applicationId, "worker", null, "t06-frozen-2"));

        List<LoanDisbursementAdapter.DisbursementStatusQuery> queries = captureStatusQueries(2);
        assertEquals(2, queries.size());
        for (LoanDisbursementAdapter.DisbursementStatusQuery query : queries) {
            assertEquals(FROZEN_IFSC, query.beneficiaryIfsc());
            assertEquals(frozenRef, query.tranRefNo());
            assertEquals(frozenMode, query.paymentMode());
        }

        // Frozen PENDOK resolves to success despite the live PENDFL mutation.
        assertEquals(LoanAccountStatus.DISBURSED,
                loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow().getStatus());
        assertEquals(LoanApplicationStatus.DISBURSED,
                loanApplicationRepository.findById(applicationId).orElseThrow().getStatus());
    }

    @Test
    void happyPathPendingToSuccessUnchanged() throws Exception {
        UUID applicationId = seedApproved(FROZEN_IFSC, new BigDecimal("45000.00"));
        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/disbursement-requests", applicationId)
                        .with(systemAdmin()))
                .andExpect(status().isOk());
        disbursementIntentWorkflowService.executeForApplication(applicationId);

        DisbursementIntent intent = disbursementIntentRepository.findLiveByLoanAccountId(
                loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow().getId()).orElseThrow();

        assertFalse(loanDisbursementCommandService.pollPendingDisbursement(applicationId, "worker", null, "t06-happy-1"));
        assertTrue(loanDisbursementCommandService.pollPendingDisbursement(applicationId, "worker", null, "t06-happy-2"));

        List<LoanDisbursementAdapter.DisbursementStatusQuery> queries = captureStatusQueries(2);
        for (LoanDisbursementAdapter.DisbursementStatusQuery query : queries) {
            assertEquals(FROZEN_IFSC, query.beneficiaryIfsc());
            assertEquals(intent.getTranRefNo(), query.tranRefNo());
        }
        assertEquals(LoanAccountStatus.DISBURSED,
                loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow().getStatus());
    }

    @Test
    void legacyLogWithoutIntentUsesOriginalPayload() throws Exception {
        UUID applicationId = seedApproved(FROZEN_IFSC, new BigDecimal("45000.00"));
        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/disbursement-requests", applicationId)
                        .with(systemAdmin()))
                .andExpect(status().isOk());
        disbursementIntentWorkflowService.executeForApplication(applicationId);

        LoanAccount account = loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow();
        LoanDisbursementRequestLog originalLog = loanDisbursementRequestLogRepository
                .findTopByLoanAccount_IdOrderByCreatedAtDesc(account.getId()).orElseThrow();
        String originalRef = originalLog.getTranRefNo();
        DisbursementPaymentMode originalMode = originalLog.getPaymentMode();

        // Simulate a legacy pre-intent row: the intent never existed, only the stored request.
        // Evidence (test databases only): truncate the append-only trail first — the
        // trigger rejects row deletes, and the intent FK must not block legacy shaping.
        DisbursementIntent live = disbursementIntentRepository.findLiveByLoanAccountId(account.getId()).orElseThrow();
        truncateReconciliationEvidence();
        disbursementIntentRepository.delete(live);

        mutateBorrowerIfscDirect(applicationId, MUTATED_IFSC);

        assertTrue(disbursementIntentWorkflowService.loadStatusPollContext(applicationId).isPresent());
        assertFalse(loanDisbursementCommandService.pollPendingDisbursement(applicationId, "worker", null, "t06-legacy-1"));
        assertTrue(loanDisbursementCommandService.pollPendingDisbursement(applicationId, "worker", null, "t06-legacy-2"));

        List<LoanDisbursementAdapter.DisbursementStatusQuery> queries = captureStatusQueries(2);
        for (LoanDisbursementAdapter.DisbursementStatusQuery query : queries) {
            assertEquals(FROZEN_IFSC, query.beneficiaryIfsc());
            assertEquals(originalRef, query.tranRefNo());
            assertEquals(originalMode, query.paymentMode());
        }
        assertEquals(LoanAccountStatus.DISBURSED,
                loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow().getStatus());
    }

    @Test
    void v111BackfilledIntentConflictBlocksPolling() throws Exception {
        UUID applicationId = seedApproved(FROZEN_IFSC, new BigDecimal("45000.00"));
        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/disbursement-requests", applicationId)
                        .with(systemAdmin()))
                .andExpect(status().isOk());
        disbursementIntentWorkflowService.executeForApplication(applicationId);

        LoanAccount account = loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow();
        DisbursementIntent modern = disbursementIntentRepository.findLiveByLoanAccountId(account.getId()).orElseThrow();
        String originalRef = modern.getTranRefNo();
        DisbursementPaymentMode originalMode = modern.getPaymentMode();
        var amount = modern.getAmount();
        String createdBy = modern.getCreatedBy();
        String correlationId = modern.getCorrelationId();
        String beneficiaryName = modern.getBeneficiaryName();
        String beneficiaryAccount = modern.getBeneficiaryAccountNumber();
        int logCountBefore = loanDisbursementRequestLogRepository.findTop20ByLoanAccount_IdOrderByCreatedAtDesc(account.getId()).size();
        int statusChecksBefore = loanDisbursementRequestLogRepository
                .findTopByLoanAccount_IdOrderByCreatedAtDesc(account.getId()).orElseThrow().getStatusCheckCount();

        // Model the V111 migration shape: borrower changed after the original request, then the
        // backfill copied the *current* borrower IFSC into a new UNKNOWN intent while the stored
        // request still carries the original instruction. Same reference, conflicting beneficiary.
        mutateBorrowerIfscDirect(applicationId, MUTATED_IFSC);
        truncateReconciliationEvidence();
        disbursementIntentRepository.delete(modern);
        LoanAccount attachedAccount = loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow();
        DisbursementIntent backfilled = new DisbursementIntent(
                UUID.randomUUID(), attachedAccount, originalRef, amount, originalMode,
                beneficiaryName, beneficiaryAccount, MUTATED_IFSC, createdBy, correlationId);
        backfilled.recordProviderResponse(DisbursementIntentState.UNKNOWN, originalRef, null, null,
                DisbursementDeclineKind.NONE);
        disbursementIntentRepository.save(backfilled);

        List<String> diagnostics = captureWarns(() -> {
            assertTrue(disbursementIntentWorkflowService.loadStatusPollContext(applicationId).isEmpty());
            assertFalse(loanDisbursementCommandService.pollPendingDisbursement(applicationId, "worker", null, "t06-v111"));
        });
        assertTrue(diagnostics.stream().anyMatch(message -> message.contains("frozen_instruction_conflict")),
                "Expected a frozen_instruction_conflict diagnostic, got: " + diagnostics);

        verify(loanDisbursementAdapter, never()).checkStatus(any());
        // No poll-count increment, no new intent/reference, no outcome mutation.
        assertEquals(statusChecksBefore, loanDisbursementRequestLogRepository
                .findTopByLoanAccount_IdOrderByCreatedAtDesc(account.getId()).orElseThrow().getStatusCheckCount());
        assertEquals(logCountBefore, loanDisbursementRequestLogRepository
                .findTop20ByLoanAccount_IdOrderByCreatedAtDesc(account.getId()).size());
        assertEquals(LoanAccountStatus.DISBURSEMENT_REQUESTED,
                loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow().getStatus());
        DisbursementIntent stillBlocked = disbursementIntentRepository.findLiveByLoanAccountId(account.getId()).orElseThrow();
        assertEquals(DisbursementIntentState.UNKNOWN, stillBlocked.getState());
        assertEquals(MUTATED_IFSC, stillBlocked.getBeneficiaryIfsc());
    }

    @Test
    void conflictingLiveReferenceBlocksWithoutFallback() throws Exception {
        UUID applicationId = seedApproved(FROZEN_IFSC, new BigDecimal("45000.00"));
        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/disbursement-requests", applicationId)
                        .with(systemAdmin()))
                .andExpect(status().isOk());
        disbursementIntentWorkflowService.executeForApplication(applicationId);

        LoanAccount account = loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow();
        DisbursementIntent liveIntent = disbursementIntentRepository.findLiveByLoanAccountId(account.getId()).orElseThrow();
        String liveRef = liveIntent.getTranRefNo();
        LoanDisbursementRequestLog originalLog = loanDisbursementRequestLogRepository
                .findTopByLoanAccount_IdOrderByCreatedAtDesc(account.getId()).orElseThrow();

        // A newer stored request with a different reference arrives while the older live intent is
        // still present. The poll must not borrow the live intent's beneficiary for the new request,
        // nor mark the unrelated live intent with the new verdict.
        String divergentRef = liveRef + "-DIVERGENT";
        insertDivergentLatestLog(account, originalLog, divergentRef);
        LoanDisbursementRequestLog divergentLog = loanDisbursementRequestLogRepository
                .findTopByLoanAccount_IdOrderByCreatedAtDesc(account.getId()).orElseThrow();
        assertEquals(divergentRef, divergentLog.getTranRefNo());
        int divergentChecksBefore = divergentLog.getStatusCheckCount();

        List<String> diagnostics = captureWarns(() -> {
            assertTrue(disbursementIntentWorkflowService.loadStatusPollContext(applicationId).isEmpty());
            assertFalse(loanDisbursementCommandService.pollPendingDisbursement(applicationId, "worker", null, "t06-conflict-ref"));
        });
        assertTrue(diagnostics.stream().anyMatch(message -> message.contains("live_intent_reference_mismatch")),
                "Expected a live_intent_reference_mismatch diagnostic, got: " + diagnostics);
        verify(loanDisbursementAdapter, never()).checkStatus(any());
        assertEquals(divergentChecksBefore, loanDisbursementRequestLogRepository
                .findTopByLoanAccount_IdOrderByCreatedAtDesc(account.getId()).orElseThrow().getStatusCheckCount());
        assertEquals(liveRef, disbursementIntentRepository.findLiveByLoanAccountId(account.getId()).orElseThrow().getTranRefNo());
        assertEquals(LoanAccountStatus.DISBURSEMENT_REQUESTED,
                loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow().getStatus());
    }

    @Test
    void payloadIfscValidationBlocksPolling() throws Exception {
        UUID applicationId = seedApproved(FROZEN_IFSC, new BigDecimal("45000.00"));
        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/disbursement-requests", applicationId)
                        .with(systemAdmin()))
                .andExpect(status().isOk());
        disbursementIntentWorkflowService.executeForApplication(applicationId);

        LoanAccount account = loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow();
        // Isolate payload validation: no live intent, so the stored request is the only evidence.
        // Evidence (test databases only): see truncateReconciliationEvidence.
        disbursementIntentRepository.findLiveByLoanAccountId(account.getId())
                .ifPresent(intent -> {
                    truncateReconciliationEvidence();
                    disbursementIntentRepository.delete(intent);
                });
        LoanDisbursementRequestLog validBase = loanDisbursementRequestLogRepository
                .findTopByLoanAccount_IdOrderByCreatedAtDesc(account.getId()).orElseThrow();

        List<Map<String, Object>> badIfscOverrides = List.of(
                Map.of("__remove__beneficiaryIfsc", true),
                Map.of("beneficiaryIfsc", ""),
                Map.of("beneficiaryIfsc", "   ")
        );
        for (Map<String, Object> override : badIfscOverrides) {
            reset(loanDisbursementAdapter);
            // Each variant builds from the original valid payload so failures stay isolated.
            insertLatestLogWithPayload(account, validBase, override);
            List<String> diagnostics = captureWarns(() -> {
                assertTrue(disbursementIntentWorkflowService.loadStatusPollContext(applicationId).isEmpty(),
                        "Payload override should block polling: " + override);
                assertFalse(loanDisbursementCommandService.pollPendingDisbursement(applicationId, "worker", null, "t06-bad-ifsc"));
            });
            assertTrue(diagnostics.stream().anyMatch(message -> message.contains("request_ifsc_missing")),
                    "Expected request_ifsc_missing for " + override + ", got: " + diagnostics);
            verify(loanDisbursementAdapter, never()).checkStatus(any());
        }

        // Null and non-string IFSC values need explicit JSON nodes (Map.of forbids nulls).
        reset(loanDisbursementAdapter);
        insertLatestLogWithRawPayload(account, validBase, payloadWithNullIfsc(validBase));
        List<String> nullDiagnostics = captureWarns(() -> {
            assertTrue(disbursementIntentWorkflowService.loadStatusPollContext(applicationId).isEmpty());
            assertFalse(loanDisbursementCommandService.pollPendingDisbursement(applicationId, "worker", null, "t06-null-ifsc"));
        });
        assertTrue(nullDiagnostics.stream().anyMatch(message -> message.contains("request_ifsc_missing")));
        verify(loanDisbursementAdapter, never()).checkStatus(any());

        reset(loanDisbursementAdapter);
        insertLatestLogWithRawPayload(account, validBase, payloadWithNumericIfsc(validBase));
        List<String> numericDiagnostics = captureWarns(() -> {
            assertTrue(disbursementIntentWorkflowService.loadStatusPollContext(applicationId).isEmpty());
            assertFalse(loanDisbursementCommandService.pollPendingDisbursement(applicationId, "worker", null, "t06-numeric-ifsc"));
        });
        assertTrue(numericDiagnostics.stream().anyMatch(message -> message.contains("request_ifsc_missing")));
        verify(loanDisbursementAdapter, never()).checkStatus(any());

        assertEquals(LoanAccountStatus.DISBURSEMENT_REQUESTED,
                loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow().getStatus());
    }

    @Test
    void missingOrContradictoryReferenceAndModeBlockPolling() throws Exception {
        UUID applicationId = seedApproved(FROZEN_IFSC, new BigDecimal("45000.00"));
        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/disbursement-requests", applicationId)
                        .with(systemAdmin()))
                .andExpect(status().isOk());
        disbursementIntentWorkflowService.executeForApplication(applicationId);

        LoanAccount account = loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow();
        disbursementIntentRepository.findLiveByLoanAccountId(account.getId())
                .ifPresent(intent -> {
                    truncateReconciliationEvidence();
                    disbursementIntentRepository.delete(intent);
                });
        LoanDisbursementRequestLog validBase = loanDisbursementRequestLogRepository
                .findTopByLoanAccount_IdOrderByCreatedAtDesc(account.getId()).orElseThrow();

        // Missing reference.
        reset(loanDisbursementAdapter);
        insertLatestLogWithPayload(account, validBase, Map.of("__remove__tranRefNo", true));
        List<String> missingRef = captureWarns(() -> {
            assertTrue(disbursementIntentWorkflowService.loadStatusPollContext(applicationId).isEmpty());
            assertFalse(loanDisbursementCommandService.pollPendingDisbursement(applicationId, "worker", null, "t06-missing-ref"));
        });
        assertTrue(missingRef.stream().anyMatch(message -> message.contains("request_reference_missing")), "got: " + missingRef);
        verify(loanDisbursementAdapter, never()).checkStatus(any());

        // Missing mode.
        reset(loanDisbursementAdapter);
        insertLatestLogWithPayload(account, validBase, Map.of("__remove__paymentMode", true));
        List<String> missingMode = captureWarns(() -> {
            assertTrue(disbursementIntentWorkflowService.loadStatusPollContext(applicationId).isEmpty());
            assertFalse(loanDisbursementCommandService.pollPendingDisbursement(applicationId, "worker", null, "t06-missing-mode"));
        });
        assertTrue(missingMode.stream().anyMatch(message -> message.contains("request_mode_missing")), "got: " + missingMode);
        verify(loanDisbursementAdapter, never()).checkStatus(any());

        // Contradictory reference between payload and columns.
        reset(loanDisbursementAdapter);
        insertLatestLogWithPayload(account, validBase, Map.of("tranRefNo", validBase.getTranRefNo() + "-OTHER"));
        List<String> contradictoryRef = captureWarns(() -> {
            assertTrue(disbursementIntentWorkflowService.loadStatusPollContext(applicationId).isEmpty());
            assertFalse(loanDisbursementCommandService.pollPendingDisbursement(applicationId, "worker", null, "t06-contra-ref"));
        });
        assertTrue(contradictoryRef.stream().anyMatch(message -> message.contains("payload_reference_mismatch")), "got: " + contradictoryRef);
        verify(loanDisbursementAdapter, never()).checkStatus(any());

        // Contradictory mode between payload and columns.
        reset(loanDisbursementAdapter);
        DisbursementPaymentMode otherMode = validBase.getPaymentMode() == DisbursementPaymentMode.IMPS
                ? DisbursementPaymentMode.NEFT : DisbursementPaymentMode.IMPS;
        insertLatestLogWithPayload(account, validBase, Map.of("paymentMode", otherMode.name()));
        List<String> contradictoryMode = captureWarns(() -> {
            assertTrue(disbursementIntentWorkflowService.loadStatusPollContext(applicationId).isEmpty());
            assertFalse(loanDisbursementCommandService.pollPendingDisbursement(applicationId, "worker", null, "t06-contra-mode"));
        });
        assertTrue(contradictoryMode.stream().anyMatch(message -> message.contains("payload_mode_mismatch")), "got: " + contradictoryMode);
        verify(loanDisbursementAdapter, never()).checkStatus(any());

        assertEquals(LoanAccountStatus.DISBURSEMENT_REQUESTED,
                loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow().getStatus());
    }

    @Test
    void unknownCrashRecoveryUsesFrozenReferenceWithoutResubmission() throws Exception {
        UUID applicationId = seedApproved(FROZEN_IFSC, new BigDecimal("45000.00"));
        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/disbursement-requests", applicationId)
                        .with(systemAdmin()))
                .andExpect(status().isOk());

        org.mockito.Mockito.doAnswer(invocation -> {
            throw new IllegalStateException("provider response lost");
        }).when(loanDisbursementAdapter).requestDisbursement(any());
        disbursementIntentWorkflowService.executeForApplication(applicationId);
        reset(loanDisbursementAdapter);

        LoanAccount account = loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow();
        DisbursementIntent unknown = disbursementIntentRepository.findLiveByLoanAccountId(account.getId()).orElseThrow();
        assertEquals(DisbursementIntentState.UNKNOWN, unknown.getState());
        String frozenRef = unknown.getTranRefNo();

        mutateBorrowerIfscDirect(applicationId, MUTATED_IFSC);

        assertTrue(disbursementIntentWorkflowService.loadStatusPollContext(applicationId).isPresent());
        assertFalse(loanDisbursementCommandService.pollPendingDisbursement(applicationId, "worker", null, "t06-unknown-1"));
        assertTrue(loanDisbursementCommandService.pollPendingDisbursement(applicationId, "worker", null, "t06-unknown-2"));

        List<LoanDisbursementAdapter.DisbursementStatusQuery> queries = captureStatusQueries(2);
        for (LoanDisbursementAdapter.DisbursementStatusQuery query : queries) {
            assertEquals(FROZEN_IFSC, query.beneficiaryIfsc());
            assertEquals(frozenRef, query.tranRefNo());
        }
        verify(loanDisbursementAdapter, never()).requestDisbursement(any());
        assertEquals(LoanAccountStatus.DISBURSED,
                loanAccountRepository.findByLoanApplication_Id(applicationId).orElseThrow().getStatus());
    }

    @Test
    void terminalAndParkedAccountsRequireZeroProviderCalls() throws Exception {
        // Terminal success: no further polling, and crucially no provider call at all.
        UUID disbursedId = seedApproved("HDFC0001234", new BigDecimal("45000.00"));
        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{disbursedId}/disbursement-requests", disbursedId)
                        .with(systemAdmin()))
                .andExpect(status().isOk());
        disbursementIntentWorkflowService.executeForApplication(disbursedId);
        assertEquals(LoanAccountStatus.DISBURSED,
                loanAccountRepository.findByLoanApplication_Id(disbursedId).orElseThrow().getStatus());

        reset(loanDisbursementAdapter);
        assertTrue(disbursementIntentWorkflowService.loadStatusPollContext(disbursedId).isEmpty());
        assertFalse(loanDisbursementCommandService.pollPendingDisbursement(disbursedId, "worker", null, "t06-terminal"));
        verify(loanDisbursementAdapter, never()).checkStatus(any());
        verify(loanDisbursementAdapter, never()).requestDisbursement(any());

        // Parked reconciliation: the normal poll path is closed; the reconciliation queue owns recovery.
        UUID parkedId = seedApproved("MOCK0STUCK0", new BigDecimal("45000.00"));
        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{parkedId}/disbursement-requests", parkedId)
                        .with(systemAdmin()))
                .andExpect(status().isOk());
        disbursementIntentWorkflowService.executeForApplication(parkedId);
        loanDisbursementCommandService.pollPendingDisbursement(parkedId, "worker", null, "t06-park-1");
        loanDisbursementCommandService.pollPendingDisbursement(parkedId, "worker", null, "t06-park-2");
        assertEquals(LoanAccountStatus.DISBURSEMENT_PENDING_RECONCILIATION,
                loanAccountRepository.findByLoanApplication_Id(parkedId).orElseThrow().getStatus());

        reset(loanDisbursementAdapter);
        assertTrue(disbursementIntentWorkflowService.loadStatusPollContext(parkedId).isEmpty());
        assertFalse(loanDisbursementCommandService.pollPendingDisbursement(parkedId, "worker", null, "t06-parked-poll"));
        verify(loanDisbursementAdapter, never()).checkStatus(any());
    }

    // --- fixtures ---

    private List<LoanDisbursementAdapter.DisbursementStatusQuery> captureStatusQueries(int expectedCalls) {
        ArgumentCaptor<LoanDisbursementAdapter.DisbursementStatusQuery> captor =
                ArgumentCaptor.forClass(LoanDisbursementAdapter.DisbursementStatusQuery.class);
        verify(loanDisbursementAdapter, times(expectedCalls)).checkStatus(captor.capture());
        return captor.getAllValues();
    }

    private void mutateBorrowerIfscDirect(UUID applicationId, String newIfsc) {
        UUID borrowerId = loanApplicationRepository.findById(applicationId).orElseThrow().getBorrower().getId();
        Borrower borrower = borrowerRepository.findById(borrowerId).orElseThrow();
        borrower.updateBankDetails(
                borrower.getBankAccountNumber(),
                borrower.getBankName(),
                newIfsc,
                borrower.getAccountHolderName());
        borrowerRepository.save(borrower);
    }

    private String liveBorrowerIfsc(UUID applicationId) {
        UUID borrowerId = loanApplicationRepository.findById(applicationId).orElseThrow().getBorrower().getId();
        return borrowerRepository.findById(borrowerId).orElseThrow().getIfscCode();
    }

    private void insertLatestLogWithPayload(
            LoanAccount account,
            LoanDisbursementRequestLog base,
            Map<String, Object> overrides) throws Exception {
        insertLatestLogWithRawPayload(account, base, buildPayloadJson(base, overrides));
    }

    private void insertLatestLogWithRawPayload(
            LoanAccount account,
            LoanDisbursementRequestLog base,
            String requestPayloadJson) {
        insertLatestLogWithColumns(account, base, requestPayloadJson, base.getTranRefNo(), base.getPaymentMode());
    }

    private void insertDivergentLatestLog(
            LoanAccount account,
            LoanDisbursementRequestLog base,
            String divergentRef) throws Exception {
        ObjectNode node = (ObjectNode) objectMapper.readTree(base.getRequestPayloadJson());
        node.put("tranRefNo", divergentRef);
        String payload = objectMapper.writeValueAsString(node);
        // Payload and columns agree with each other on the divergent reference; the live intent
        // still carries the original reference, which is the conflict under test.
        insertLatestLogWithColumns(account, base, payload, divergentRef, base.getPaymentMode());
    }

    private void insertLatestLogWithColumns(
            LoanAccount account,
            LoanDisbursementRequestLog base,
            String requestPayloadJson,
            String columnTranRefNo,
            DisbursementPaymentMode columnMode) {
        LoanAccount attached = loanAccountRepository.findByLoanApplication_Id(
                account.getLoanApplication().getId()).orElseThrow();
        LoanDisbursementRequestLog fresh = new LoanDisbursementRequestLog(
                attached,
                base.getActorUsername(),
                base.getAmount(),
                base.getProviderName(),
                base.getProviderRequestId(),
                DisbursementDisposition.PENDING.name(),
                columnMode,
                columnTranRefNo,
                base.getProviderActCode(),
                base.getBankRrn(),
                base.getDeclineKind() == null ? DisbursementDeclineKind.NONE : base.getDeclineKind(),
                requestPayloadJson,
                "{\"disposition\":\"PENDING\"}",
                base.getCorrelationId());
        loanDisbursementRequestLogRepository.save(fresh);
    }

    private String buildPayloadJson(LoanDisbursementRequestLog base, Map<String, Object> overrides) throws Exception {
        ObjectNode node = (ObjectNode) objectMapper.readTree(base.getRequestPayloadJson());
        for (Map.Entry<String, Object> entry : overrides.entrySet()) {
            String key = entry.getKey();
            if (key.startsWith("__remove__")) {
                node.remove(key.substring("__remove__".length()));
            } else {
                Object value = entry.getValue();
                if (value == null) {
                    node.putNull(key);
                } else {
                    node.put(key, value.toString());
                }
            }
        }
        return objectMapper.writeValueAsString(node);
    }

    private String payloadWithNullIfsc(LoanDisbursementRequestLog base) throws Exception {
        ObjectNode node = (ObjectNode) objectMapper.readTree(base.getRequestPayloadJson());
        node.putNull("beneficiaryIfsc");
        return objectMapper.writeValueAsString(node);
    }

    private String payloadWithNumericIfsc(LoanDisbursementRequestLog base) throws Exception {
        ObjectNode node = (ObjectNode) objectMapper.readTree(base.getRequestPayloadJson());
        node.put("beneficiaryIfsc", 12345);
        return objectMapper.writeValueAsString(node);
    }

    private List<String> captureWarns(ThrowingRunnable runnable) throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(DisbursementIntentWorkflowService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            runnable.run();
        } finally {
            logger.detachAppender(appender);
        }
        List<String> messages = new ArrayList<>();
        for (ILoggingEvent event : appender.list) {
            messages.add(event.getFormattedMessage());
        }
        return messages;
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private UUID seedApproved(String ifsc, BigDecimal requestedAmount) throws Exception {
        String lspId = createLspViaAdmin();
        String productId = createProductViaAdmin();
        mapProductToLsp(productId, lspId);
        String applicationId = createApplicationViaOps(lspId, productId, requestedAmount);
        transition(applicationId, "AWAITING_APPROVAL", "Ready for approval");
        markKycComplete(applicationId);
        transition(applicationId, "APPROVED_PENDING_DISBURSAL", "Approved for phase-1 test");
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
        payload.put("borrowerEmail", "t06+" + borrowerPan.toLowerCase() + "@example.com");
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
                            "Uploaded for phase-1 test",
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
