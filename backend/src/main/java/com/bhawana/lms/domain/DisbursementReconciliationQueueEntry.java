package com.bhawana.lms.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Explicit bounded unresolved queue. One row per loan account with uncertain money:
 * in-flight ({@code UNKNOWN}/{@code REQUESTED}), parked, legacy mismatches, stranded
 * terminals and conflicting definitive evidence.
 *
 * <p>{@code nextPollAt} with backoff bounds provider load; {@code firstSeenAt} drives age
 * escalation; {@code owner} carries operator ownership once claimed. The row is removed when
 * the loan reaches an applied terminal outcome — never by resetting the account to an
 * eligible initiation state.
 */
@Entity
@Table(name = "disbursement_reconciliation_queue")
public class DisbursementReconciliationQueueEntry {

    @Id
    @Column(name = "loan_account_id")
    private UUID loanAccountId;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId
    @JoinColumn(name = "loan_account_id")
    private LoanAccount loanAccount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "intent_id")
    private DisbursementIntent intent;

    // NULL = no reference was ever stored: the account stays visible for operator-only handling.
    @Column(name = "tran_ref_no", length = 64)
    private String tranRefNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, length = 32)
    private DisbursementReconciliationReason reason;

    @Column(name = "next_poll_at", nullable = false)
    private Instant nextPollAt;

    @Column(name = "poll_count", nullable = false)
    private int pollCount;

    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt;

    @Column(name = "last_observation_at", nullable = false)
    private Instant lastObservationAt;

    @Column(name = "owner", length = 128)
    private String owner;

    @Column(name = "escalated", nullable = false)
    private boolean escalated;

    @Column(name = "details")
    private String details;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected DisbursementReconciliationQueueEntry() {
    }

    public DisbursementReconciliationQueueEntry(
            LoanAccount loanAccount,
            DisbursementIntent intent,
            String tranRefNo,
            DisbursementReconciliationReason reason,
            Instant nextPollAt,
            String details
    ) {
        this(loanAccount, intent, tranRefNo, reason, nextPollAt, details, Instant.now());
    }

    public DisbursementReconciliationQueueEntry(
            LoanAccount loanAccount,
            DisbursementIntent intent,
            String tranRefNo,
            DisbursementReconciliationReason reason,
            Instant nextPollAt,
            String details,
            Instant firstSeenAt
    ) {
        this.loanAccount = loanAccount;
        this.loanAccountId = loanAccount.getId();
        this.intent = intent;
        this.tranRefNo = tranRefNo;
        this.reason = reason;
        this.nextPollAt = nextPollAt;
        this.pollCount = 0;
        this.firstSeenAt = firstSeenAt;
        this.lastObservationAt = Instant.now();
        this.details = details;
    }

    @PrePersist
    void onCreate() {
        updatedAt = Instant.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public void refresh(
            DisbursementIntent intent,
            String tranRefNo,
            DisbursementReconciliationReason reason,
            Instant nextPollAt,
            String details
    ) {
        this.intent = intent;
        this.tranRefNo = tranRefNo;
        this.reason = reason;
        this.nextPollAt = nextPollAt;
        this.lastObservationAt = Instant.now();
        if (details != null) {
            this.details = details;
        }
    }

    /**
     * Observation-driven refresh: updates identity/reason/evidence timestamp but keeps
     * the polling schedule ({@code nextPollAt}/{@code pollCount}) and the original
     * {@code firstSeenAt}. Only {@link #recordPollAttempt} advances the backoff.
     */
    public void refreshKeepSchedule(
            DisbursementIntent intent,
            String tranRefNo,
            DisbursementReconciliationReason reason,
            String details
    ) {
        this.intent = intent;
        this.tranRefNo = tranRefNo;
        this.reason = reason;
        this.lastObservationAt = Instant.now();
        if (details != null) {
            this.details = details;
        }
    }

    public void recordPollAttempt(Instant nextPollAt) {
        this.pollCount += 1;
        this.nextPollAt = nextPollAt;
        this.lastObservationAt = Instant.now();
    }

    /**
     * Evidence heartbeat for a held row: records that fresh evidence arrived and drives
     * age escalation without touching reason, reference, identities, details, owner or schedule.
     */
    public void touchForObservation() {
        this.lastObservationAt = Instant.now();
    }

    public void claim(String owner) {
        this.owner = owner;
    }

    public void markEscalated() {
        this.escalated = true;
    }

    public UUID getLoanAccountId() {
        return loanAccountId;
    }

    public LoanAccount getLoanAccount() {
        return loanAccount;
    }

    public DisbursementIntent getIntent() {
        return intent;
    }

    public String getTranRefNo() {
        return tranRefNo;
    }

    public DisbursementReconciliationReason getReason() {
        return reason;
    }

    public Instant getNextPollAt() {
        return nextPollAt;
    }

    public int getPollCount() {
        return pollCount;
    }

    public Instant getFirstSeenAt() {
        return firstSeenAt;
    }

    public Instant getLastObservationAt() {
        return lastObservationAt;
    }

    public String getOwner() {
        return owner;
    }

    public boolean isEscalated() {
        return escalated;
    }

    public String getDetails() {
        return details;
    }
}
