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
@Table(name = "disbursement_outcome_audit")
public class DisbursementOutcomeAudit {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "loan_application_id", nullable = false)
    private LoanApplication loanApplication;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "loan_account_id", nullable = false)
    private LoanAccount loanAccount;

    @Column(name = "actor_username", nullable = false, length = 255)
    private String actorUsername;

    @Column(name = "actor_ip", length = 64)
    private String actorIp;

    @Column(name = "correlation_id", length = 128)
    private String correlationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private DisbursementOutcomeAuditSource source;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private DisbursementOutcomeAuditOutcome outcome;

    @Column(name = "provider_request_id", length = 128)
    private String providerRequestId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected DisbursementOutcomeAudit() {
    }

    public DisbursementOutcomeAudit(
            LoanApplication loanApplication,
            LoanAccount loanAccount,
            String actorUsername,
            String actorIp,
            String correlationId,
            DisbursementOutcomeAuditSource source,
            DisbursementOutcomeAuditOutcome outcome,
            String providerRequestId
    ) {
        this.id = UUID.randomUUID();
        this.loanApplication = loanApplication;
        this.loanAccount = loanAccount;
        this.actorUsername = actorUsername;
        this.actorIp = actorIp;
        this.correlationId = correlationId;
        this.source = source;
        this.outcome = outcome;
        this.providerRequestId = providerRequestId;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getLoanApplicationId() {
        return loanApplication.getId();
    }

    public UUID getLoanAccountId() {
        return loanAccount.getId();
    }

    public String getActorUsername() {
        return actorUsername;
    }

    public String getActorIp() {
        return actorIp;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public DisbursementOutcomeAuditSource getSource() {
        return source;
    }

    public DisbursementOutcomeAuditOutcome getOutcome() {
        return outcome;
    }

    public String getProviderRequestId() {
        return providerRequestId;
    }
}
