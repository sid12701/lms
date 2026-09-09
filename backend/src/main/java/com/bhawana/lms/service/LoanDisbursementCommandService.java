package com.bhawana.lms.service;

import com.bhawana.lms.common.correlation.CorrelationIdHolder;
import com.bhawana.lms.common.money.Money;
import com.bhawana.lms.common.api.error.ApiConflictException;
import com.bhawana.lms.common.api.error.BusinessRuleViolationException;
import com.bhawana.lms.common.api.error.ResourceNotFoundException;
import com.bhawana.lms.domain.DisbursementDeclineKind;
import com.bhawana.lms.domain.DisbursementDisposition;
import com.bhawana.lms.domain.DisbursementIntent;
import com.bhawana.lms.domain.DisbursementIntentState;
import com.bhawana.lms.domain.DisbursementPaymentMode;
import com.bhawana.lms.domain.DisbursementReconciliationReason;
import com.bhawana.lms.domain.LoanAccount;
import com.bhawana.lms.domain.LoanAccountStatus;
import com.bhawana.lms.domain.LoanApplication;
import com.bhawana.lms.domain.LoanApplicationStatus;
import com.bhawana.lms.domain.LoanDisbursementRequestLog;
import com.bhawana.lms.domain.MockDisbursementOutcome;
import com.bhawana.lms.repo.LoanAccountRepository;
import com.bhawana.lms.repo.DisbursementIntentRepository;
import com.bhawana.lms.repo.LoanApplicationRepository;
import com.bhawana.lms.repo.LoanDisbursementRequestLogRepository;
import jakarta.persistence.EntityManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Owns disbursement <em>initiation</em> (creating the durable intent; C04: the only
 * money-movement path — the worker executes the committed intent outside any transaction)
 * and the orchestration of the resolution flows (manual mock outcome, worker auto-resolve,
 * status-check poll). The terminal verdict is normalised into a
 * {@link DisbursementOutcomeApplier.ProviderOutcome} and applied to persistent state by
 * {@link DisbursementOutcomeApplier}.
 */
@Service
public class LoanDisbursementCommandService {

    private final LoanApplicationRepository loanApplicationRepository;
    private final LoanAccountRepository loanAccountRepository;
    private final LoanDisbursementRequestLogRepository loanDisbursementRequestLogRepository;
    private final LoanDisbursementAdapter loanDisbursementAdapter;
    private final LoanApplicationQueryService loanApplicationQueryService;
    private final LoanApplicationDocumentChecklistService loanApplicationDocumentChecklistService;
    private final LoanApplicationStatusWriter loanApplicationStatusWriter;
    private final DisbursementOutcomeApplier disbursementOutcomeApplier;
    private final LoanDisbursementMockProperties mockProperties;
    private final DisbursementIntentWorkflowService disbursementIntentWorkflowService;
    private final DisbursementPaymentModeSelector paymentModeSelector;
    private final DisbursementSimulationGuard simulationGuard;
    private final DisbursementObservationWriter observationWriter;
    private final DisbursementIntentRepository disbursementIntentRepository;
    private final ObjectMapper objectMapper;
    private final DisbursementProviderLatency providerLatency;
    private final TransactionTemplate transactionTemplate;
    private final EntityManager entityManager;

    public LoanDisbursementCommandService(
            LoanApplicationRepository loanApplicationRepository,
            LoanAccountRepository loanAccountRepository,
            LoanDisbursementRequestLogRepository loanDisbursementRequestLogRepository,
            LoanDisbursementAdapter loanDisbursementAdapter,
            LoanApplicationQueryService loanApplicationQueryService,
            LoanApplicationDocumentChecklistService loanApplicationDocumentChecklistService,
            LoanApplicationStatusWriter loanApplicationStatusWriter,
            DisbursementOutcomeApplier disbursementOutcomeApplier,
            LoanDisbursementMockProperties mockProperties,
            DisbursementIntentWorkflowService disbursementIntentWorkflowService,
            DisbursementPaymentModeSelector paymentModeSelector,
            DisbursementSimulationGuard simulationGuard,
            DisbursementObservationWriter observationWriter,
            DisbursementIntentRepository disbursementIntentRepository,
            ObjectMapper objectMapper,
            DisbursementProviderLatency providerLatency,
            TransactionTemplate transactionTemplate,
            EntityManager entityManager
    ) {
        this.loanApplicationRepository = loanApplicationRepository;
        this.loanAccountRepository = loanAccountRepository;
        this.loanDisbursementRequestLogRepository = loanDisbursementRequestLogRepository;
        this.loanDisbursementAdapter = loanDisbursementAdapter;
        this.loanApplicationQueryService = loanApplicationQueryService;
        this.loanApplicationDocumentChecklistService = loanApplicationDocumentChecklistService;
        this.loanApplicationStatusWriter = loanApplicationStatusWriter;
        this.disbursementOutcomeApplier = disbursementOutcomeApplier;
        this.mockProperties = mockProperties;
        this.disbursementIntentWorkflowService = disbursementIntentWorkflowService;
        this.paymentModeSelector = paymentModeSelector;
        this.simulationGuard = simulationGuard;
        this.observationWriter = observationWriter;
        this.disbursementIntentRepository = disbursementIntentRepository;
        this.objectMapper = objectMapper;
        this.providerLatency = providerLatency;
        this.transactionTemplate = transactionTemplate;
        this.entityManager = entityManager;
    }

    @Transactional
    public LoanApplication initiateDisbursement(UUID applicationId, String actorUsername) {
        lockBorrowerForDisbursement(applicationId);
        lockApplicationForDisbursement(applicationId);
        LoanApplication application = loanApplicationQueryService.getApplication(applicationId);
        if (isDisbursementAlreadyComplete(application.getStatus())) {
            return application;
        }
        LoanAccount loanAccount = resolveLoanAccountForDisbursement(application);
        // C04: no silent idempotent return — REQUESTED/PENDING_RECONCILIATION must reconcile,
        // never re-initiate. The detailed guard lives in the overload below + the domain method.
        return initiateDisbursement(
                applicationId,
                actorUsername,
                DisbursementAmounts.fromLoanAccount(loanAccount).netDisbursalAmount()
        );
    }

    @Transactional
    public LoanApplication initiateDisbursement(
            UUID applicationId,
            String actorUsername,
            BigDecimal disbursementAmount
    ) {
        lockBorrowerForDisbursement(applicationId);
        lockApplicationForDisbursement(applicationId);
        LoanApplication application = loanApplicationQueryService.getApplication(applicationId);
        if (isDisbursementAlreadyComplete(application.getStatus())) {
            return application;
        }
        if (application.getStatus() != LoanApplicationStatus.APPROVED_PENDING_DISBURSAL
                && application.getStatus() != LoanApplicationStatus.DISBURSEMENT_RETRY) {
            throw new BusinessRuleViolationException(
                    "DISBURSEMENT_NOT_ALLOWED",
                    "Disbursement can only be requested for applications pending disbursal or disbursement retry.",
                    Map.of()
            );
        }
        loanApplicationDocumentChecklistService.validateRequiredDocumentsUploadedBeforeDisbursement(applicationId);

        LoanAccount loanAccount = resolveLoanAccountForDisbursement(application);
        // C04: the durable intent is the only money-movement path. Re-initiation from an
        // in-flight (REQUESTED) or uncertain (PENDING_RECONCILIATION) loan is rejected — the
        // only forward path there is reconciliation of the original reference.
        if (loanAccount.getStatus() == LoanAccountStatus.DISBURSEMENT_REQUESTED
                || loanAccount.getStatus() == LoanAccountStatus.DISBURSEMENT_PENDING_RECONCILIATION) {
            throw new ApiConflictException(
                    "DISBURSEMENT_ALREADY_REQUESTED",
                    "Disbursement has already been requested for this loan account; reconcile the original reference instead of initiating again."
            );
        }
        if (loanAccount.getStatus() != LoanAccountStatus.PENDING_DISBURSEMENT
                && loanAccount.getStatus() != LoanAccountStatus.DISBURSEMENT_FAILED) {
            throw new ApiConflictException(
                    "DISBURSEMENT_ALREADY_REQUESTED",
                    "Disbursement has already been requested for this loan account."
            );
        }

        BigDecimal scaledDisbursementAmount = Money.scale(Money.requirePositive(disbursementAmount, "Disbursement amount"));
        DisbursementPaymentMode paymentMode = paymentModeSelector.selectPaymentMode(scaledDisbursementAmount);

        // C04: durable intent is mandatory — no inline provider call, no flag branch.
        disbursementIntentWorkflowService.createIntent(
                application,
                loanAccount,
                scaledDisbursementAmount,
                paymentMode,
                actorUsername
        );
        return application;
    }

    /**
     * Worker hook: after {@link #initiateDisbursement} raises a request, resolve a terminal provider
     * verdict (IMPS success / decline) inline. PENDING transactions are left for the status-check
     * worker. Returns the disposition observed, or {@code null} when there is nothing to resolve.
     *
     * <p>G01: mock auto-resolve is simulation-only. The guard is enforced here — not on the wired
     * adapter type — so the boundary holds even after a real bank adapter replaces the mock.
     */
    @Transactional
    public DisbursementDisposition autoResolveAfterInitiate(
            UUID applicationId,
            String actorUsername,
            String actorIp,
            String correlationId
    ) {
        simulationGuard.requireSimulationAllowed("auto-resolve-mock-outcome");
        LoanApplication application = loanApplicationQueryService.getApplication(applicationId);
        LoanAccount loanAccount = getRequiredLoanAccount(applicationId);
        if (loanAccount.getStatus() != LoanAccountStatus.DISBURSEMENT_REQUESTED) {
            return null;
        }
        LoanDisbursementRequestLog latestRequest = requireLatestRequest(applicationId, loanAccount.getId());
        DisbursementDisposition disposition = dispositionOf(latestRequest);
        if (disposition == DisbursementDisposition.PENDING) {
            return DisbursementDisposition.PENDING;
        }
        disbursementOutcomeApplier.apply(
                application,
                loanAccount,
                latestRequest,
                new DisbursementOutcomeApplier.ProviderOutcome(
                        disposition,
                        latestRequest.getDeclineKind(),
                        latestRequest.getProviderActCode(),
                        latestRequest.getBankRrn(),
                        "Resolved from provider payment response."
                ),
                actorUsername,
                actorIp,
                correlationId
        );
        return disposition;
    }

    /**
     * Status-check worker hook: polls a PENDING transaction (ICICI {@code /composite-status}) and
     * resolves it once terminal, or parks it for reconciliation once the poll cap is reached.
     *
     * <p>C04: single reconciliation path — no flag branch. The provider call stays outside the
     * transaction; the verdict, account/application transition, loan event and outcome audit commit
     * together. Rows created before the inline-initiation removal (legacy logs without an intent)
     * remain pollable through the same method; when a live intent exists it is marked from the
     * same verdict so intent and log never diverge.
     *
     * @return true when the transaction reached a terminal/parked state; false when still pending.
     */
    public boolean pollPendingDisbursement(
            UUID applicationId,
            String actorUsername,
            String actorIp,
            String correlationId
    ) {
        Optional<DisbursementIntentWorkflowService.StatusPollContext> context =
                disbursementIntentWorkflowService.loadStatusPollContext(applicationId);
        if (context.isEmpty()) {
            return false;
        }
        return pollWithCapturedContext(context.get(), actorUsername, actorIp, correlationId);
    }

    /**
     * H02 — shared poll engine behind both the normal status-check path (still-REQUESTED loans)
     * and the reconciliation sweep (REQUESTED plus parked loans, same frozen original
     * reference). The network call stays outside the transaction; the captured
     * reference/instruction is revalidated under locks before anything is applied.
     */
    boolean pollWithCapturedContext(
            DisbursementIntentWorkflowService.StatusPollContext captured,
            String actorUsername,
            String actorIp,
            String correlationId
    ) {
        // Provider call stays outside the transaction: resolving it holds no database work, and a
        // slow or hung provider must not pin a pooled connection for the worker's whole serial loop.
        // H02: the captured frozen reference/instruction travels with the call and is revalidated
        // under locks after the network before anything is applied.
        String capturedRef = captured.latestRequest().getTranRefNo();
        LoanDisbursementAdapter.DisbursementStatusQuery capturedQuery = captured.query();
        // H02 — durable per-call identity BEFORE the network: the claim commits the poll
        // sequence on the stored request before the provider is touched. The sequence travels
        // into the immutable result (including timeouts); a crash after the call but before
        // the result commit leaves the claimed sequence with no result row — a crashed
        // attempt that stays visible as attempted-with-missing-result. No response is ever
        // fabricated for it; recovery re-polls the same reference with a fresh sequence.
        java.util.Optional<Integer> pollSeq = claimPollAttempt(captured, capturedRef);
        if (pollSeq.isEmpty()) {
            return false;
        }
        int callSeq = pollSeq.get();
        LoanDisbursementAdapter.DisbursementStatusResult statusResult;
        try {
            // H27/H02: the bank call stays outside every transaction; latency is recorded
            // even on timeout (the timeout observation path below still runs).
            statusResult = providerLatency.timeStatusCheck(() -> loanDisbursementAdapter.checkStatus(capturedQuery));
        } catch (RuntimeException timeout) {
            // H02: timeouts are POLL observations too — carrying the claimed sequence, the
            // original reference stays queued, and nothing is re-initiated.
            recordPollTimeout(captured, callSeq, actorUsername, correlationId, timeout.getMessage());
            return false;
        }
        LoanDisbursementAdapter.DisbursementStatusResult resolvedResult = statusResult;

        // Applying the verdict opens its own boundary: the observation, the account status, the
        // application transition, the loan event and the outcome audit commit together or not at
        // all. A self-invocation of an @Transactional method would bypass the proxy and leave
        // each of those writes auto-committing on its own, so the template is not optional.
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            DisbursementIntentWorkflowService.StatusPollContext pollContext = captured;
            UUID polledApplicationId = pollContext.application().getId();
            UUID polledAccountId = pollContext.loanAccount().getId();
            UUID polledRequestId = pollContext.latestRequest().getId();
            LoanApplication lockedApplication =
                    loanApplicationRepository.findByIdForUpdate(polledApplicationId).orElse(null);
            LoanAccount lockedAccount =
                    loanAccountRepository.findByIdForUpdate(polledAccountId).orElse(null);
            LoanDisbursementRequestLog capturedRequest = loanDisbursementRequestLogRepository
                    .findById(polledRequestId)
                    .orElse(null);
            if (lockedApplication == null || lockedAccount == null || capturedRequest == null) {
                return false;
            }
            // H02: reread the actual latest stored request under the locks. The captured row
            // is evidence of what was polled — but if a newer request row appeared while the
            // provider call was in flight, this capture is stale: record the verdict as
            // duplicate evidence against the captured identity and apply nothing.
            LoanDisbursementRequestLog topRequest = loanDisbursementRequestLogRepository
                    .findTopByLoanAccount_IdOrderByCreatedAtDesc(lockedAccount.getId()).orElse(null);
            boolean staleIdentity = topRequest == null || !topRequest.getId().equals(capturedRequest.getId());
            // H02 revalidation: the captured reference and frozen instruction are rechecked under
            // the application→account→intent locks AFTER the network. A loan that resolved
            // concurrently keeps terminal precedence; a changed live instruction, a superseded
            // capture, or a definitive verdict contradicting the accepted outcome surfaces as
            // operator-queue evidence. The observation itself is still recorded — never lost
            // because the loan moved.
            DisbursementIntent liveIntent = disbursementIntentRepository
                    .findLiveByLoanAccountIdForUpdate(lockedAccount.getId()).orElse(null);
            Revalidation revalidation = staleIdentity
                    ? Revalidation.blocked(
                            DisbursementReconciliationReason.LEGACY_MISMATCH,
                            "Newer stored request " + (topRequest == null ? "unknown" : topRequest.getId())
                                    + " superseded poll capture " + capturedRequest.getId()
                                    + " for reference " + capturedRef + "; verdict kept as evidence only.")
                    : revalidatePolledState(
                            lockedAccount, capturedRequest, liveIntent, pollContext, capturedRef);
            if (!revalidation.ok()) {
                observationWriter.recordPoll(
                        lockedAccount,
                        liveIntent,
                        capturedRef,
                        liveIntent == null ? 0 : liveIntent.getAttemptCount(),
                        callSeq,
                        resolvedResult.disposition(),
                        resolvedResult.isQueryResolved(),
                        true,
                        loanDisbursementAdapter.providerName(),
                        capturedRequest.getProviderRequestId(),
                        resolvedResult.actCode(),
                        resolvedResult.bankRrn(),
                        resolvedResult.declineKind(),
                        pollContext.frozenBeneficiaryIfsc(),
                        frozenBeneficiaryAccount(pollContext),
                        pollContext.frozenPaymentMode(),
                        serializeStatusQuery(capturedQuery),
                        resolvedResult.responsePayloadJson(),
                        correlationId,
                        actorUsername
                );
                if (revalidation.queueReason() != null) {
                    observationWriter.enqueue(
                            lockedAccount, liveIntent, capturedRef,
                            revalidation.queueReason(), revalidation.details());
                } else if (resolvedResult.isQueryResolved()
                        && resolvedResult.disposition() != DisbursementDisposition.PENDING
                        && DisbursementIntentWorkflowService.contradictsResolvedLoan(
                                resolvedResult.disposition(),
                                lockedAccount.getStatus(),
                                liveIntent == null ? null : liveIntent.getState())) {
                    // H02: a delayed definitive verdict contradicts the accepted terminal
                    // outcome — preserved above and held operator-visible, never applied.
                    observationWriter.enqueue(
                            lockedAccount, liveIntent, capturedRef,
                            DisbursementReconciliationReason.CONFLICTING_EVIDENCE,
                            "Delayed " + resolvedResult.disposition() + " verdict for reference "
                                    + capturedRef + " contradicts the accepted "
                                    + lockedAccount.getStatus() + " outcome; operator review required.");
                }
                // H02: terminal duplicate — evidence kept above, accepted outcome and queue left
                // untouched so a conflicting-evidence entry is never dismissed by a late poll.
                return false;
            }
            // C04/C02: a delayed poll must never regress an accepted result. Revalidation above
            // already rejected anything but still-REQUESTED (normal path) or parked
            // (reconciliation sweep on the original reference); stranded terminals belong to
            // the repair path.
            boolean terminal = applyStatusPollResult(
                    lockedApplication,
                    lockedAccount,
                    capturedRequest,
                    liveIntent,
                    callSeq,
                    resolvedResult,
                    capturedQuery,
                    actorUsername,
                    actorIp,
                    correlationId
            );
            if (resolvedResult.isQueryResolved() && resolvedResult.disposition() != DisbursementDisposition.PENDING) {
                // C06-phase-1: only the intent owning this reference may be marked; a different live
                // intent must never receive another instruction's verdict.
                disbursementIntentWorkflowService.markIntentFromStatusPoll(
                        lockedAccount.getId(), capturedRequest.getTranRefNo(), resolvedResult);
            }
            return terminal;
        }));
    }

    /**
     * H02 — commits the attempted-poll marker before any network I/O: locks the account and
     * the captured stored request, rechecks they still carry the captured reference in a
     * pollable state, and bumps the poll count. Returns the assigned durable per-call
     * sequence (the post-increment count, unique per attempted call on this request), or
     * empty — no network call, no observation, nothing attempted — when the capture went
     * stale first.
     */
    private java.util.Optional<Integer> claimPollAttempt(
            DisbursementIntentWorkflowService.StatusPollContext captured,
            String capturedRef
    ) {
        return transactionTemplate.execute(tx -> {
            LoanAccount lockedAccount = loanAccountRepository
                    .findByIdForUpdate(captured.loanAccount().getId()).orElse(null);
            LoanDisbursementRequestLog capturedRequest = loanDisbursementRequestLogRepository
                    .findById(captured.latestRequest().getId()).orElse(null);
            if (lockedAccount == null || capturedRequest == null) {
                return java.util.Optional.empty();
            }
            if (lockedAccount.getStatus() != LoanAccountStatus.DISBURSEMENT_REQUESTED
                    && lockedAccount.getStatus() != LoanAccountStatus.DISBURSEMENT_PENDING_RECONCILIATION) {
                return java.util.Optional.empty();
            }
            if (!java.util.Objects.equals(capturedRequest.getTranRefNo(), capturedRef)) {
                return java.util.Optional.empty();
            }
            // The capture must still be the latest stored request; a newer row means a newer
            // capture owns polling now.
            LoanDisbursementRequestLog top = loanDisbursementRequestLogRepository
                    .findTopByLoanAccount_IdOrderByCreatedAtDesc(lockedAccount.getId()).orElse(null);
            if (top == null || !top.getId().equals(capturedRequest.getId())) {
                return java.util.Optional.empty();
            }
            capturedRequest.recordStatusCheck();
            loanDisbursementRequestLogRepository.save(capturedRequest);
            return java.util.Optional.of(capturedRequest.getStatusCheckCount());
        });
    }

    /**
     * H02 — revalidates the pre-network capture under locks. Accepted only when the latest
     * stored request still carries the captured reference, the live intent (when present) still
     * owns it, and the frozen beneficiary instruction is unchanged. Anything else is a stale or
     * contradictory capture: the observation is kept as duplicate evidence and the loan either
     * keeps its accepted outcome or lands in the operator queue.
     */
    private Revalidation revalidatePolledState(
            LoanAccount lockedAccount,
            LoanDisbursementRequestLog latestRequest,
            DisbursementIntent liveIntent,
            DisbursementIntentWorkflowService.StatusPollContext pollContext,
            String capturedRef
    ) {
        if (!java.util.Objects.equals(latestRequest.getTranRefNo(), capturedRef)) {
            return Revalidation.blocked(
                    DisbursementReconciliationReason.LEGACY_MISMATCH,
                    "Poll capture reference " + capturedRef
                            + " no longer matches the latest stored request; operator review required.");
        }
        if (liveIntent != null) {
            if (!java.util.Objects.equals(liveIntent.getTranRefNo(), capturedRef)) {
                return Revalidation.blocked(
                        DisbursementReconciliationReason.LEGACY_MISMATCH,
                        "Live instruction reference changed under poll for reference " + capturedRef
                                + "; the captured verdict belongs to a superseded instruction.");
            }
            if (!normalize(liveIntent.getBeneficiaryIfsc()).equals(normalize(pollContext.frozenBeneficiaryIfsc()))
                    || liveIntent.getPaymentMode() != pollContext.frozenPaymentMode()
                    || !java.util.Objects.equals(
                            liveIntent.getBeneficiaryAccountNumber(),
                            frozenBeneficiaryAccount(pollContext))) {
                return Revalidation.blocked(
                        DisbursementReconciliationReason.LEGACY_MISMATCH,
                        "Frozen instruction changed under poll for reference " + capturedRef
                                + "; manual reconciliation required, live borrower data is never substituted.");
            }
            if (liveIntent.getState().isTerminal()) {
                return Revalidation.blocked(
                        DisbursementReconciliationReason.STRANDED_TERMINAL,
                        "Terminal intent already recorded for reference " + capturedRef
                                + "; stranded repair owns application, poll verdict kept as evidence.");
            }
        }
        if (lockedAccount.getStatus() != LoanAccountStatus.DISBURSEMENT_REQUESTED
                && lockedAccount.getStatus() != LoanAccountStatus.DISBURSEMENT_PENDING_RECONCILIATION) {
            return Revalidation.blocked(
                    null,
                    "Account already " + lockedAccount.getStatus() + " when poll for reference "
                            + capturedRef + " returned; accepted outcome preserved.");
        }
        // H02: both still-REQUESTED and already-parked loans are accepted here. Parked loans
        // arrive only via the reconciliation sweep polling the original reference; the apply
        // step refreshes their queue entry without emitting a new transition/event.
        return Revalidation.accepted();
    }

    private record Revalidation(boolean ok, DisbursementReconciliationReason queueReason, String details) {
        static Revalidation accepted() {
            return new Revalidation(true, null, null);
        }

        static Revalidation blocked(DisbursementReconciliationReason queueReason, String details) {
            return new Revalidation(false, queueReason, details);
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(java.util.Locale.ROOT);
    }

    private static String frozenBeneficiaryAccount(
            DisbursementIntentWorkflowService.StatusPollContext pollContext) {
        try {
            com.fasterxml.jackson.databind.JsonNode payload = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(pollContext.latestRequest().getRequestPayloadJson());
            com.fasterxml.jackson.databind.JsonNode node = payload.get("beneficiaryAccountNumber");
            return node != null && node.isTextual() ? node.asText() : "";
        } catch (Exception ignored) {
            return "";
        }
    }

    private boolean applyStatusPollResult(
            LoanApplication application,
            LoanAccount loanAccount,
            LoanDisbursementRequestLog latestRequest,
            DisbursementIntent liveIntent,
            int callSeq,
            LoanDisbursementAdapter.DisbursementStatusResult statusResult,
            LoanDisbursementAdapter.DisbursementStatusQuery capturedQuery,
            String actorUsername,
            String actorIp,
            String correlationId
    ) {
        // H02: the poll count was already committed pre-call (claimPollAttempt) as the durable
        // attempted-call marker, so it is not bumped again here: one attempted poll consumes
        // exactly one count whether the result commits, times out, or crashes mid-apply.
        // H02: every poll attempt is an immutable observation in the same atomic boundary as the
        // outcome decision — N polls produce N observations, including unresolved queries.
        boolean queryResolvedTerminal = statusResult.isQueryResolved()
                && statusResult.disposition() != DisbursementDisposition.PENDING;
        observationWriter.recordPoll(
                loanAccount,
                liveIntent,
                latestRequest.getTranRefNo(),
                liveIntent == null ? 0 : liveIntent.getAttemptCount(),
                callSeq,
                statusResult.disposition(),
                statusResult.isQueryResolved(),
                false,
                loanDisbursementAdapter.providerName(),
                latestRequest.getProviderRequestId(),
                statusResult.actCode(),
                statusResult.bankRrn(),
                statusResult.declineKind(),
                capturedQuery.beneficiaryIfsc(),
                frozenBeneficiaryAccountFor(latestRequest, liveIntent),
                capturedQuery.paymentMode(),
                serializeStatusQuery(capturedQuery),
                statusResult.responsePayloadJson(),
                correlationId,
                actorUsername
        );

        if (queryResolvedTerminal) {
            disbursementOutcomeApplier.apply(
                    application,
                    loanAccount,
                    latestRequest,
                    new DisbursementOutcomeApplier.ProviderOutcome(
                            statusResult.disposition(),
                            statusResult.declineKind(),
                            statusResult.actCode(),
                            statusResult.bankRrn(),
                            statusResult.message()
                    ),
                    actorUsername,
                    actorIp,
                    correlationId
            );
            // H02: terminal outcome accepted — resolved, clear the queue.
            observationWriter.clear(loanAccount.getId());
            return true;
        }

        if (loanAccount.getStatus() == LoanAccountStatus.DISBURSEMENT_PENDING_RECONCILIATION) {
            // H02 reconciliation re-poll confirmed still pending on an already-parked loan:
            // refresh the queue entry for age, advance the backoff for the attempt just made,
            // but emit no new transition/event.
            observationWriter.enqueue(
                    loanAccount,
                    liveIntent,
                    latestRequest.getTranRefNo(),
                    DisbursementReconciliationReason.PARKED,
                    "Reconciliation re-poll still pending for parked reference "
                            + latestRequest.getTranRefNo() + "; original reference retained.");
            observationWriter.recordAttempt(loanAccount.getId());
            return true;
        }

        if (latestRequest.getStatusCheckCount() >= mockProperties.getStatusCheck().getMaxPolls()) {
            disbursementOutcomeApplier.apply(
                    application,
                    loanAccount,
                    latestRequest,
                    new DisbursementOutcomeApplier.ProviderOutcome(
                            DisbursementDisposition.PENDING,
                            DisbursementDeclineKind.NONE,
                            latestRequest.getProviderActCode(),
                            latestRequest.getBankRrn(),
                            "Status check exhausted; parked for manual reconciliation."
                    ),
                    actorUsername,
                    actorIp,
                    correlationId
            );
            // H02: normal polling is exhausted — the loan parks with its original reference and
            // stays in the queue; reconciliation keeps polling it, never re-initiating.
            observationWriter.enqueue(
                    loanAccount,
                    liveIntent,
                    latestRequest.getTranRefNo(),
                    DisbursementReconciliationReason.PARKED,
                    "Status check exhausted for reference " + latestRequest.getTranRefNo()
                            + "; parked for reconciliation on the original reference.");
            observationWriter.recordAttempt(loanAccount.getId());
            return true;
        }
        // H02: still pending — refresh the queue entry and advance the backoff for the attempt.
        observationWriter.enqueue(
                loanAccount,
                liveIntent,
                latestRequest.getTranRefNo(),
                liveIntent != null && liveIntent.getState() == DisbursementIntentState.UNKNOWN
                        ? DisbursementReconciliationReason.UNKNOWN
                        : DisbursementReconciliationReason.REQUESTED,
                "Poll " + latestRequest.getStatusCheckCount() + " still pending for reference "
                        + latestRequest.getTranRefNo() + ".");
        observationWriter.recordAttempt(loanAccount.getId());
        return false;
    }

    /**
     * H02 — a hung/failed status call is still evidence: the poll counts, the observation is
     * stored with an unresolved query, and the original reference stays queued.
     */
    private void recordPollTimeout(
            DisbursementIntentWorkflowService.StatusPollContext captured,
            int callSeq,
            String actorUsername,
            String correlationId,
            String message
    ) {
        UUID accountId = captured.loanAccount().getId();
        UUID applicationId = captured.application().getId();
        UUID requestId = captured.latestRequest().getId();
        Boolean counted = transactionTemplate.execute(tx -> {
            LoanAccount lockedAccount = loanAccountRepository.findByIdForUpdate(accountId).orElse(null);
            LoanDisbursementRequestLog latestRequest =
                    loanDisbursementRequestLogRepository.findById(requestId).orElse(null);
            if (lockedAccount == null || latestRequest == null) {
                return false;
            }
            if (lockedAccount.getStatus() != LoanAccountStatus.DISBURSEMENT_REQUESTED
                    && lockedAccount.getStatus() != LoanAccountStatus.DISBURSEMENT_PENDING_RECONCILIATION) {
                // Resolved concurrently: keep the timeout as duplicate evidence, preserve outcome.
                DisbursementIntent liveIntent = disbursementIntentRepository
                        .findLiveByLoanAccountId(lockedAccount.getId()).orElse(null);
                observationWriter.recordPoll(
                        lockedAccount, liveIntent, captured.latestRequest().getTranRefNo(),
                        liveIntent == null ? 0 : liveIntent.getAttemptCount(),
                        callSeq,
                        DisbursementDisposition.PENDING, false, true,
                        loanDisbursementAdapter.providerName(), latestRequest.getProviderRequestId(),
                        latestRequest.getProviderActCode(), latestRequest.getBankRrn(),
                        DisbursementDeclineKind.NONE,
                        captured.frozenBeneficiaryIfsc(),
                        frozenBeneficiaryAccountFor(latestRequest, liveIntent),
                        captured.frozenPaymentMode(),
                        serializeStatusQuery(captured.query()),
                        timeoutResponseJson(message),
                        correlationId, actorUsername);
                // H02: resolved concurrently — timeout kept as duplicate evidence above; the
                // accepted outcome and any held conflicting-evidence queue entry stand.
                return false;
            }
            DisbursementIntent liveIntent = disbursementIntentRepository
                    .findLiveByLoanAccountId(lockedAccount.getId()).orElse(null);
            // H02: the attempt was already counted pre-call; the timeout only needs its
            // observation plus the retained queue entry.
            observationWriter.recordPoll(
                    lockedAccount, liveIntent, latestRequest.getTranRefNo(),
                    liveIntent == null ? 0 : liveIntent.getAttemptCount(),
                    callSeq,
                    DisbursementDisposition.PENDING, false, false,
                    loanDisbursementAdapter.providerName(), latestRequest.getProviderRequestId(),
                    latestRequest.getProviderActCode(), latestRequest.getBankRrn(),
                    DisbursementDeclineKind.NONE,
                    captured.frozenBeneficiaryIfsc(),
                    frozenBeneficiaryAccountFor(latestRequest, liveIntent),
                    captured.frozenPaymentMode(),
                    serializeStatusQuery(captured.query()),
                    timeoutResponseJson(message),
                    correlationId, actorUsername);
            observationWriter.enqueue(
                    lockedAccount, liveIntent, latestRequest.getTranRefNo(),
                    liveIntent != null && liveIntent.getState() == DisbursementIntentState.UNKNOWN
                            ? DisbursementReconciliationReason.UNKNOWN
                            : DisbursementReconciliationReason.REQUESTED,
                    "Status poll timed out for reference " + latestRequest.getTranRefNo()
                            + "; original reference retained.");
            observationWriter.recordAttempt(lockedAccount.getId());
            return true;
        });
        if (Boolean.FALSE.equals(counted)) {
            org.slf4j.LoggerFactory.getLogger(LoanDisbursementCommandService.class).debug(
                    "Poll timeout for application {} recorded without progress.", applicationId);
        }
    }

    private static String frozenBeneficiaryAccountFor(
            LoanDisbursementRequestLog latestRequest, DisbursementIntent liveIntent) {
        if (liveIntent != null && liveIntent.getBeneficiaryAccountNumber() != null) {
            return liveIntent.getBeneficiaryAccountNumber();
        }
        try {
            com.fasterxml.jackson.databind.JsonNode payload =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(latestRequest.getRequestPayloadJson());
            com.fasterxml.jackson.databind.JsonNode node = payload.get("beneficiaryAccountNumber");
            return node != null && node.isTextual() ? node.asText() : "";
        } catch (Exception ignored) {
            return "";
        }
    }

    private String serializeStatusQuery(LoanDisbursementAdapter.DisbursementStatusQuery query) {
        try {
            java.util.LinkedHashMap<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("tranRefNo", query.tranRefNo());
            payload.put("paymentMode", query.paymentMode() == null ? null : query.paymentMode().name());
            payload.put("beneficiaryIfsc", query.beneficiaryIfsc());
            payload.put("priorStatusCheckCount", query.priorStatusCheckCount());
            return objectMapper.writeValueAsString(payload);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize disbursement status query.", exception);
        }
    }

    private String timeoutResponseJson(String message) {
        try {
            return objectMapper.writeValueAsString(java.util.Map.of(
                    "disposition", DisbursementDisposition.PENDING.name(),
                    "queryResolved", false,
                    "message", message == null ? "Status poll timed out." : message));
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize poll timeout response.", exception);
        }
    }

    @Transactional
    public LoanApplication resolveMockDisbursementOutcome(
            UUID applicationId,
            String actorUsername,
            MockDisbursementOutcome outcome
    ) {
        return resolveMockDisbursementOutcome(
                applicationId,
                actorUsername,
                null,
                CorrelationIdHolder.get(),
                outcome
        );
    }

    @Transactional
    public LoanApplication resolveMockDisbursementOutcome(
            UUID applicationId,
            String actorUsername,
            String actorIp,
            String correlationId,
            MockDisbursementOutcome outcome
    ) {
        // G01: mock outcomes are simulation-only. Enforced here — not on the wired adapter type —
        // so the boundary holds even after a real bank adapter replaces the mock.
        simulationGuard.requireSimulationAllowed("mock-disbursement-outcome");
        if (outcome == null) {
            throw new IllegalArgumentException("Disbursement outcome is required.");
        }

        LoanApplication application = loanApplicationQueryService.getApplication(applicationId);
        LoanAccount loanAccount = getRequiredLoanAccount(applicationId);
        if (loanAccount.getStatus() != LoanAccountStatus.DISBURSEMENT_REQUESTED) {
            throw new BusinessRuleViolationException(
                    "DISBURSEMENT_NOT_REQUESTED",
                    "Mock disbursement outcome can only be applied after a request is raised.",
                    Map.of()
            );
        }

        LoanDisbursementRequestLog latestRequest = requireLatestRequest(applicationId, loanAccount.getId());
        return disbursementOutcomeApplier.apply(
                application,
                loanAccount,
                latestRequest,
                toProviderOutcome(outcome),
                actorUsername,
                actorIp,
                correlationId
        );
    }

    private static DisbursementOutcomeApplier.ProviderOutcome toProviderOutcome(MockDisbursementOutcome outcome) {
        return switch (outcome) {
            case DISBURSED -> new DisbursementOutcomeApplier.ProviderOutcome(
                    DisbursementDisposition.SUCCESS, DisbursementDeclineKind.NONE, "0", null,
                    "Mock disbursement completed successfully.");
            case FAILED -> new DisbursementOutcomeApplier.ProviderOutcome(
                    DisbursementDisposition.FAILED, DisbursementDeclineKind.TECHNICAL, null, null,
                    "Mock disbursement failed in the simulated provider.");
            case PENDING_RECONCILIATION -> new DisbursementOutcomeApplier.ProviderOutcome(
                    DisbursementDisposition.PENDING, DisbursementDeclineKind.NONE, null, null,
                    "Mock disbursement is awaiting reconciliation.");
        };
    }

    private static DisbursementDisposition dispositionOf(LoanDisbursementRequestLog log) {
        // On initiation the provider status is the raw disposition name (SUCCESS/FAILED/PENDING).
        try {
            return DisbursementDisposition.valueOf(log.getProviderStatus());
        } catch (IllegalArgumentException ignored) {
            return DisbursementDisposition.PENDING;
        }
    }

    private LoanDisbursementRequestLog requireLatestRequest(UUID applicationId, UUID loanAccountId) {
        return loanDisbursementRequestLogRepository
                .findTopByLoanAccount_IdOrderByCreatedAtDesc(loanAccountId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Disbursement request log is not available for application id: " + applicationId
                ));
    }

    private LoanAccount getRequiredLoanAccount(UUID applicationId) {
        return loanAccountRepository.findDetailedByLoanApplication_Id(applicationId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Loan account is not available for application id: " + applicationId
                ));
    }

    private LoanAccount resolveLoanAccountForDisbursement(LoanApplication application) {
        return loanAccountRepository.findDetailedByLoanApplication_Id(application.getId())
                .orElseGet(() -> loanApplicationStatusWriter.ensureLoanAccountForApprovedApplication(application));
    }

    private void lockApplicationForDisbursement(UUID applicationId) {
        loanApplicationRepository.findByIdForUpdate(applicationId)
                .orElseThrow(() -> new ResourceNotFoundException("Unknown loan application id: " + applicationId));
    }

    /**
     * C06-phase-2 borrower-first prefix: initiation serializes against cross-LSP bank edits
     * on the shared borrower row before touching application/account/intent state. The
     * refresh discards any cached borrower copy that predates the lock wait.
     */
    private void lockBorrowerForDisbursement(UUID applicationId) {
        loanApplicationRepository.findBorrowerByApplicationIdForUpdate(applicationId)
                .map(lockedBorrower -> {
                    entityManager.refresh(lockedBorrower);
                    return lockedBorrower;
                })
                .orElseThrow(() -> new ResourceNotFoundException("Unknown loan application id: " + applicationId));
    }

    private static boolean isDisbursementAlreadyComplete(LoanApplicationStatus status) {
        return status == LoanApplicationStatus.DISBURSED
                || status == LoanApplicationStatus.UNDER_REPAYMENT
                || status == LoanApplicationStatus.CLOSED
                || status == LoanApplicationStatus.FORECLOSED;
    }
}
