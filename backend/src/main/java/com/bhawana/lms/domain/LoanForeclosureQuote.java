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
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "loan_foreclosure_quote")
public class LoanForeclosureQuote {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "loan_account_id", nullable = false)
    private LoanAccount loanAccount;

    @Column(nullable = false)
    private int version;

    @Column(name = "requested_by_username", nullable = false, length = 255)
    private String requestedByUsername;

    @Column(name = "executed_by_username", length = 255)
    private String executedByUsername;

    @Column(name = "effective_date", nullable = false)
    private LocalDate effectiveDate;

    @Column(name = "outstanding_principal", nullable = false, precision = 19, scale = 2)
    private BigDecimal outstandingPrincipal;

    @Column(name = "outstanding_interest", nullable = false, precision = 19, scale = 2)
    private BigDecimal outstandingInterest;

    @Column(name = "settlement_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal settlementAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private LoanForeclosureQuoteStatus status;

    @Column(name = "executed_at")
    private Instant executedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected LoanForeclosureQuote() {
    }

    public LoanForeclosureQuote(
            LoanAccount loanAccount,
            int version,
            String requestedByUsername,
            LocalDate effectiveDate,
            BigDecimal outstandingPrincipal,
            BigDecimal outstandingInterest,
            BigDecimal settlementAmount
    ) {
        this.id = UUID.randomUUID();
        this.loanAccount = loanAccount;
        this.version = version;
        this.requestedByUsername = requestedByUsername;
        this.effectiveDate = effectiveDate;
        this.outstandingPrincipal = outstandingPrincipal;
        this.outstandingInterest = outstandingInterest;
        this.settlementAmount = settlementAmount;
        this.status = LoanForeclosureQuoteStatus.ACTIVE;
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

    public LoanAccount getLoanAccount() {
        return loanAccount;
    }

    public int getVersion() {
        return version;
    }

    public String getRequestedByUsername() {
        return requestedByUsername;
    }

    public String getExecutedByUsername() {
        return executedByUsername;
    }

    public LocalDate getEffectiveDate() {
        return effectiveDate;
    }

    public BigDecimal getOutstandingPrincipal() {
        return outstandingPrincipal;
    }

    public BigDecimal getOutstandingInterest() {
        return outstandingInterest;
    }

    public BigDecimal getSettlementAmount() {
        return settlementAmount;
    }

    public LoanForeclosureQuoteStatus getStatus() {
        return status;
    }

    public Instant getExecutedAt() {
        return executedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void supersede() {
        if (status == LoanForeclosureQuoteStatus.ACTIVE) {
            this.status = LoanForeclosureQuoteStatus.SUPERSEDED;
        }
    }

    public void execute(String actorUsername) {
        this.status = LoanForeclosureQuoteStatus.EXECUTED;
        this.executedByUsername = actorUsername;
        this.executedAt = Instant.now();
    }
}
