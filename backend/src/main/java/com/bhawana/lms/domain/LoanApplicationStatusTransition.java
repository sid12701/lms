package com.bhawana.lms.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "loan_application_status_transition")
public class LoanApplicationStatusTransition {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "loan_application_id", nullable = false)
    private LoanApplication loanApplication;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", nullable = false, length = 32)
    private LoanApplicationStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 32)
    private LoanApplicationStatus toStatus;

    @Column(name = "actor_username", nullable = false, length = 255)
    private String actorUsername;

    @Column(name = "note", nullable = false, length = 500)
    private String note;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason_code", length = 64)
    private LoanApplicationStatusReasonCode reasonCode;

    @Column(name = "correlation_id", length = 128)
    private String correlationId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected LoanApplicationStatusTransition() {
    }

    public LoanApplicationStatusTransition(
            LoanApplication loanApplication,
            LoanApplicationStatus fromStatus,
            LoanApplicationStatus toStatus,
            String actorUsername,
            String note,
            LoanApplicationStatusReasonCode reasonCode,
            String correlationId
    ) {
        this.id = UUID.randomUUID();
        this.loanApplication = loanApplication;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.actorUsername = actorUsername;
        this.note = note;
        this.reasonCode = reasonCode;
        this.correlationId = correlationId;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public LoanApplication getLoanApplication() {
        return loanApplication;
    }

    public LoanApplicationStatus getFromStatus() {
        return fromStatus;
    }

    public LoanApplicationStatus getToStatus() {
        return toStatus;
    }

    public String getActorUsername() {
        return actorUsername;
    }

    public String getNote() {
        return note;
    }

    public LoanApplicationStatusReasonCode getReasonCode() {
        return reasonCode;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
