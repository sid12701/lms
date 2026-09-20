package com.bhawana.lms.service;

import com.bhawana.lms.common.api.error.ApiConflictException;
import com.bhawana.lms.common.api.error.BusinessRuleViolationException;
import com.bhawana.lms.common.correlation.CorrelationIdHolder;
import com.bhawana.lms.common.util.PersistedTimestamp;
import com.bhawana.lms.common.util.Strings;
import com.bhawana.lms.domain.Borrower;
import com.bhawana.lms.domain.DisbursementDeclineKind;
import com.bhawana.lms.domain.DisbursementDisposition;
import com.bhawana.lms.domain.DisbursementIntent;
import com.bhawana.lms.domain.DisbursementIntentState;
import com.bhawana.lms.domain.DisbursementObservationKind;
import com.bhawana.lms.domain.DisbursementPaymentMode;
import com.bhawana.lms.domain.DisbursementReconciliationReason;
import com.bhawana.lms.domain.LoanAccount;
import com.bhawana.lms.domain.LoanAccountStatus;
import com.bhawana.lms.domain.LoanApplication;
import com.bhawana.lms.domain.LoanApplicationStatus;
import com.bhawana.lms.domain.LoanDisbursementRequestLog;
import com.bhawana.lms.domain.LoanEventType;
import com.bhawana.lms.domain.LspStatus;
import com.bhawana.lms.repo.ClaimToken;
import com.bhawana.lms.repo.DisbursementIntentRepository;import com.bhawana.lms.repo.LoanAccountRepository;
import com.bhawana.lms.repo.LoanApplicationRepository;
import com.bhawana.lms.repo.LoanDisbursementRequestLogRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Durable disbursement intent workflow (the only disbursement initiation path):
 * Tx-A intent creation, out-of-transaction provider calls, and Tx-B outcome persistence.
 * {@link LoanDisbursementCommandService} always delegates here.
 */
@Service
public class DisbursementIntentWorkflowService {

    private static final Logger log = LoggerFactory.getLogger(DisbursementIntentWorkflowService.class);

    private final DisbursementIntentRepository disbursementIntentRepository;
    private final LoanAccountRepository loanAccountRepository;
    private final LoanApplicationRepository loanApplicationRepository;
    private final LoanDisbursementRequestLogRepository loanDisbursementRequestLogRepository;
    private final LoanDisbursementAdapter loanDisbursementAdapter;
    private final LoanEventLog loanEventLog;
    private final LoanApplicationQueryService loanApplicationQueryService;
    private final DisbursementIntentWorkflowProperties properties;
    private final DisbursementOutcomeApplier disbursementOutcomeApplier;
    private final LoanRepaymentScheduleService loanRepaymentScheduleService;
    private final DisbursementObservationWriter observationWriter;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final DisbursementProviderLatency providerLatency;
    private final Counter claimFailureCounter;
    private final Counter claimScanFailureCounter;
    private final Counter repairFailureCounter;
    private final Counter repairScanFailureCounter;
    private final EntityManager entityManager;
    /**
     * process-unique claim owner (configured prefix + host + startup UUID). All claims from
     * this process share it; the attempt count distinguishes generations. Two threads in the same
     * process share the owner and must still pass the attempt/state checks.
     */
    private final String workerOwner;

    public DisbursementIntentWorkflowService(
            DisbursementIntentRepository disbursementIntentRepository,
            LoanAccountRepository loanAccountRepository,
            LoanApplicationRepository loanApplicationRepository,
            LoanDisbursementRequestLogRepository loanDisbursementRequestLogRepository,
            LoanDisbursementAdapter loanDisbursementAdapter,
            LoanEventLog loanEventLog,
            LoanApplicationQueryService loanApplicationQueryService,
            DisbursementIntentWorkflowProperties properties,
            DisbursementOutcomeApplier disbursementOutcomeApplier,
            LoanRepaymentScheduleService loanRepaymentScheduleService,
            DisbursementObservationWriter observationWriter,
            ObjectMapper objectMapper,
            TransactionTemplate transactionTemplate,
            DisbursementProviderLatency providerLatency,
            MeterRegistry meterRegistry,
            EntityManager entityManager
    ) {
        this.disbursementIntentRepository = disbursementIntentRepository;
        this.loanAccountRepository = loanAccountRepository;
        this.loanApplicationRepository = loanApplicationRepository;
        this.loanDisbursementRequestLogRepository = loanDisbursementRequestLogRepository;
        this.loanDisbursementAdapter = loanDisbursementAdapter;
        this.loanEventLog = loanEventLog;
        this.loanApplicationQueryService = loanApplicationQueryService;
        this.properties = properties;
        this.disbursementOutcomeApplier = disbursementOutcomeApplier;
        this.loanRepaymentScheduleService = loanRepaymentScheduleService;
        this.observationWriter = observationWriter;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
        this.providerLatency = providerLatency;
        // low-cardinality recovery failure counters (scope tag is scan|item only —
        // never intent, account, or request identifiers). Backlog gauges stay with the
        // DisbursementIntentMetrics.
        this.claimFailureCounter = Counter.builder("lms.disbursement.workflow.claim.failures")
                .description("Claimable-intent recovery failures; later intents still execute")
                .tag("scope", "item")
                .register(meterRegistry);
        this.claimScanFailureCounter = Counter.builder("lms.disbursement.workflow.claim.failures")
                .description("Claimable-intent scan failures; the batch contributes zero and the tick continues")
                .tag("scope", "scan")
                .register(meterRegistry);
        this.repairFailureCounter = Counter.builder("lms.disbursement.workflow.repair.failures")
                .description("Stranded-terminal repair failures; later intents still repair")
                .tag("scope", "item")
                .register(meterRegistry);
        this.repairScanFailureCounter = Counter.builder("lms.disbursement.workflow.repair.failures")
                .description("Stranded-terminal repair scan failures; the batch repairs zero and the tick continues")
                .tag("scope", "scan")
                .register(meterRegistry);
        this.entityManager = entityManager;
        this.workerOwner = buildWorkerOwner(properties.getLeaseOwner());
    }

    static String buildWorkerOwner(String configuredPrefix) {
        String prefix = configuredPrefix == null || configuredPrefix.isBlank()
                ? "disbursement-worker"
                : configuredPrefix.trim();
        String hostname = System.getenv("HOSTNAME");
        if (hostname == null || hostname.isBlank()) {
            hostname = "lms-api";
        }
        String shortId = UUID.randomUUID().toString().substring(0, 8);
        return prefix + "-" + hostname + "-" + shortId;
    }

    /** Process-unique owner used for the claim fence; exposed for tests and ops logs. */
    public String workerOwner() {
        return workerOwner;
    }

    @Transactional
    public DisbursementIntent createIntent(
            LoanApplication application,
            LoanAccount loanAccount,
            BigDecimal disbursementAmount,
            DisbursementPaymentMode paymentMode,
            String actorUsername
    ) {
        // Shared loan-command lock order: borrower → application → account → intent.
        // The borrower lock comes first so a concurrent cross-LSP bank edit (which also locks
        // the borrower first) serializes instead of interleaving; direct callers are covered too.
        // The caller (initiateDisbursement) already holds these row locks; re-acquiring them is
        // a no-op in the same transaction but guarantees the eligibility checks below observe
        // a committed invalidation instead of racing with it.
        Borrower lockedBorrower = loanApplicationRepository.findBorrowerByApplicationIdForUpdate(application.getId())
                .orElseThrow(() -> new IllegalArgumentException("Unknown loan application id: " + application.getId()));
        entityManager.refresh(lockedBorrower);
        LoanApplication lockedApplication = loanApplicationRepository.findByIdForUpdate(application.getId())
                .orElseThrow(() -> new IllegalArgumentException("Unknown loan application id: " + application.getId()));
        if (lockedApplication.getStatus() == LoanApplicationStatus.INVALID) {
            throw new ApiConflictException(
                    "LOAN_ALREADY_INVALID",
                    "Loan application is already marked invalid."
            );
        }
        if (lockedApplication.getStatus() != LoanApplicationStatus.APPROVED_PENDING_DISBURSAL
                && lockedApplication.getStatus() != LoanApplicationStatus.DISBURSEMENT_RETRY) {
            throw new BusinessRuleViolationException(
                    "DISBURSEMENT_NOT_ALLOWED",
                    "Disbursement can only be requested for applications pending disbursal or disbursement retry.",
                    Map.of("status", lockedApplication.getStatus().name())
            );
        }
        LoanAccount lockedAccount = loanAccountRepository.findByIdForUpdate(loanAccount.getId())
                .orElseThrow(() -> new IllegalArgumentException("Unknown loan account id: " + loanAccount.getId()));
        // the intent snapshot is the immutable payment instruction — it cannot be built
        // without verified beneficiary details on file. Reject cleanly instead of leaking a
        // database constraint violation to the caller. The locked borrower (post-wait refresh
        // above) is authoritative, never a stale cached association.
        if (lockedBorrower.getBankAccountNumber() == null
                || lockedBorrower.getBankAccountNumber().isBlank()
                || lockedBorrower.getIfscCode() == null
                || lockedBorrower.getIfscCode().isBlank()) {
            throw new BusinessRuleViolationException(
                    "DISBURSEMENT_VALIDATION_FAILED",
                    "Disbursement bank details failed compliance checks.",
                    Map.of("borrowerBank", "Borrower bank account must be on file before disbursement.")
            );
        }
        if (lockedAccount.getStatus() == LoanAccountStatus.INVALID) {
            throw new ApiConflictException(
                    "LOAN_ALREADY_INVALID",
                    "Loan application is already marked invalid."
            );
        }
        if (lockedAccount.getStatus() == LoanAccountStatus.DISBURSEMENT_REQUESTED
                || lockedAccount.getStatus() == LoanAccountStatus.DISBURSEMENT_PENDING_RECONCILIATION) {
            throw new ApiConflictException(
                    "DISBURSEMENT_ALREADY_REQUESTED",
                    "A live disbursement intent already exists for this loan account."
            );
        }

        disbursementIntentRepository.findLiveByLoanAccountIdForUpdate(lockedAccount.getId()).ifPresent(existing -> {
            throw new ApiConflictException(
                    "DISBURSEMENT_ALREADY_REQUESTED",
                    "A live disbursement intent already exists for this loan account."
            );
        });

        UUID intentId = UUID.randomUUID();
        DisbursementIntent intent = new DisbursementIntent(
                intentId,
                lockedAccount,
                DisbursementIntentReference.deriveTranRefNo(intentId),
                disbursementAmount,
                paymentMode,
                lockedBorrower.getFullName(),
                lockedBorrower.getBankAccountNumber(),
                lockedBorrower.getIfscCode(),
                Strings.normalizeActor(actorUsername),
                CorrelationIdHolder.get()
        );
        // freeze the canonical repayment-schedule hash while holding the shared
        // application → account → intent locks, so a replacement racing this creation either
        // commits first (and is reflected here) or loses on its own live-intent recheck.
        intent.freezeScheduleHash(loanRepaymentScheduleService.currentScheduleHash(lockedAccount.getId()));
        lockedAccount.markDisbursementRequested();
        loanAccountRepository.save(lockedAccount);
        return disbursementIntentRepository.save(intent);
    }

    /**
     * bounded intent recovery runs independently of application scanning. The claim
     * scan and every claimed item are isolated: one failure contributes zero for that
     * item while later items still execute and commit in their own transactions. Never
     * re-initiates — only CREATED claims execute, and UNKNOWN/submitted work is left for
     * reconciliation, never resubmitted here.
     */
    public List<UUID> executeClaimableIntents() {
        Instant now = PersistedTimestamp.now();
        Instant leaseExpiresAt = now.plusSeconds(properties.getLeaseDurationSeconds());
        String owner = workerOwner;
        final List<ClaimToken> claimed;
        try {
            claimed = transactionTemplate.execute(status -> disbursementIntentRepository.claimBatch(
                    now,
                    properties.getClaimBatchSize(),
                    leaseExpiresAt,
                    owner
            ));
        } catch (RuntimeException exception) {
            claimScanFailureCounter.increment();
            log.warn("Claimable intent scan failed and was skipped: {}", exception.getMessage());
            return List.of();
        }
        if (claimed == null || claimed.isEmpty()) {
            return List.of();
        }
        List<UUID> executed = new java.util.ArrayList<>(claimed.size());
        int failed = 0;
        for (ClaimToken claim : claimed) {
            try {
                executeClaimedIntent(claim).ifPresent(executed::add);
            } catch (RuntimeException exception) {
                failed++;
                claimFailureCounter.increment();
                log.warn("Claimable intent execution failed for intent {}: {}",
                        claim.intentId(), exception.getMessage());
            }
        }
        if (failed > 0) {
            log.warn("Claimable intent batch finished with {} failure(s) out of {}.", failed, claimed.size());
        }
        return executed;
    }

    public Optional<UUID> executeForApplication(UUID applicationId) {
        LoanAccount loanAccount = loanAccountRepository.findByLoanApplication_Id(applicationId).orElse(null);
        if (loanAccount == null) {
            return Optional.empty();
        }
        Optional<DisbursementIntent> intent = disbursementIntentRepository.findLiveByLoanAccountId(loanAccount.getId())
                .filter(candidate -> candidate.getState() == DisbursementIntentState.CREATED);
        if (intent.isEmpty()) {
            return Optional.empty();
        }
        // fast path uses the same conditional claim primitive as batch — atomic UPDATE with
        // RETURNING attempt — so fast-vs-fast, fast-vs-batch and batch-vs-batch all fence on
        // owner + attempt, not on a shared owner string.
        Optional<ClaimToken> claimed = transactionTemplate.execute(
                status -> disbursementIntentRepository.claimSingle(
                        intent.get().getId(),
                        PersistedTimestamp.now(),
                        PersistedTimestamp.now().plusSeconds(properties.getLeaseDurationSeconds()),
                        workerOwner));
        if (claimed == null || claimed.isEmpty()) {
            return Optional.empty();
        }
        return executeClaimedIntent(claimed.get());
    }

    public Optional<UUID> executeClaimedIntent(ClaimToken claim) {
        // resolve owning IDs OUTSIDE the prepare transaction. The prepare Tx must start with
        // an empty persistence context so its FOR UPDATE reads hit the database fresh; an unlocked
        // probe inside the same Tx would cache CREATED and let two concurrent preparers both win.
        DisbursementIntent probe = disbursementIntentRepository.findDetailedById(claim.intentId()).orElse(null);
        if (probe == null) {
            return Optional.empty();
        }
        UUID applicationId = probe.getLoanAccount().getLoanApplication().getId();
        UUID loanAccountId = probe.getLoanAccount().getId();
        // Detach the probe so it can never leak a stale entity into the Tx below.
        // (Probe was read outside any Tx managed here; the Tx below uses a fresh context.)

        ProviderCallContext context = transactionTemplate.execute(
                status -> loadProviderCallContext(claim, applicationId, loanAccountId));
        if (context == null) {
            return Optional.empty();
        }

        UUID intentId = claim.intentId();
        LoanDisbursementAdapter.DisbursementResult result;
        try {
            // The bank call stays outside every transaction; latency is recorded
            // even when the call throws (timeout path persists UNKNOWN below).
            result = providerLatency.timeInitiate(() -> loanDisbursementAdapter.requestDisbursement(context.command()));
        } catch (RuntimeException exception) {
            log.warn("Disbursement provider call failed for intent {}: {}", intentId, exception.getMessage());
            transactionTemplate.executeWithoutResult(status -> persistUnknownProviderOutcome(intentId, context, exception.getMessage()));
            return Optional.of(context.applicationId());
        }

        transactionTemplate.executeWithoutResult(status -> persistProviderOutcome(intentId, context, result));
        return Optional.of(context.applicationId());
    }

    /**
     * @deprecated Compatibility: claims atomically then executes. Prefer
     * {@link #executeClaimedIntent(ClaimToken)} with an explicit token so stale claims cannot
     * proceed. Retained for operational callers that only hold an id.
     */
    @Deprecated
    public Optional<UUID> executeClaimedIntent(UUID intentId) {
        Optional<ClaimToken> claimed = transactionTemplate.execute(
                status -> disbursementIntentRepository.claimSingle(
                        intentId,
                        PersistedTimestamp.now(),
                        PersistedTimestamp.now().plusSeconds(properties.getLeaseDurationSeconds()),
                        workerOwner));
        if (claimed == null || claimed.isEmpty()) {
            return Optional.empty();
        }
        return executeClaimedIntent(claimed.get());
    }

    public Optional<StatusPollContext> loadStatusPollContext(UUID applicationId) {
        return loadPollContext(applicationId, false);
    }

    /**
     * reconciliation poll context. Same frozen instruction as the normal path (intent
     * snapshot or original stored payload, never the live borrower row), but open to parked
     * ({@code DISBURSEMENT_PENDING_RECONCILIATION}) loans so an exhausted poll keeps polling
     * the <em>original</em> reference to success instead of re-initiating. Empty means either
     * resolved (nothing to do) or blocked by missing/contradictory instruction (operator queue,
     * never fabricated history) — the caller distinguishes via account state.
     */
    public Optional<StatusPollContext> loadReconciliationPollContext(UUID applicationId) {
        return loadPollContext(applicationId, true);
    }

    private Optional<StatusPollContext> loadPollContext(UUID applicationId, boolean allowParked) {
        LoanApplication application = loanApplicationQueryService.getApplication(applicationId);
        LoanAccount loanAccount = loanAccountRepository.findDetailedByLoanApplication_Id(applicationId).orElse(null);
        if (loanAccount == null) {
            return Optional.empty();
        }
        // normal polling reconciles only still-REQUESTED loans. Terminal or parked
        // accounts must not trigger a provider call here; stranded terminals belong to the repair path
        // and parked loans to reconciliation. Returning empty before the adapter
        // call guarantees zero calls, zero poll-count increments and zero mutations for them.
        // Reconciliation polling (allowParked) additionally serves parked loans, still on
        // the frozen original reference — never a fresh initiation.
        if (allowParked
                ? loanAccount.getStatus() != LoanAccountStatus.DISBURSEMENT_REQUESTED
                        && loanAccount.getStatus() != LoanAccountStatus.DISBURSEMENT_PENDING_RECONCILIATION
                : loanAccount.getStatus() != LoanAccountStatus.DISBURSEMENT_REQUESTED) {
            return Optional.empty();
        }
        if (loanAccount.getLoanApplication() == null
                || !loanAccount.getLoanApplication().getId().equals(applicationId)) {
            log.warn("Disbursement poll blocked reason=account_application_mismatch applicationId={} loanAccountId={}.",
                    applicationId, loanAccount.getId());
            return Optional.empty();
        }
        LoanDisbursementRequestLog latestRequest = loanDisbursementRequestLogRepository
                .findTopByLoanAccount_IdOrderByCreatedAtDesc(loanAccount.getId())
                .orElse(null);
        if (latestRequest == null || dispositionOf(latestRequest) != DisbursementDisposition.PENDING) {
            return Optional.empty();
        }
        if (latestRequest.getLoanAccount() == null
                || !latestRequest.getLoanAccount().getId().equals(loanAccount.getId())) {
            log.warn("Disbursement poll blocked reason=request_account_mismatch applicationId={} loanAccountId={} requestId={}.",
                    applicationId, loanAccount.getId(), latestRequest.getId());
            return Optional.empty();
        }
        FrozenPollInstruction frozen = resolveFrozenPollInstruction(applicationId, loanAccount, latestRequest);
        if (frozen == null) {
            return Optional.empty();
        }
        return Optional.of(new StatusPollContext(
                application, loanAccount, latestRequest,
                frozen.beneficiaryIfsc(), frozen.tranRefNo(), frozen.paymentMode()));
    }

    @Transactional
    public void markIntentFromStatusPoll(
            UUID loanAccountId,
            String expectedTranRefNo,
            LoanDisbursementAdapter.DisbursementStatusResult statusResult) {
        Optional<DisbursementIntent> live = disbursementIntentRepository.findLiveByLoanAccountId(loanAccountId);
        if (live.isEmpty()) {
            return;
        }
        DisbursementIntent intent = live.get();
        // never mark a different live instruction with this poll verdict. A tranRefNo
        // mismatch means the poll evidence does not belong to the current live intent; the verdict
        // is still applied to the loan via the applier, but the unrelated intent row is left alone.
        if (!Objects.equals(intent.getTranRefNo(), expectedTranRefNo)) {
            log.warn("Disbursement poll intent marking skipped reason=live_intent_reference_mismatch "
                            + "loanAccountId={} expectedTranRefNo={} liveIntentId={} liveTranRefNo={}.",
                    loanAccountId, expectedTranRefNo, intent.getId(), intent.getTranRefNo());
            return;
        }
        intent.recordProviderResponse(
                mapIntentState(statusResult.disposition()),
                intent.getProviderRequestId(),
                statusResult.actCode(),
                statusResult.bankRrn(),
                statusResult.declineKind()
        );
        disbursementIntentRepository.save(intent);
    }

    @Transactional
    DisbursementIntent claimIntent(UUID intentId) {
        // Compatibility: the fast path must use the same atomic primitive as batch. The old
        // read-then-write allowed two concurrent claimants to both stamp the same row.
        Instant now = PersistedTimestamp.now();
        Optional<ClaimToken> token = disbursementIntentRepository.claimSingle(
                intentId, now, now.plusSeconds(properties.getLeaseDurationSeconds()), workerOwner);
        if (token.isEmpty()) {
            return null;
        }
        return disbursementIntentRepository.findById(intentId).orElse(null);
    }

    ProviderCallContext loadProviderCallContext(ClaimToken claim, UUID applicationId, UUID loanAccountId) {
        UUID intentId = claim.intentId();
        // Shared lock order (application → account → intent) — honored by invalidation, so no
        // deadlock. This Tx starts with an empty persistence context (probe happened outside), so
        // every FOR UPDATE below reads the committed row fresh; a concurrent winner's REQUESTED
        // commit is always observed and loses here on state.
        LoanApplication application = loanApplicationRepository.findByIdForUpdate(applicationId).orElse(null);
        if (application == null) {
            return null;
        }
        LoanAccount loanAccount = loanAccountRepository.findByIdForUpdate(loanAccountId).orElse(null);
        if (loanAccount == null) {
            return null;
        }
        DisbursementIntent intent = disbursementIntentRepository.findByIdForUpdate(intentId).orElse(null);
        Instant now = Instant.now();
        if (intent == null
                || !intent.getState().isClaimable()
                // Claim fence: owner + attempt + live lease must still match the captured token.
                // A stale claim (same shared owner string, older attempt) loses here, as does a
                // claim taken over by another process after lease expiry. Threads in the same
                // process share the owner, so the attempt check is what separates them.
                || !Objects.equals(claim.owner(), intent.getLeaseOwner())
                || claim.attemptCount() != intent.getAttemptCount()
                || intent.getLeaseExpiresAt() == null
                || !intent.getLeaseExpiresAt().isAfter(now)) {
            return null;
        }
        // Re-read eligibility under the same locks invalidation uses. A loan invalidated
        // after Tx-A committed, or an LSP disabled while the intent was queued, must not
        // reach the bank: returning null grants no submission permission and leaves the
        // provider call counter at zero.
        if (application.getStatus() != LoanApplicationStatus.APPROVED_PENDING_DISBURSAL
                && application.getStatus() != LoanApplicationStatus.DISBURSEMENT_RETRY) {
            return null;
        }
        if (application.getLsp() == null || application.getLsp().getStatus() != LspStatus.ACTIVE) {
            return null;
        }
        if (loanAccount.getStatus() == LoanAccountStatus.INVALID) {
            return null;
        }
        // the frozen schedule hash must still match the persisted schedule. A replacement
        // that committed after intent creation (or after the last eligibility check) changes the
        // terms the provider call would execute against, so preparation grants no submission
        // permission. Missing legacy evidence is never invented: a CREATED intent without a
        // frozen hash stays blocked, while an already-submitted instruction remains
        // reconcilable through the existing repair/reconciliation paths (which never pass through here).
        String frozenScheduleHash = intent.getScheduleHash();
        if (frozenScheduleHash == null) {
            log.warn("Disbursement submission blocked reason=schedule_evidence_missing intentId={} loanAccountId={}.",
                    intentId, loanAccountId);
            return null;
        }
        String currentScheduleHash = loanRepaymentScheduleService.currentScheduleHash(loanAccount.getId());
        if (!frozenScheduleHash.equals(currentScheduleHash)) {
            log.warn("Disbursement submission blocked reason=schedule_changed intentId={} loanAccountId={}.",
                    intentId, loanAccountId);
            return null;
        }
        LoanDisbursementAdapter.DisbursementCommand command = new LoanDisbursementAdapter.DisbursementCommand(
                loanAccount.getAccountNumber(),
                intent.getAmount(),
                intent.getBeneficiaryName(),
                application.getExternalLoanId(),
                application.getLsp().getCode(),
                intent.getPaymentMode(),
                intent.getTranRefNo(),
                intent.getBeneficiaryAccountNumber(),
                intent.getBeneficiaryIfsc()
        );
        LoanDisbursementRequestLog requestLog = loanDisbursementRequestLogRepository.save(new LoanDisbursementRequestLog(
                loanAccount,
                intent.getCreatedBy(),
                intent.getAmount(),
                loanDisbursementAdapter.providerName(),
                intent.getTranRefNo(),
                DisbursementDisposition.PENDING.name(),
                intent.getPaymentMode(),
                intent.getTranRefNo(),
                null,
                null,
                DisbursementDeclineKind.NONE,
                serializeDisbursementRequest(command),
                serializePendingResponse(),
                intent.getCorrelationId()
        ));
        intent.markProviderCallStarted();
        disbursementIntentRepository.save(intent);
        return new ProviderCallContext(
                intentId,
                application.getId(),
                loanAccount.getId(),
                requestLog.getId(),
                command,
                claim.owner(),
                claim.attemptCount());
    }

    @Transactional
    void persistProviderOutcome(
            UUID intentId,
            ProviderCallContext context,
            LoanDisbursementAdapter.DisbursementResult result
    ) {
        LoanApplication application = loanApplicationRepository.findByIdForUpdate(context.applicationId())
                .orElseThrow(() -> new IllegalArgumentException("Unknown loan application id: " + context.applicationId()));
        LoanAccount loanAccount = loanAccountRepository.findByIdForUpdate(context.loanAccountId())
                .orElseThrow(() -> new IllegalArgumentException("Unknown loan account id: " + context.loanAccountId()));
        DisbursementIntent intent = disbursementIntentRepository.findByIdForUpdate(intentId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown disbursement intent id: " + intentId));
        LoanDisbursementRequestLog requestLog = loanDisbursementRequestLogRepository
                .findById(context.requestLogId())
                .orElseThrow(() -> new IllegalArgumentException("Unknown disbursement request log id: " + context.requestLogId()));

        // the INITIATE observation is recorded in every path — including terminal/stale
        // duplicates — in the same transaction as the outcome decision, so the observed result
        // and the accepted outcome commit atomically and a failed local apply rolls both back
        // together, staying recoverable by the original reference.
        String initiateRequestJson = serializeDisbursementRequest(context.command());
        boolean duplicateOutcome = intent.getState().isTerminal()
                || loanAccount.getStatus() != LoanAccountStatus.DISBURSEMENT_REQUESTED
                || !Objects.equals(requestLog.getTranRefNo(), intent.getTranRefNo())
                || (intent.getLeaseOwner() != null
                        && (!Objects.equals(context.claimOwner(), intent.getLeaseOwner())
                                || context.claimAttempt() != intent.getAttemptCount()));
        observationWriter.recordInitiate(
                loanAccount,
                intent,
                context.claimAttempt(),
                result.disposition(),
                true,
                duplicateOutcome,
                result.providerName(),
                result.providerRequestId(),
                result.actCode(),
                result.bankRrn(),
                result.declineKind(),
                context.command().beneficiaryIfsc(),
                context.command().beneficiaryAccountNumber(),
                context.command().paymentMode(),
                initiateRequestJson,
                result.responsePayloadJson(),
                intent.getCorrelationId(),
                intent.getCreatedBy()
        );

        if (duplicateOutcome) {
            // Terminal precedence: late/stale evidence is preserved above but never regresses
            // the accepted outcome. A definitive verdict that contradicts the resolved loan is
            // surfaced to the operator queue instead of being applied.
            if (result.disposition() != DisbursementDisposition.PENDING
                    && contradictsResolvedLoan(result.disposition(), loanAccount.getStatus(), intent.getState())) {
                observationWriter.enqueue(
                        loanAccount,
                        intent,
                        intent.getTranRefNo(),
                        DisbursementReconciliationReason.CONFLICTING_EVIDENCE,
                        "Late definitive provider response contradicts the accepted outcome for reference "
                                + intent.getTranRefNo() + "; operator review required, no state changed.");
                log.warn("Conflicting late provider outcome for intent {} (state {}, account {}); queued as conflicting evidence.",
                        intentId, intent.getState(), loanAccount.getStatus());
            } else if (loanAccount.getStatus() == LoanAccountStatus.DISBURSEMENT_REQUESTED
                    || loanAccount.getStatus() == LoanAccountStatus.DISBURSEMENT_PENDING_RECONCILIATION) {
                observationWriter.enqueue(
                        loanAccount,
                        intent,
                        intent.getTranRefNo(),
                        queueReasonFor(intent, loanAccount),
                        "Duplicate/stale provider response recorded as evidence for reference "
                                + intent.getTranRefNo() + "; accepted outcome unchanged.");
                log.warn("Ignoring duplicate/stale provider outcome for intent {} (state {}, account {}).",
                        intentId, intent.getState(), loanAccount.getStatus());
            } else {
                // terminal duplicate — the observation above is preserved, the accepted
                // outcome stands, and the queue is left untouched: a conflicting-evidence entry
                // held for operators must never be dismissed by a late duplicate.
                log.warn("Ignoring duplicate provider outcome for already-terminal intent {} (state {}, account {}).",
                        intentId, intent.getState(), loanAccount.getStatus());
            }
            return;
        }

        DisbursementIntentState nextIntentState = mapIntentState(result.disposition());
        requestLog.updateProviderSubmission(
                result.providerName(),
                result.providerRequestId(),
                result.providerStatus(),
                result.paymentMode(),
                result.actCode(),
                result.bankRrn(),
                result.declineKind(),
                result.responsePayloadJson()
        );
        loanDisbursementRequestLogRepository.save(requestLog);

        intent.recordProviderResponse(
                nextIntentState,
                result.providerRequestId(),
                result.actCode(),
                result.bankRrn(),
                result.declineKind()
        );
        disbursementIntentRepository.save(intent);

        loanEventLog.append(
                application.getLsp(),
                LoanEventType.DISBURSEMENT_REQUESTED,
                "LOAN_ACCOUNT",
                loanAccount.getId().toString(),
                application.getId(),
                LoanEventPayloads.disbursement(application, loanAccount, requestLog)
        );

        if (result.disposition() != DisbursementDisposition.PENDING) {
            disbursementOutcomeApplier.apply(
                    application,
                    loanAccount,
                    requestLog,
                    new DisbursementOutcomeApplier.ProviderOutcome(
                            result.disposition(),
                            result.declineKind(),
                            result.actCode(),
                            result.bankRrn(),
                            result.message()
                    ),
                    intent.getCreatedBy(),
                    null,
                    intent.getCorrelationId()
            );
            // terminal outcome accepted — the loan is resolved, clear any queue entry.
            observationWriter.clear(loanAccount.getId());
        } else {
            // still in flight — the loan stays visible in the reconciliation queue on its
            // original reference until polling or manual evidence resolves it.
            observationWriter.enqueue(
                    loanAccount,
                    intent,
                    intent.getTranRefNo(),
                    queueReasonFor(intent, loanAccount),
                    "Initiate response PENDING for reference " + intent.getTranRefNo()
                            + "; polling the original reference.");
        }
    }

    @Transactional
    void persistUnknownProviderOutcome(UUID intentId, ProviderCallContext context, String message) {
        LoanApplication application = loanApplicationRepository.findByIdForUpdate(context.applicationId())
                .orElseThrow(() -> new IllegalArgumentException("Unknown loan application id: " + context.applicationId()));
        LoanAccount loanAccount = loanAccountRepository.findByIdForUpdate(context.loanAccountId())
                .orElseThrow(() -> new IllegalArgumentException("Unknown loan account id: " + context.loanAccountId()));
        DisbursementIntent intent = disbursementIntentRepository.findByIdForUpdate(intentId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown disbursement intent id: " + intentId));
        // timeouts are PENDING observations with an unresolved query — recorded in every
        // path, including already-terminal duplicates, so no attempt is ever lost.
        String unknownRequestJson = serializeDisbursementRequest(context.command());
        String unknownResponseJson = serializeUnknownResponse(message);
        boolean staleUnknown = intent.getState().isTerminal()
                || (intent.getLeaseOwner() != null
                        && (!Objects.equals(context.claimOwner(), intent.getLeaseOwner())
                                || context.claimAttempt() != intent.getAttemptCount()));
        observationWriter.recordInitiate(
                loanAccount,
                intent,
                context.claimAttempt(),
                DisbursementDisposition.PENDING,
                false,
                staleUnknown,
                loanDisbursementAdapter.providerName(),
                context.command().tranRefNo(),
                null,
                null,
                DisbursementDeclineKind.NONE,
                context.command().beneficiaryIfsc(),
                context.command().beneficiaryAccountNumber(),
                context.command().paymentMode(),
                unknownRequestJson,
                unknownResponseJson,
                intent.getCorrelationId(),
                intent.getCreatedBy()
        );
        if (staleUnknown) {
            log.warn("Ignoring stale unknown-outcome marker for intent {} (state {}).", intentId, intent.getState());
            return;
        }

        String payload = serializeUnknownResponse(message);
        LoanDisbursementRequestLog requestLog = loanDisbursementRequestLogRepository
                .findById(context.requestLogId())
                .orElseThrow(() -> new IllegalArgumentException("Unknown disbursement request log id: " + context.requestLogId()));
        requestLog.updateOutcome(
                DisbursementDisposition.PENDING.name(),
                null,
                null,
                DisbursementDeclineKind.NONE,
                payload
        );
        loanDisbursementRequestLogRepository.save(requestLog);

        intent.recordProviderResponse(
                DisbursementIntentState.UNKNOWN,
                intent.getTranRefNo(),
                null,
                null,
                DisbursementDeclineKind.NONE
        );
        disbursementIntentRepository.save(intent);

        // an UNKNOWN instruction is unresolved money — visible in the queue until a
        // definitive observation arrives via polling or evidence-backed manual resolution.
        observationWriter.enqueue(
                loanAccount,
                intent,
                intent.getTranRefNo(),
                DisbursementReconciliationReason.UNKNOWN,
                "Provider call outcome unknown for reference " + intent.getTranRefNo()
                        + "; reconciling the original reference.");

        loanEventLog.append(
                application.getLsp(),
                LoanEventType.DISBURSEMENT_REQUESTED,
                "LOAN_ACCOUNT",
                loanAccount.getId().toString(),
                application.getId(),
                LoanEventPayloads.disbursement(application, loanAccount, requestLog)
        );
    }

    @Transactional(readOnly = true)
    public List<UUID> findStrandedTerminalIntentIds(int limit) {
        int bounded = Math.max(1, Math.min(limit, 100));
        return disbursementIntentRepository.findStrandedTerminalIntents(PageRequest.of(0, bounded)).stream()
                .map(DisbursementIntent::getId)
                .toList();
    }

    @Transactional
    public boolean repairStrandedTerminalIntent(UUID intentId) {
        DisbursementIntent probe = disbursementIntentRepository.findDetailedById(intentId).orElse(null);
        if (probe == null) {
            return false;
        }
        UUID applicationId = probe.getLoanAccount().getLoanApplication().getId();
        UUID loanAccountId = probe.getLoanAccount().getId();

        LoanApplication application = loanApplicationRepository.findByIdForUpdate(applicationId).orElse(null);
        LoanAccount loanAccount = loanAccountRepository.findByIdForUpdate(loanAccountId).orElse(null);
        DisbursementIntent intent = disbursementIntentRepository.findByIdForUpdate(intentId).orElse(null);
        if (application == null || loanAccount == null || intent == null) {
            return false;
        }
        if (!intent.getState().isTerminal() || intent.getState() == DisbursementIntentState.CANCELLED) {
            return false;
        }
        if (loanAccount.getStatus() != LoanAccountStatus.DISBURSEMENT_REQUESTED) {
            return false;
        }
        LoanDisbursementRequestLog latestRequest = loanDisbursementRequestLogRepository
                .findTopByLoanAccount_IdOrderByCreatedAtDesc(loanAccount.getId())
                .orElse(null);
        if (latestRequest == null || !Objects.equals(latestRequest.getTranRefNo(), intent.getTranRefNo())) {
            log.warn("Stranded intent {} has no trustworthy stored request for reference {}; flagging for manual reconciliation.",
                    intentId, intent.getTranRefNo());
            // missing evidence is an operator-queue item, never fabricated history.
            observationWriter.enqueue(
                    loanAccount,
                    intent,
                    intent.getTranRefNo(),
                    DisbursementReconciliationReason.LEGACY_MISMATCH,
                    "Stranded terminal intent has no matching stored request for reference "
                            + intent.getTranRefNo() + "; manual evidence-backed resolution required.");
            return false;
        }
        DisbursementDisposition disposition = intent.getState() == DisbursementIntentState.SUCCEEDED
                ? DisbursementDisposition.SUCCESS
                : DisbursementDisposition.FAILED;
        disbursementOutcomeApplier.apply(
                application,
                loanAccount,
                latestRequest,
                new DisbursementOutcomeApplier.ProviderOutcome(
                        disposition,
                        latestRequest.getDeclineKind(),
                        latestRequest.getProviderActCode(),
                        latestRequest.getBankRrn(),
                        "Repaired stranded terminal result for reference " + intent.getTranRefNo()
                                + " without re-initiation."
                ),
                intent.getCreatedBy(),
                null,
                intent.getCorrelationId()
        );
        // stranded terminal applied — the loan is resolved, drop its queue entry.
        observationWriter.clear(loanAccount.getId());
        return true;
    }

    /**
     * stranded-terminal repair is bounded recovery without re-initiation. Scan
     * failures repair zero while per-item failures are isolated with per-item counters,
     * so one bad intent never starves the rest of the batch.
     */
    public int repairStrandedTerminalDisbursements(int limit) {
        final List<UUID> ids;
        try {
            ids = findStrandedTerminalIntentIds(limit);
        } catch (RuntimeException exception) {
            repairScanFailureCounter.increment();
            log.warn("Stranded terminal repair scan failed and was skipped: {}", exception.getMessage());
            return 0;
        }
        int repaired = 0;
        int failed = 0;
        for (UUID intentId : ids) {
            try {
                Boolean applied = transactionTemplate.execute(status -> repairStrandedTerminalIntent(intentId));
                if (Boolean.TRUE.equals(applied)) {
                    repaired++;
                }
            } catch (RuntimeException exception) {
                failed++;
                repairFailureCounter.increment();
                log.warn("Stranded repair failed for intent {}: {}", intentId, exception.getMessage());
            }
        }
        if (failed > 0) {
            log.warn("Stranded terminal repair finished with {} failure(s) out of {}.", failed, ids.size());
        }
        return repaired;
    }

    private static DisbursementIntentState mapIntentState(DisbursementDisposition disposition) {
        return switch (disposition) {
            case SUCCESS -> DisbursementIntentState.SUCCEEDED;
            case FAILED -> DisbursementIntentState.FAILED;
            case PENDING -> DisbursementIntentState.REQUESTED;
        };
    }

    /**
     * queue reason for still-unresolved money: UNKNOWN instructions, parked loans,
     * stranded terminals (terminal intent, loan still REQUESTED), else plain in-flight.
     */
    static DisbursementReconciliationReason queueReasonFor(
            DisbursementIntent intent, LoanAccount loanAccount) {
        if (intent.getState() == DisbursementIntentState.UNKNOWN) {
            return DisbursementReconciliationReason.UNKNOWN;
        }
        if (loanAccount.getStatus() == LoanAccountStatus.DISBURSEMENT_PENDING_RECONCILIATION) {
            return DisbursementReconciliationReason.PARKED;
        }
        if (intent.getState().isTerminal()
                && loanAccount.getStatus() == LoanAccountStatus.DISBURSEMENT_REQUESTED) {
            return DisbursementReconciliationReason.STRANDED_TERMINAL;
        }
        return DisbursementReconciliationReason.REQUESTED;
    }

    /**
     * true when a late definitive verdict disagrees with the already-accepted outcome, so
     * it must surface as conflicting evidence instead of regressing the loan. Terminal
     * precedence: an accepted SUCCESS is only contradicted by FAILED and vice versa. A null
     * intent state (no live intent, e.g. legacy rows) falls back to the account status alone.
     */
    static boolean contradictsResolvedLoan(
            DisbursementDisposition late,
            LoanAccountStatus accountStatus,
            DisbursementIntentState intentState) {
        DisbursementDisposition accepted = null;
        if (accountStatus == LoanAccountStatus.DISBURSED || intentState == DisbursementIntentState.SUCCEEDED) {
            accepted = DisbursementDisposition.SUCCESS;
        } else if (accountStatus == LoanAccountStatus.DISBURSEMENT_FAILED
                || intentState == DisbursementIntentState.FAILED) {
            accepted = DisbursementDisposition.FAILED;
        }
        return accepted != null && late != DisbursementDisposition.PENDING && late != accepted;
    }

    private static DisbursementDisposition dispositionOf(LoanDisbursementRequestLog log) {
        try {
            return DisbursementDisposition.valueOf(log.getProviderStatus());
        } catch (IllegalArgumentException ignored) {
            return DisbursementDisposition.PENDING;
        }
    }

    private String serializeDisbursementRequest(LoanDisbursementAdapter.DisbursementCommand command) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("provider", loanDisbursementAdapter.providerName());
        payload.put("paymentMode", command.paymentMode() == null ? null : command.paymentMode().name());
        payload.put("tranRefNo", command.tranRefNo());
        payload.put("loanAccountNumber", command.loanAccountNumber());
        payload.put("amount", command.amount());
        payload.put("borrowerName", command.borrowerName());
        payload.put("beneficiaryAccountNumber", command.beneficiaryAccountNumber());
        payload.put("beneficiaryIfsc", command.beneficiaryIfsc());
        payload.put("externalLoanId", command.externalLoanId());
        payload.put("lspCode", command.lspCode());
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize disbursement request payload.", exception);
        }
    }

    private String serializeUnknownResponse(String message) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "disposition", DisbursementDisposition.PENDING.name(),
                    "message", message == null ? "Provider call failed with unknown outcome." : message
            ));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize unknown provider response.", exception);
        }
    }

    private String serializePendingResponse() {
        return serializeUnknownResponse("Provider call initiated; terminal response not yet recorded.");
    }

    record ProviderCallContext(
            UUID intentId,
            UUID applicationId,
            UUID loanAccountId,
            UUID requestLogId,
            LoanDisbursementAdapter.DisbursementCommand command,
            String claimOwner,
            int claimAttempt
    ) {
    }

    public record StatusPollContext(
            LoanApplication application,
            LoanAccount loanAccount,
            LoanDisbursementRequestLog latestRequest,
            String frozenBeneficiaryIfsc,
            String frozenTranRefNo,
            DisbursementPaymentMode frozenPaymentMode
    ) {
        public LoanDisbursementAdapter.DisbursementStatusQuery query() {
            // the status query reuses the frozen payment instruction (intent snapshot
            // or, for legacy rows without an intent, the original stored request payload) — never
            // the borrower's current IFSC. The photo is taken at intent creation, not at approval.
            return new LoanDisbursementAdapter.DisbursementStatusQuery(
                    frozenTranRefNo,
                    frozenPaymentMode,
                    frozenBeneficiaryIfsc,
                    latestRequest.getStatusCheckCount()
            );
        }
    }

    /**
     * Frozen-instruction resolution.
     *
     * <p>Modern rows: the live intent and the latest stored request must agree on loan account,
     * reference and rail, and on the beneficiary IFSC. The frozen query then uses the intent's
     * snapshot.
     *
     * <p>Legacy rows without a live intent (early inline requests, stranded terminals with no
     * live intent): the original stored request payload is the only verifiable instruction and
     * drives polling.
     *
     * <p>V111 backfilled intents copied beneficiary data from the live borrower row and invented a
     * reference/mode when the log lacked one, so an intent alone is not proof of the original
     * instruction. Any missing evidence or any intent/payload/column disagreement blocks automatic
     * polling with a warn diagnostic: no adapter call, no poll-count increment, no new
     * intent/reference and no outcome mutation. A conflicting live intent is never bypassed via
     * payload fallback.
     *
     * @return the frozen instruction, or {@code null} when polling must stay blocked (already logged).
     */
    private FrozenPollInstruction resolveFrozenPollInstruction(
            UUID applicationId,
            LoanAccount loanAccount,
            LoanDisbursementRequestLog latestRequest) {
        PayloadInstruction payload = extractPayloadInstruction(applicationId, loanAccount, latestRequest);
        if (payload == null) {
            return null;
        }
        Optional<DisbursementIntent> live =
                disbursementIntentRepository.findLiveByLoanAccountId(loanAccount.getId());
        if (live.isEmpty()) {
            return new FrozenPollInstruction(
                    payload.beneficiaryIfsc(), payload.tranRefNo(), payload.paymentMode());
        }
        DisbursementIntent intent = live.get();
        if (intent.getLoanAccount() == null
                || !intent.getLoanAccount().getId().equals(loanAccount.getId())) {
            log.warn("Disbursement poll blocked reason=intent_account_mismatch applicationId={} loanAccountId={} "
                            + "requestId={} liveIntentId={}.",
                    applicationId, loanAccount.getId(), latestRequest.getId(), intent.getId());
            return null;
        }
        if (isBlank(intent.getBeneficiaryIfsc())) {
            log.warn("Disbursement poll blocked reason=intent_ifsc_missing applicationId={} loanAccountId={} "
                            + "requestId={} liveIntentId={}.",
                    applicationId, loanAccount.getId(), latestRequest.getId(), intent.getId());
            return null;
        }
        if (!Objects.equals(intent.getTranRefNo(), latestRequest.getTranRefNo())
                || !Objects.equals(intent.getTranRefNo(), payload.tranRefNo())) {
            log.warn("Disbursement poll blocked reason=live_intent_reference_mismatch applicationId={} "
                            + "loanAccountId={} requestId={} liveIntentId={}.",
                    applicationId, loanAccount.getId(), latestRequest.getId(), intent.getId());
            return null;
        }
        if (intent.getPaymentMode() == null
                || intent.getPaymentMode() != latestRequest.getPaymentMode()
                || intent.getPaymentMode() != payload.paymentMode()) {
            log.warn("Disbursement poll blocked reason=live_intent_mode_mismatch applicationId={} "
                            + "loanAccountId={} requestId={} liveIntentId={}.",
                    applicationId, loanAccount.getId(), latestRequest.getId(), intent.getId());
            return null;
        }
        if (!normalizeIfsc(intent.getBeneficiaryIfsc()).equals(normalizeIfsc(payload.beneficiaryIfsc()))) {
            log.warn("Disbursement poll blocked reason=frozen_instruction_conflict applicationId={} "
                            + "loanAccountId={} requestId={} liveIntentId={}. "
                            + "Backfilled or edited intent beneficiary differs from the original request; "
                            + "manual reconciliation required, live borrower data is never substituted.",
                    applicationId, loanAccount.getId(), latestRequest.getId(), intent.getId());
            return null;
        }
        return new FrozenPollInstruction(
                intent.getBeneficiaryIfsc(), intent.getTranRefNo(), intent.getPaymentMode());
    }

    /**
     * Reads the verifiable original request from the stored {@code request_payload_json} and
     * cross-checks its reference/rail against the request-log columns. Malformed field values are
     * treated as missing evidence (blocked); malformed JSON syntax cannot normally be persisted
     * through the jsonb constructor and is handled as blocked via the same path.
     *
     * @return the payload instruction with trimmed values, or {@code null} when blocked (logged).
     */
    private PayloadInstruction extractPayloadInstruction(
            UUID applicationId,
            LoanAccount loanAccount,
            LoanDisbursementRequestLog latestRequest) {
        JsonNode payload;
        try {
            payload = objectMapper.readTree(latestRequest.getRequestPayloadJson());
        } catch (Exception exception) {
            log.warn("Disbursement poll blocked reason=request_payload_unparseable applicationId={} "
                            + "loanAccountId={} requestId={}: {}",
                    applicationId, loanAccount.getId(), latestRequest.getId(), exception.getMessage());
            return null;
        }
        if (payload == null || !payload.isObject()) {
            log.warn("Disbursement poll blocked reason=request_payload_not_object applicationId={} "
                            + "loanAccountId={} requestId={}.",
                    applicationId, loanAccount.getId(), latestRequest.getId());
            return null;
        }
        String payloadIfsc = textField(payload, "beneficiaryIfsc");
        String payloadTranRefNo = textField(payload, "tranRefNo");
        String payloadModeRaw = textField(payload, "paymentMode");
        if (isBlank(payloadIfsc)) {
            log.warn("Disbursement poll blocked reason=request_ifsc_missing applicationId={} "
                            + "loanAccountId={} requestId={}.",
                    applicationId, loanAccount.getId(), latestRequest.getId());
            return null;
        }
        if (isBlank(payloadTranRefNo)) {
            log.warn("Disbursement poll blocked reason=request_reference_missing applicationId={} "
                            + "loanAccountId={} requestId={}.",
                    applicationId, loanAccount.getId(), latestRequest.getId());
            return null;
        }
        if (isBlank(payloadModeRaw)) {
            log.warn("Disbursement poll blocked reason=request_mode_missing applicationId={} "
                            + "loanAccountId={} requestId={}.",
                    applicationId, loanAccount.getId(), latestRequest.getId());
            return null;
        }
        DisbursementPaymentMode payloadMode;
        try {
            payloadMode = DisbursementPaymentMode.valueOf(payloadModeRaw.trim());
        } catch (IllegalArgumentException invalid) {
            log.warn("Disbursement poll blocked reason=request_mode_invalid applicationId={} "
                            + "loanAccountId={} requestId={}.",
                    applicationId, loanAccount.getId(), latestRequest.getId());
            return null;
        }
        String trimmedIfsc = payloadIfsc.trim();
        String trimmedTranRefNo = payloadTranRefNo.trim();
        // Contradictory identity between the stored payload and the log columns must block rather
        // than guess which copy is original. A missing column value is also insufficient evidence:
        // V111 invented references/modes for such rows, so they must not be trusted.
        if (isBlank(latestRequest.getTranRefNo()) || !latestRequest.getTranRefNo().equals(trimmedTranRefNo)) {
            log.warn("Disbursement poll blocked reason=payload_reference_mismatch applicationId={} "
                            + "loanAccountId={} requestId={}.",
                    applicationId, loanAccount.getId(), latestRequest.getId());
            return null;
        }
        if (latestRequest.getPaymentMode() == null || latestRequest.getPaymentMode() != payloadMode) {
            log.warn("Disbursement poll blocked reason=payload_mode_mismatch applicationId={} "
                            + "loanAccountId={} requestId={}.",
                    applicationId, loanAccount.getId(), latestRequest.getId());
            return null;
        }
        return new PayloadInstruction(trimmedIfsc, trimmedTranRefNo, payloadMode);
    }

    private static String textField(JsonNode payload, String fieldName) {
        JsonNode node = payload.get(fieldName);
        if (node == null || !node.isTextual()) {
            return null;
        }
        return node.asText();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String normalizeIfsc(String ifsc) {
        return ifsc == null ? "" : ifsc.trim().toUpperCase(Locale.ROOT);
    }

    private record FrozenPollInstruction(
            String beneficiaryIfsc,
            String tranRefNo,
            DisbursementPaymentMode paymentMode) {
    }

    private record PayloadInstruction(
            String beneficiaryIfsc,
            String tranRefNo,
            DisbursementPaymentMode paymentMode) {
    }
}
