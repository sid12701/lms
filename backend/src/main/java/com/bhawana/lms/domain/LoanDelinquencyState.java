package com.bhawana.lms.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "loan_delinquency_state")
public class LoanDelinquencyState {

    @Id
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "loan_application_id", nullable = false, unique = true)
    private LoanApplication loanApplication;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_bucket", nullable = false, length = 32)
    private LoanDelinquencyBucket lastBucket;

    @Column(name = "last_max_days_past_due", nullable = false)
    private int lastMaxDaysPastDue;

    @Column(name = "last_evaluated_at", nullable = false)
    private Instant lastEvaluatedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected LoanDelinquencyState() {
    }

    public LoanDelinquencyState(LoanApplication loanApplication) {
        this.id = UUID.randomUUID();
        this.loanApplication = loanApplication;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public LoanApplication getLoanApplication() {
        return loanApplication;
    }

    public UUID getLoanApplicationId() {
        return loanApplication.getId();
    }

    public LoanDelinquencyBucket getLastBucket() {
        return lastBucket;
    }

    public int getLastMaxDaysPastDue() {
        return lastMaxDaysPastDue;
    }

    public Instant getLastEvaluatedAt() {
        return lastEvaluatedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void refresh(LoanDelinquencyBucket bucket, int maxDaysPastDue, Instant evaluatedAt) {
        this.lastBucket = bucket;
        this.lastMaxDaysPastDue = maxDaysPastDue;
        this.lastEvaluatedAt = evaluatedAt;
    }
}
