package com.bhawana.lms.service;

import com.bhawana.lms.common.util.Strings;
import com.bhawana.lms.domain.DisbursementDeclineKind;
import com.bhawana.lms.domain.DisbursementDisposition;
import com.bhawana.lms.domain.DisbursementIntent;
import com.bhawana.lms.domain.DisbursementObservation;
import com.bhawana.lms.domain.DisbursementObservationKind;
import com.bhawana.lms.domain.DisbursementObservationProvenance;
import com.bhawana.lms.domain.DisbursementPaymentMode;
import com.bhawana.lms.domain.DisbursementReconciliationQueueEntry;
import com.bhawana.lms.domain.DisbursementReconciliationReason;
import com.bhawana.lms.domain.LoanAccount;
import com.bhawana.lms.repo.DisbursementIntentRepository;
import com.bhawana.lms.repo.DisbursementObservationRepository;
import com.bhawana.lms.repo.DisbursementReconciliationQueueRepository;
import com.bhawana.lms.repo.LoanDisbursementRequestLogRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * H02 — leaf writer for the append-only observation trail and the explicit reconciliation
 * queue. Called inside the caller's result transaction so the observed result and the
 * accepted outcome commit atomically; a failed local apply rolls both back together and the
 * original reference stays recoverable by polling.
 *
 * <p>Observations are never skipped because the account is terminal or the response is stale:
 * late/duplicate evidence is stored with {@code duplicate=true} and reconciled as evidence,
 * never regressing the accepted outcome.
 */
@Component
public class DisbursementObservationWriter {

    private static final Logger log = LoggerFactory.getLogger(DisbursementObservationWriter.class);

    private final DisbursementObservationRepository observationRepository;
    private final DisbursementReconciliationQueueRepository queueRepository;
    private final DisbursementReconciliationProperties properties;
    private final EntityManager entityManager;
    private final DisbursementIntentRepository disbursementIntentRepository;
    private final LoanDisbursementRequestLogRepository loanDisbursementRequestLogRepository;

    public DisbursementObservationWriter(
            DisbursementObservationRepository observationRepository,
            DisbursementReconciliationQueueRepository queueRepository,
            DisbursementReconciliationProperties properties,
            EntityManager entityManager,
            DisbursementIntentRepository disbursementIntentRepository,
            LoanDisbursementRequestLogRepository loanDisbursementRequestLogRepository
    ) {
        this.observationRepository = observationRepository;
        this.queueRepository = queueRepository;
        this.properties = properties;
        this.entityManager = entityManager;
        this.disbursementIntentRepository = disbursementIntentRepository;
        this.loanDisbursementRequestLogRepository = loanDisbursementRequestLogRepository;
    }

    public DisbursementObservation recordInitiate(
            LoanAccount loanAccount,
            DisbursementIntent intent,
            int attempt,
            DisbursementDisposition disposition,
            boolean queryResolved,
            boolean duplicate,
            String providerName,
            String providerRequestId,
            String actCode,
            String bankRrn,
            DisbursementDeclineKind declineKind,
            String beneficiaryIfsc,
            String beneficiaryAccountNumber,
            DisbursementPaymentMode paymentMode,
            String requestPayloadJson,
            String responsePayloadJson,
            String correlationId,
            String createdBy
    ) {
        return save(new DisbursementObservation(
                loanAccount, intent, intent == null ? providerRequestId : intent.getTranRefNo(),
                attempt, null, DisbursementObservationKind.INITIATE,
                disposition, queryResolved, duplicate,
                providerName, providerRequestId, actCode, bankRrn, declineKind,
                beneficiaryIfsc, beneficiaryAccountNumber, paymentMode,
                requestPayloadJson, responsePayloadJson, correlationId,
                DisbursementObservationProvenance.LIVE,
                Strings.normalizeActor(createdBy)));
    }

    public DisbursementObservation recordPoll(
            LoanAccount loanAccount,
            DisbursementIntent intent,
            String tranRefNo,
            int attempt,
            Integer pollSeq,
            DisbursementDisposition disposition,
            boolean queryResolved,
            boolean duplicate,
            String providerName,
            String providerRequestId,
            String actCode,
            String bankRrn,
            DisbursementDeclineKind declineKind,
            String beneficiaryIfsc,
            String beneficiaryAccountNumber,
            DisbursementPaymentMode paymentMode,
            String requestPayloadJson,
            String responsePayloadJson,
            String correlationId,
            String createdBy
    ) {
        return save(new DisbursementObservation(
                loanAccount, intent, tranRefNo,
                attempt, pollSeq, DisbursementObservationKind.POLL,
                disposition, queryResolved, duplicate,
                providerName, providerRequestId, actCode, bankRrn, declineKind,
                beneficiaryIfsc, beneficiaryAccountNumber, paymentMode,
                requestPayloadJson, responsePayloadJson, correlationId,
                DisbursementObservationProvenance.LIVE,
                Strings.normalizeActor(createdBy)));
    }

    private DisbursementObservation save(DisbursementObservation observation) {
        return observationRepository.save(observation);
    }

    /**
     * Upserts the per-account queue row. Missing/contradictory instructions land here with
     * their reason — the queue is the operator surface, never fabricated history.
     *
     * <p>Refresh never advances {@code next_poll_at} or {@code poll_count}: only an actually
     * attempted poll ({@link DisbursementReconciliationQueueEntry#recordPollAttempt}) moves the
     * backoff, so repeated observations for the same unresolved money cannot push due work
     * forever into the future. {@code first_seen_at} is set once at insert and never rewritten.
     */
    public void enqueue(
            LoanAccount loanAccount,
            DisbursementIntent intent,
            String tranRefNo,
            DisbursementReconciliationReason reason,
            String details
    ) {
        enqueueAt(loanAccount, intent, tranRefNo, reason, details,
                resolveEarliestEvidence(loanAccount.getId()));
    }

    /**
     * H02/C06 — earliest durable original evidence for an account (first stored request or
     * intent {@code createdAt}), so a newly discovered queue row ages from when the money
     * moved, not from discovery. Uses only durable request/intent rows — never live borrower
     * fields. Falls back to now only when no durable evidence exists at all.
     */
    public Instant resolveEarliestEvidence(UUID accountId) {
        Instant earliest = null;
        Optional<com.bhawana.lms.domain.LoanDisbursementRequestLog> firstLog =
                loanDisbursementRequestLogRepository.findTopByLoanAccount_IdOrderByCreatedAtAsc(accountId);
        if (firstLog.isPresent()) {
            earliest = firstLog.get().getCreatedAt();
        }
        Optional<DisbursementIntent> firstIntent =
                disbursementIntentRepository.findTopByLoanAccount_IdOrderByCreatedAtAsc(accountId);
        if (firstIntent.isPresent()
                && (earliest == null || firstIntent.get().getCreatedAt().isBefore(earliest))) {
            earliest = firstIntent.get().getCreatedAt();
        }
        return earliest == null ? Instant.now() : earliest;
    }

    /**
     * H02 — creation path that seeds {@code firstSeenAt} from the original evidence instead of
     * discovery time (legacy rows discovered long after the money moved). Refreshes never
     * rewrite first-seen, and a held {@code CONFLICTING_EVIDENCE} row is never rewritten at
     * all: its reason, reference, identities and details stand until explicit operator
     * resolution — only the evidence timestamp and age escalation move.
     */
    public void enqueueAt(
            LoanAccount loanAccount,
            DisbursementIntent intent,
            String tranRefNo,
            DisbursementReconciliationReason reason,
            String details,
            Instant firstSeenAt
    ) {
        UUID accountId = loanAccount.getId();
        Optional<DisbursementReconciliationQueueEntry> existing = queueRepository.findById(accountId);
        if (existing.isPresent()) {
            DisbursementReconciliationQueueEntry entry = existing.get();
            if (entry.getReason() == DisbursementReconciliationReason.CONFLICTING_EVIDENCE) {
                entry.touchForObservation();
                maybeEscalate(entry);
                queueRepository.save(entry);
            } else {
                entry.refreshKeepSchedule(intent, tranRefNo, reason, details);
                maybeEscalate(entry);
                queueRepository.save(entry);
            }
        } else {
            DisbursementReconciliationQueueEntry entry = new DisbursementReconciliationQueueEntry(
                    loanAccount, intent, tranRefNo, reason,
                    properties.nextPollAt(0), details, firstSeenAt);
            maybeEscalate(entry);
            // H02: explicit persist — the @MapsId assigned identifier makes Spring Data's
            // save() take the merge path, which misreads a brand-new row as detached and
            // fails. Managed-then-saved updates above are unaffected.
            entityManager.persist(entry);
        }
        log.debug("Disbursement reconciliation queue upsert account={} reason={} ref={}.",
                accountId, reason, tranRefNo);
    }

    /**
     * H02 — advances the backoff after an actually attempted poll. This is the only writer path
     * that moves {@code next_poll_at}/{@code poll_count}; observation refreshes never do, so
     * repeated evidence cannot starve due work, while every real attempt backs off.
     */
    public void recordAttempt(UUID loanAccountId) {
        queueRepository.findById(loanAccountId).ifPresent(entry -> {
            entry.recordPollAttempt(properties.nextPollAt(entry.getPollCount() + 1));
            maybeEscalate(entry);
            queueRepository.save(entry);
        });
    }

    /**
     * H02 — clears the queue row on an accepted outcome, unless it is held for conflicting
     * evidence: real contradictions stay operator-visible after acceptance. No dismissal API
     * exists in this scope — the hold persists alongside the immutable trail, and operator
     * ownership (claim) stays available.
     */
    public void clear(UUID loanAccountId) {
        clearIfNonConflicting(loanAccountId);
    }

    /**
     * H02 — conditional clear for no-outcome paths (duplicates, replays): a terminal duplicate
     * observation must never wipe a conflicting-evidence queue entry. Only a row that is not
     * being held for conflicting evidence is dismissed.
     */
    public void clearIfNonConflicting(UUID loanAccountId) {
        Optional<DisbursementReconciliationQueueEntry> existing = queueRepository.findById(loanAccountId);
        if (existing.isPresent()
                && existing.get().getReason() != DisbursementReconciliationReason.CONFLICTING_EVIDENCE) {
            queueRepository.delete(existing.get());
        }
    }

    private void maybeEscalate(DisbursementReconciliationQueueEntry entry) {
        long ageSeconds = Instant.now().getEpochSecond() - entry.getFirstSeenAt().getEpochSecond();
        if (!entry.isEscalated() && ageSeconds >= properties.getEscalationAgeSeconds()) {
            entry.markEscalated();
        }
    }
}
