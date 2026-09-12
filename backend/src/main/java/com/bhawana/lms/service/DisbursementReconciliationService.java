package com.bhawana.lms.service;

import com.bhawana.lms.common.api.error.ApiConflictException;
import com.bhawana.lms.common.api.error.ResourceNotFoundException;
import com.bhawana.lms.domain.DisbursementDisposition;
import com.bhawana.lms.domain.DisbursementIntent;
import com.bhawana.lms.domain.DisbursementIntentState;
import com.bhawana.lms.domain.DisbursementObservation;
import com.bhawana.lms.domain.DisbursementReconciliationQueueEntry;
import com.bhawana.lms.domain.DisbursementReconciliationReason;
import com.bhawana.lms.domain.LoanAccount;
import com.bhawana.lms.domain.LoanAccountStatus;
import com.bhawana.lms.domain.LoanApplication;
import com.bhawana.lms.domain.LoanDisbursementRequestLog;
import com.bhawana.lms.repo.DisbursementIntentRepository;
import com.bhawana.lms.repo.DisbursementObservationRepository;
import com.bhawana.lms.repo.DisbursementReconciliationQueueRepository;
import com.bhawana.lms.repo.LoanAccountRepository;
import com.bhawana.lms.repo.LoanApplicationRepository;
import com.bhawana.lms.repo.LoanDisbursementRequestLogRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Bounded reconciliation for unresolved money. Owns the explicit per-account queue
 * (UNKNOWN/REQUESTED/parked plus legacy mismatches, stranded terminals and conflicting
 * evidence), the backoff-bounded re-poll of the <em>original</em> reference to success, and
 * the evidence-backed manual resolution path.
 *
 * <p>Invariants: no re-initiation past the point of no return (no fresh reference, no reset
 * to an eligible initiation state); missing/contradictory instructions stay queued for
 * operators, never fabricated; manual resolution consumes a stored definitive matching
 * provider observation through the single terminal-result applier and is replay-safe.
 *
 * <p>Worker batch-loop isolation: this service exposes {@link #pollDueQueue} for the
 * parent to invoke and keeps every item failure isolated so one bad loan cannot poison a sweep.
 */
@Service
public class DisbursementReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(DisbursementReconciliationService.class);

    private final DisbursementReconciliationQueueRepository queueRepository;
    private final DisbursementObservationRepository observationRepository;
    private final DisbursementIntentRepository disbursementIntentRepository;
    private final LoanAccountRepository loanAccountRepository;
    private final LoanApplicationRepository loanApplicationRepository;
    private final LoanDisbursementRequestLogRepository loanDisbursementRequestLogRepository;
    private final DisbursementIntentWorkflowService disbursementIntentWorkflowService;
    private final LoanDisbursementCommandService loanDisbursementCommandService;
    private final DisbursementOutcomeApplier disbursementOutcomeApplier;
    private final DisbursementObservationWriter observationWriter;
    private final DisbursementReconciliationProperties properties;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    @SuppressWarnings("checkstyle:ParameterNumber")
    public DisbursementReconciliationService(
            DisbursementReconciliationQueueRepository queueRepository,
            DisbursementObservationRepository observationRepository,
            DisbursementIntentRepository disbursementIntentRepository,
            LoanAccountRepository loanAccountRepository,
            LoanApplicationRepository loanApplicationRepository,
            LoanDisbursementRequestLogRepository loanDisbursementRequestLogRepository,
            DisbursementIntentWorkflowService disbursementIntentWorkflowService,
            LoanDisbursementCommandService loanDisbursementCommandService,
            DisbursementOutcomeApplier disbursementOutcomeApplier,
            DisbursementObservationWriter observationWriter,
            DisbursementReconciliationProperties properties,
            com.fasterxml.jackson.databind.ObjectMapper objectMapper,
            TransactionTemplate transactionTemplate
    ) {
        this.queueRepository = queueRepository;
        this.observationRepository = observationRepository;
        this.disbursementIntentRepository = disbursementIntentRepository;
        this.loanAccountRepository = loanAccountRepository;
        this.loanApplicationRepository = loanApplicationRepository;
        this.loanDisbursementRequestLogRepository = loanDisbursementRequestLogRepository;
        this.disbursementIntentWorkflowService = disbursementIntentWorkflowService;
        this.loanDisbursementCommandService = loanDisbursementCommandService;
        this.disbursementOutcomeApplier = disbursementOutcomeApplier;
        this.observationWriter = observationWriter;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
    }

    /** Queue counts by reason plus the oldest unresolved age (consumed by a metrics exporter). */
    @Transactional(readOnly = true)
    public QueueSummary queueSummary() {
        Map<DisbursementReconciliationReason, Long> byReason =
                new EnumMap<>(DisbursementReconciliationReason.class);
        for (DisbursementReconciliationReason reason : DisbursementReconciliationReason.values()) {
            byReason.put(reason, 0L);
        }
        long total = 0L;
        for (DisbursementReconciliationQueueRepository.ReasonCount row : queueRepository.countByReason()) {
            byReason.put(row.getReason(), row.getEntryCount());
            total += row.getEntryCount();
        }
        Instant oldest = queueRepository.findOldestFirstSeenAt().orElse(null);
        long oldestAgeSeconds = oldest == null
                ? 0L : Math.max(0L, Duration.between(oldest, Instant.now()).toSeconds());
        return new QueueSummary(total, Map.copyOf(byReason), oldestAgeSeconds, oldest);
    }

    /**
     * Bounded oldest-first queue page for operators, with a true row offset (stable
     * {@code firstSeenAt}, {@code loanAccountId} order) so non-multiple offsets page
     * exactly instead of flooring to a page start.
     */
    @Transactional(readOnly = true)
    public List<QueueEntryView> queuePage(int limit, int offset) {
        int boundedLimit = Math.max(1, Math.min(limit <= 0 ? properties.getQueuePageSize() : limit,
                properties.getQueuePageSize()));
        int boundedOffset = Math.max(0, offset);
        return queueRepository.findPageByAgeOffset(boundedLimit, boundedOffset).stream()
                .map(entry -> new QueueEntryView(
                        entry.getLoanAccountId(),
                        entry.getLoanAccount().getLoanApplication().getId(),
                        entry.getIntent() == null ? null : entry.getIntent().getId(),
                        entry.getTranRefNo(),
                        entry.getReason(),
                        entry.getNextPollAt(),
                        entry.getPollCount(),
                        entry.getFirstSeenAt(),
                        entry.getLastObservationAt(),
                        entry.getOwner(),
                        entry.isEscalated(),
                        Math.max(0L, Duration.between(entry.getFirstSeenAt(), Instant.now()).toSeconds()),
                        entry.getDetails()))
                .toList();
    }

    /** Operator takes ownership of a queue entry. */
    public void claimEntry(UUID loanAccountId, String owner) {
        transactionTemplate.executeWithoutResult(tx -> {
            // Same app→account lock order as every other queue mutation so concurrent
            // claim/backoff/reject/apply updates serialize instead of colliding.
            LoanAccount probe = loanAccountRepository.findById(loanAccountId).orElseThrow(
                    () -> new ResourceNotFoundException(
                            "Reconciliation queue entry is not available for loan account id: " + loanAccountId));
            loanApplicationRepository.findByIdForUpdate(probe.getLoanApplication().getId()).orElseThrow(
                    () -> new ResourceNotFoundException("Unknown loan application for loan account id: "
                            + loanAccountId));
            loanAccountRepository.findByIdForUpdate(loanAccountId).orElseThrow(
                    () -> new ResourceNotFoundException(
                            "Reconciliation queue entry is not available for loan account id: " + loanAccountId));
            DisbursementReconciliationQueueEntry entry = queueRepository.findById(loanAccountId)
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Reconciliation queue entry is not available for loan account id: " + loanAccountId));
            entry.claim(owner);
            queueRepository.save(entry);
        });
    }

    /**
     * Bounded sweep over due entries: stranded terminals go through the stranded-terminal repair path,
     * pollable entries re-poll their original reference (REQUESTED and parked alike — never a
     * fresh initiation), and blocked entries back off in the queue with their reason intact.
     * Each item runs isolated; one failure never aborts the sweep.
     *
     * @return number of entries that reached an applied terminal/parked outcome or repair.
     */
    public int pollDueQueue(String actorUsername, String actorIp, String correlationId) {
        int batchSize = Math.max(2, properties.getQueuePollBatchSize());
        // Bounded discovery reserve: inventory of never-queued submitted accounts gets a
        // fixed slice of every sweep so a permanently full due batch cannot starve it forever.
        // The batch floor of two guarantees the reserve below is always at least one.
        int reserve = Math.max(1, batchSize / 4);
        int dueLimit = Math.max(1, batchSize - reserve);
        List<UUID> dueIds = transactionTemplate.execute(tx -> {
            List<UUID> ids = new java.util.ArrayList<>(queueRepository
                    .findDueForPoll(Instant.now(), PageRequest.of(0, dueLimit))
                    .stream().map(DisbursementReconciliationQueueEntry::getLoanAccountId).toList());
            // Bounded discovery: submitted but never-queued accounts (legacy evidence)
            // join the same sweep so missing/contradictory instructions become visible instead
            // of lingering outside the queue. Fresh intents with no submitted call are excluded
            // by the query itself. The batch floor keeps this slice non-empty.
            for (UUID id : loanAccountRepository.findUnqueuedSubmittedIds(
                    PageRequest.of(0, reserve))) {
                if (!ids.contains(id)) {
                    ids.add(id);
                }
            }
            return ids;
        });
        if (dueIds == null || dueIds.isEmpty()) {
            return 0;
        }
        int resolved = 0;
        for (UUID loanAccountId : dueIds) {
            try {
                if (pollOneQueuedAccount(loanAccountId, actorUsername, actorIp, correlationId)) {
                    resolved++;
                }
            } catch (RuntimeException exception) {
                log.warn("Reconciliation poll failed for loan account {}: {}",
                        loanAccountId, exception.getMessage());
            }
        }
        return resolved;
    }

    private boolean pollOneQueuedAccount(
            UUID loanAccountId, String actorUsername, String actorIp, String correlationId) {
        Optional<UUID> applicationId = transactionTemplate.execute(tx -> loanAccountRepository
                .findById(loanAccountId).map(account -> account.getLoanApplication().getId()));
        if (applicationId == null || applicationId.isEmpty()) {
            transactionTemplate.executeWithoutResult(tx -> queueRepository.findById(loanAccountId)
                    .ifPresent(queueRepository::delete));
            return false;
        }
        UUID appId = applicationId.get();

        // Stranded terminals: a terminal intent of THIS account whose loan still reads
        // REQUESTED — repaired from stored evidence without re-initiation (the repair path owns
        // application). Only this account's intents are ever repaired here; unrelated
        // stranded rows wait for their own sweep turn.
        Boolean stranded = transactionTemplate.execute(tx -> {
            LoanAccount account = loanAccountRepository.findById(loanAccountId).orElse(null);
            if (account == null
                    || account.getStatus() != LoanAccountStatus.DISBURSEMENT_REQUESTED) {
                return null;
            }
            return disbursementIntentRepository.findLiveByLoanAccountId(loanAccountId).isEmpty()
                    && hasTerminalIntent(loanAccountId);
        });
        if (Boolean.TRUE.equals(stranded)) {
            List<UUID> ids = transactionTemplate.execute(tx -> terminalIntentIdsFor(loanAccountId));
            boolean repaired = false;
            if (ids != null) {
                for (UUID intentId : ids) {
                    try {
                        Boolean applied = transactionTemplate.execute(
                                tx -> disbursementIntentWorkflowService.repairStrandedTerminalIntent(intentId));
                        if (Boolean.TRUE.equals(applied)) {
                            repaired = true;
                        }
                    } catch (RuntimeException exception) {
                        log.warn("Stranded repair failed during reconciliation for intent {}: {}",
                                intentId, exception.getMessage());
                    }
                }
            }
            if (repaired) {
                return true;
            }
            backoffBlockedEntry(loanAccountId, DisbursementReconciliationReason.STRANDED_TERMINAL,
                    "Stranded terminal repair outstanding; retrying with backoff.");
            return false;
        }

        Optional<DisbursementIntentWorkflowService.StatusPollContext> pollContext =
                disbursementIntentWorkflowService.loadReconciliationPollContext(appId);
        if (pollContext.isEmpty()) {
            // Either resolved concurrently (terminal account — drop the row, unless it is held
            // for conflicting evidence, which stays operator-visible) or blocked by
            // missing/contradictory instruction (stay queued for operators, no provider call,
            // no fabricated history).
            Boolean cleared = transactionTemplate.execute(tx -> {
                LoanAccount account = loanAccountRepository.findById(loanAccountId).orElse(null);
                if (account == null) {
                    queueRepository.findById(loanAccountId).ifPresent(queueRepository::delete);
                    return true;
                }
                if (account.getStatus() != LoanAccountStatus.DISBURSEMENT_REQUESTED
                        && account.getStatus() != LoanAccountStatus.DISBURSEMENT_PENDING_RECONCILIATION) {
                    Optional<DisbursementReconciliationQueueEntry> held =
                            queueRepository.findById(loanAccountId);
                    if (held.isPresent()
                            && held.get().getReason()
                                    == DisbursementReconciliationReason.CONFLICTING_EVIDENCE) {
                        return false;
                    }
                    queueRepository.findById(loanAccountId).ifPresent(queueRepository::delete);
                    return true;
                }
                return false;
            });
            if (Boolean.TRUE.equals(cleared)) {
                return false;
            }
            // A held conflict is never overwritten by backoff: it stays put with its reason.
            Boolean heldConflict = transactionTemplate.execute(tx -> queueRepository.findById(loanAccountId)
                    .map(entry -> entry.getReason() == DisbursementReconciliationReason.CONFLICTING_EVIDENCE)
                    .orElse(false));
            if (Boolean.TRUE.equals(heldConflict)) {
                return false;
            }
            backoffBlockedEntry(loanAccountId, DisbursementReconciliationReason.LEGACY_MISMATCH,
                    "Reconciliation poll blocked: missing or contradictory payment instruction for the "
                            + "original reference; manual evidence-backed resolution required, no new reference issued.");
            return false;
        }
        return loanDisbursementCommandService.pollWithCapturedContext(
                pollContext.get(), actorUsername, actorIp, correlationId);
    }

    /** Terminal intent ids of exactly one loan account, oldest first. */
    private List<UUID> terminalIntentIdsFor(UUID loanAccountId) {
        List<UUID> ids = new java.util.ArrayList<>();
        disbursementIntentRepository
                .findTopByLoanAccount_IdAndStateOrderByCreatedAtDesc(
                        loanAccountId, DisbursementIntentState.SUCCEEDED)
                .ifPresent(intent -> ids.add(intent.getId()));
        disbursementIntentRepository
                .findTopByLoanAccount_IdAndStateOrderByCreatedAtDesc(
                        loanAccountId, DisbursementIntentState.FAILED)
                .ifPresent(intent -> {
                    if (!ids.contains(intent.getId())) {
                        ids.add(intent.getId());
                    }
                });
        return ids;
    }

    private boolean hasTerminalIntent(UUID loanAccountId) {
        return disbursementIntentRepository.findTopByLoanAccount_IdAndStateOrderByCreatedAtDesc(
                loanAccountId, DisbursementIntentState.SUCCEEDED).isPresent()
                || disbursementIntentRepository.findTopByLoanAccount_IdAndStateOrderByCreatedAtDesc(
                        loanAccountId, DisbursementIntentState.FAILED).isPresent();
    }

    private void backoffBlockedEntry(
            UUID loanAccountId, DisbursementReconciliationReason reason, String details) {
        transactionTemplate.executeWithoutResult(tx -> {
            // App→account lock order (never reversed) so this competes cleanly with the
            // locked poll/apply paths mutating the same row.
            LoanAccount probe = loanAccountRepository.findById(loanAccountId).orElse(null);
            if (probe == null) {
                queueRepository.findById(loanAccountId).ifPresent(queueRepository::delete);
                return;
            }
            loanApplicationRepository.findByIdForUpdate(probe.getLoanApplication().getId()).orElse(null);
            LoanAccount account = loanAccountRepository.findByIdForUpdate(loanAccountId).orElse(null);
            if (account == null) {
                queueRepository.findById(loanAccountId).ifPresent(queueRepository::delete);
                return;
            }
            // A blocked sweep must leave operator-visible state even when no queue row
            // exists yet (e.g. legacy evidence with no prior observation). A held
            // conflicting-evidence reason is never overwritten by backoff. The refresh keeps
            // the schedule; exactly one attempt advance bounds the next sweep. New rows seed
            // first-seen from the original evidence, not from discovery time.
            Optional<DisbursementReconciliationQueueEntry> held = queueRepository.findById(loanAccountId);
            DisbursementReconciliationReason keepReason =
                    held.isPresent()
                            && held.get().getReason() == DisbursementReconciliationReason.CONFLICTING_EVIDENCE
                        ? DisbursementReconciliationReason.CONFLICTING_EVIDENCE
                        : reason;
            DisbursementIntent liveIntent = disbursementIntentRepository
                    .findLiveByLoanAccountId(account.getId()).orElse(null);
            LoanDisbursementRequestLog latest = loanDisbursementRequestLogRepository
                    .findTopByLoanAccount_IdOrderByCreatedAtDesc(account.getId()).orElse(null);
            String ref = liveIntent != null ? liveIntent.getTranRefNo()
                    : (latest == null ? null : latest.getTranRefNo());
            if (held.isEmpty()) {
                observationWriter.enqueueAt(
                        account, liveIntent, ref, keepReason, details, earliestEvidence(account.getId()));
            } else {
                observationWriter.enqueue(account, liveIntent, ref, keepReason, details);
            }
            observationWriter.recordAttempt(loanAccountId);
        });
    }

    /**
     * Earliest original evidence stamp for an account, consolidated on the writer so the
     * default queue insert and blocked-discovery insert share one durable-only source (first
     * stored request or intent {@code createdAt}, never live borrower fields).
     */
    private Instant earliestEvidence(UUID loanAccountId) {
        return observationWriter.resolveEarliestEvidence(loanAccountId);
    }

    /**
     * Claimed poll sequences on the latest stored request that have no result
     * observation for the current reference: crashed attempts stay visible as
     * attempted-with-missing-result. Recovery claims a fresh sequence; the gap is never
     * backfilled with a fabricated response.
     */
    @Transactional(readOnly = true)
    public List<Integer> findMissingPollResults(UUID loanAccountId) {
        LoanDisbursementRequestLog latest = loanDisbursementRequestLogRepository
                .findTopByLoanAccount_IdOrderByCreatedAtDesc(loanAccountId).orElse(null);
        if (latest == null || latest.getTranRefNo() == null) {
            return List.of();
        }
        java.util.Set<Integer> recorded = new java.util.HashSet<>();
        for (DisbursementObservation observation : observationRepository
                .findByLoanAccount_IdAndTranRefNoAndKind(
                        loanAccountId, latest.getTranRefNo(), com.bhawana.lms.domain.DisbursementObservationKind.POLL)) {
            if (observation.getPollSeq() != null) {
                recorded.add(observation.getPollSeq());
            }
        }
        List<Integer> missing = new java.util.ArrayList<>();
        for (int seq = 1; seq <= latest.getStatusCheckCount(); seq++) {
            if (!recorded.contains(seq)) {
                missing.add(seq);
            }
        }
        return missing;
    }

    /**
     * Evidence-backed manual resolution. Consumes one stored definitive matching provider
     * observation (resolved SUCCESS/FAILED for this loan's current reference and frozen
     * instruction) through the single terminal-result applier, under application→account→intent locks.
     *
     * <p>Replay-safe: re-presenting the accepted evidence after the loan resolved returns
     * success without a second transition/event — but only for definitive evidence whose
     * verdict matches the accepted outcome. A PENDING observation or a contradictory terminal
     * verdict on a resolved loan is rejected and the contradiction stays operator-visible.
     *
     * <p>Rejections never invent outcomes, and the rejection signal itself is persisted in a
     * separate committed transaction: the decision tx rolls back (no financial writes), then
     * the queue write commits, then the conflict is reported.
     */
    public boolean resolveManually(
            UUID applicationId,
            UUID observationId,
            String actorUsername,
            String actorIp,
            String correlationId
    ) {
        try {
            return Boolean.TRUE.equals(transactionTemplate.execute(tx -> doResolveManually(
                    applicationId, observationId, actorUsername, actorIp, correlationId)));
        } catch (ReconciliationRejection rejection) {
            persistRejection(rejection);
            throw new ApiConflictException(rejection.errorCode(), rejection.getMessage());
        }
    }

    private boolean doResolveManually(
            UUID applicationId,
            UUID observationId,
            String actorUsername,
            String actorIp,
            String correlationId
    ) {
        LoanApplication application = loanApplicationRepository.findByIdForUpdate(applicationId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Unknown loan application id: " + applicationId));
        LoanAccount account = loanAccountRepository.findByLoanApplication_IdForUpdate(applicationId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Loan account is not available for application id: " + applicationId));
        DisbursementObservation observation = observationRepository.findById(observationId)
                .orElse(null);
        if (observation == null) {
            throw reject(account, null, null,
                    accountReconcilable(account) ? DisbursementReconciliationReason.LEGACY_MISMATCH : null,
                    "Manual resolution rejected: no stored provider observation with id " + observationId + ".",
                    "RECONCILIATION_EVIDENCE_MISSING",
                    "No stored provider observation with id " + observationId
                            + "; manual resolution requires bank evidence, never an invented outcome.");
        }
        if (!observation.getLoanAccount().getId().equals(account.getId())) {
            throw reject(account, null, currentReferenceFor(account),
                    DisbursementReconciliationReason.CONFLICTING_EVIDENCE,
                    "Manual resolution rejected: observation " + observationId + " belongs to a different loan.",
                    "RECONCILIATION_REFERENCE_MISMATCH",
                    "Stored observation belongs to a different loan; it cannot resolve this disbursement.");
        }
        LoanDisbursementRequestLog latestRequest = loanDisbursementRequestLogRepository
                .findTopByLoanAccount_IdOrderByCreatedAtDesc(account.getId()).orElse(null);
        DisbursementIntent liveIntent = disbursementIntentRepository
                .findLiveByLoanAccountIdForUpdate(account.getId()).orElse(null);

        if (account.getStatus() == LoanAccountStatus.DISBURSED
                || account.getStatus() == LoanAccountStatus.DISBURSEMENT_FAILED) {
            return replayOnResolvedLoan(account, liveIntent, latestRequest, observation);
        }
        if (account.getStatus() != LoanAccountStatus.DISBURSEMENT_REQUESTED
                && account.getStatus() != LoanAccountStatus.DISBURSEMENT_PENDING_RECONCILIATION) {
            throw reject(account, liveIntent, currentReference(liveIntent, latestRequest), null,
                    null,
                    "RECONCILIATION_NOT_REQUIRED",
                    "Loan account is " + account.getStatus()
                            + "; only in-flight or parked disbursements accept manual reconciliation.");
        }
        if (!observation.isDefinitive()) {
            throw reject(account, liveIntent, currentReference(liveIntent, latestRequest),
                    DisbursementReconciliationReason.LEGACY_MISMATCH,
                    "Manual resolution rejected: observation " + observationId
                            + " is not definitive provider evidence.",
                    "RECONCILIATION_EVIDENCE_NOT_DEFINITIVE",
                    "Stored observation is not definitive (resolved SUCCESS/FAILED) evidence; "
                            + "manual resolution requires a definitive matching provider observation.");
        }
        String currentRef = currentReference(liveIntent, latestRequest);
        if (currentRef == null || !Objects.equals(observation.getTranRefNo(), currentRef)
                || (latestRequest != null
                        && !Objects.equals(observation.getTranRefNo(), latestRequest.getTranRefNo()))) {
            throw reject(account, liveIntent, currentRef,
                    DisbursementReconciliationReason.CONFLICTING_EVIDENCE,
                    "Manual resolution rejected: observation " + observationId
                            + " does not match the current reference " + currentRef + ".",
                    "RECONCILIATION_REFERENCE_MISMATCH",
                    "Stored observation reference does not match the current disbursement reference; "
                            + "only matching evidence may resolve it.");
        }
        if (!instructionMatches(liveIntent, latestRequest, observation)) {
            throw reject(account, liveIntent, currentRef,
                    DisbursementReconciliationReason.CONFLICTING_EVIDENCE,
                    "Manual resolution rejected: observation " + observationId
                            + " contradicts the frozen payment instruction.",
                    "RECONCILIATION_REFERENCE_MISMATCH",
                    "Stored observation contradicts the frozen payment instruction; "
                            + "live borrower data is never substituted.");
        }
        if (latestRequest == null) {
            throw reject(account, liveIntent, currentRef,
                    DisbursementReconciliationReason.LEGACY_MISMATCH,
                    "Manual resolution rejected: no stored disbursement request carries reference "
                            + currentRef + ".",
                    "RECONCILIATION_EVIDENCE_MISSING",
                    "No stored disbursement request carries the current reference; "
                            + "manual resolution requires stored evidence.");
        }

        disbursementOutcomeApplier.apply(
                application,
                account,
                latestRequest,
                new DisbursementOutcomeApplier.ProviderOutcome(
                        observation.getDisposition(),
                        observation.getDeclineKind(),
                        observation.getActCode(),
                        observation.getBankRrn(),
                        "Manual reconciliation from stored provider observation " + observation.getId()
                                + " for reference " + observation.getTranRefNo() + "."),
                actorUsername,
                actorIp,
                correlationId);
        if (liveIntent != null) {
            liveIntent.recordProviderResponse(
                    observation.getDisposition() == DisbursementDisposition.SUCCESS
                            ? DisbursementIntentState.SUCCEEDED : DisbursementIntentState.FAILED,
                    observation.getProviderRequestId(),
                    observation.getActCode(),
                    observation.getBankRrn(),
                    observation.getDeclineKind());
            disbursementIntentRepository.save(liveIntent);
        }
        observationWriter.clear(account.getId());
        return true;
    }

    /**
     * Replay on an already-resolved loan. Succeeds idempotently only for definitive
     * evidence whose verdict matches the accepted outcome on the applied reference with a
     * matching frozen instruction. Anything else — a PENDING observation or a contradictory
     * terminal verdict — is rejected, and contradictions stay operator-visible.
     */
    private boolean replayOnResolvedLoan(
            LoanAccount account,
            DisbursementIntent liveIntent,
            LoanDisbursementRequestLog latestRequest,
            DisbursementObservation observation
    ) {
        String appliedRef = latestRequest == null ? null : latestRequest.getTranRefNo();
        boolean verdictMatches = (observation.getDisposition() == DisbursementDisposition.SUCCESS
                        && account.getStatus() == LoanAccountStatus.DISBURSED)
                || (observation.getDisposition() == DisbursementDisposition.FAILED
                        && account.getStatus() == LoanAccountStatus.DISBURSEMENT_FAILED);
        if (observation.isDefinitive()
                && appliedRef != null
                && Objects.equals(observation.getTranRefNo(), appliedRef)
                && verdictMatches
                && instructionMatches(liveIntent, latestRequest, observation)) {
            observationWriter.clearIfNonConflicting(account.getId());
            return true;
        }
        if (observation.isDefinitive()
                && (Objects.equals(observation.getTranRefNo(), appliedRef) || appliedRef == null)
                && (observation.getDisposition() == DisbursementDisposition.SUCCESS
                        || observation.getDisposition() == DisbursementDisposition.FAILED)
                && !verdictMatches) {
            throw reject(account, liveIntent, appliedRef,
                    DisbursementReconciliationReason.CONFLICTING_EVIDENCE,
                    "Manual resolution rejected: stored " + observation.getDisposition() + " observation "
                            + observation.getId() + " contradicts the accepted " + account.getStatus()
                            + " outcome for reference " + appliedRef + ".",
                    "RECONCILIATION_REFERENCE_MISMATCH",
                    "Stored observation contradicts the accepted loan outcome; "
                            + "contradictory evidence stays operator-visible, never applied.");
        }
        throw reject(account, liveIntent, appliedRef,
                null,
                null,
                "RECONCILIATION_EVIDENCE_NOT_DEFINITIVE",
                "Stored observation is not the accepted definitive evidence for this resolved loan; "
                        + "manual resolution requires a definitive matching provider observation.");
    }

    private static boolean accountReconcilable(LoanAccount account) {
        return account.getStatus() == LoanAccountStatus.DISBURSEMENT_REQUESTED
                || account.getStatus() == LoanAccountStatus.DISBURSEMENT_PENDING_RECONCILIATION;
    }

    private String currentReferenceFor(LoanAccount account) {
        DisbursementIntent liveIntent =
                disbursementIntentRepository.findLiveByLoanAccountId(account.getId()).orElse(null);
        LoanDisbursementRequestLog latest = loanDisbursementRequestLogRepository
                .findTopByLoanAccount_IdOrderByCreatedAtDesc(account.getId()).orElse(null);
        return currentReference(liveIntent, latest);
    }

    /**
     * Frozen-instruction match for manual evidence. The observation must agree with the
     * live intent when one exists AND with the original stored request's own keys — the same
     * conjunction the common poll loader enforces. A V111-synthesized intent alone can
     * never authorize an outcome: when the stored request contradicts the intent, the evidence
     * is rejected. A missing verifiable side cannot prove a match, so it rejects rather than
     * assumes.
     */
    private boolean instructionMatches(
            DisbursementIntent liveIntent,
            LoanDisbursementRequestLog latestRequest,
            DisbursementObservation observation
    ) {
        boolean intentSide = true;
        if (liveIntent != null) {
            intentSide = normalize(observation.getBeneficiaryIfsc())
                            .equals(normalize(liveIntent.getBeneficiaryIfsc()))
                    && observation.getPaymentMode() == liveIntent.getPaymentMode()
                    && Objects.equals(observation.getBeneficiaryAccountNumber(),
                            liveIntent.getBeneficiaryAccountNumber());
            if (!intentSide) {
                return false;
            }
        }
        if (latestRequest == null) {
            // No original stored request to crosscheck: only acceptable when a live intent
            // already matched above; otherwise there is nothing verifiable at all.
            return liveIntent != null;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode payload =
                    objectMapper.readTree(latestRequest.getRequestPayloadJson());
            String ifsc = textOrNull(payload, "beneficiaryIfsc");
            String beneficiaryAccount = textOrNull(payload, "beneficiaryAccountNumber");
            String mode = textOrNull(payload, "paymentMode");
            String payloadRef = textOrNull(payload, "tranRefNo");
            if (ifsc == null || beneficiaryAccount == null || mode == null) {
                return false;
            }
            return normalize(observation.getBeneficiaryIfsc()).equals(normalize(ifsc))
                    && Objects.equals(observation.getBeneficiaryAccountNumber(), beneficiaryAccount)
                    && observation.getPaymentMode() != null
                    && observation.getPaymentMode().name().equals(mode)
                    && (payloadRef == null || Objects.equals(payloadRef, latestRequest.getTranRefNo()));
        } catch (Exception invalid) {
            return false;
        }
    }

    private static String textOrNull(com.fasterxml.jackson.databind.JsonNode payload, String field) {
        com.fasterxml.jackson.databind.JsonNode node = payload.get(field);
        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            return null;
        }
        return node.asText();
    }

    private static ReconciliationRejection reject(
            LoanAccount account,
            DisbursementIntent liveIntent,
            String ref,
            DisbursementReconciliationReason queueReason,
            String queueDetails,
            String errorCode,
            String message
    ) {
        return new ReconciliationRejection(
                account.getId(),
                liveIntent == null ? null : liveIntent.getId(),
                ref, queueReason, queueDetails, errorCode, message);
    }

    /**
     * Persists a manual-resolution rejection decision in its own committed transaction
     * after the decision transaction rolled back. Financial writes from the rejected attempt
     * stay rolled back; only the operator-queue signal commits.
     */
    private void persistRejection(ReconciliationRejection rejection) {
        if (rejection.queueReason() == null) {
            return;
        }
        transactionTemplate.executeWithoutResult(tx -> {
            // Same app→account lock order as the decision path, so the rejection signal
            // cannot collide with (or silently lose to) a concurrent outcome application.
            LoanAccount probe = loanAccountRepository.findById(rejection.accountId()).orElse(null);
            if (probe == null) {
                return;
            }
            loanApplicationRepository.findByIdForUpdate(probe.getLoanApplication().getId()).orElse(null);
            LoanAccount account = loanAccountRepository.findByIdForUpdate(rejection.accountId()).orElse(null);
            if (account == null) {
                return;
            }
            DisbursementIntent liveIntent = disbursementIntentRepository
                    .findLiveByLoanAccountId(account.getId())
                    .filter(intent -> intent.getId().equals(rejection.intentId()))
                    .orElse(null);
            observationWriter.enqueue(
                    account, liveIntent, rejection.ref(),
                    rejection.queueReason(), rejection.queueDetails());
        });
    }

    /** Decision-transaction rollback carrier: rolls the decision back, then the queue write commits. */
    private static final class ReconciliationRejection extends RuntimeException {
        private final UUID accountId;
        private final UUID intentId;
        private final String ref;
        private final DisbursementReconciliationReason queueReason;
        private final String queueDetails;
        private final String errorCode;

        private ReconciliationRejection(
                UUID accountId,
                UUID intentId,
                String ref,
                DisbursementReconciliationReason queueReason,
                String queueDetails,
                String errorCode,
                String message
        ) {
            super(message);
            this.accountId = accountId;
            this.intentId = intentId;
            this.ref = ref;
            this.queueReason = queueReason;
            this.queueDetails = queueDetails;
            this.errorCode = errorCode;
        }

        private UUID accountId() {
            return accountId;
        }

        private UUID intentId() {
            return intentId;
        }

        private String ref() {
            return ref;
        }

        private DisbursementReconciliationReason queueReason() {
            return queueReason;
        }

        private String queueDetails() {
            return queueDetails;
        }

        private String errorCode() {
            return errorCode;
        }
    }

    private static String currentReference(
            DisbursementIntent liveIntent, LoanDisbursementRequestLog latestRequest) {
        if (liveIntent != null) {
            return liveIntent.getTranRefNo();
        }
        return latestRequest == null ? null : latestRequest.getTranRefNo();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(java.util.Locale.ROOT);
    }

    public record QueueSummary(
            long total,
            Map<DisbursementReconciliationReason, Long> byReason,
            long oldestAgeSeconds,
            Instant oldestFirstSeenAt) {
    }

    public record QueueEntryView(
            UUID loanAccountId,
            UUID applicationId,
            UUID intentId,
            String tranRefNo,
            DisbursementReconciliationReason reason,
            Instant nextPollAt,
            int pollCount,
            Instant firstSeenAt,
            Instant lastObservationAt,
            String owner,
            boolean escalated,
            long ageSeconds,
            String details) {
    }
}
