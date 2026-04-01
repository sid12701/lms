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
@Table(name = "loan_payment_transaction")
public class LoanPaymentTransaction {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "loan_account_id", nullable = false)
    private LoanAccount loanAccount;

    @Column(name = "actor_username", nullable = false, length = 255)
    private String actorUsername;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "payment_date", nullable = false)
    private LocalDate paymentDate;

    @Column(nullable = false, length = 128)
    private String reference;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 64)
    private LoanPaymentChannel channel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 64)
    private LoanPaymentStatus status;

    @Column(length = 500)
    private String note;

    @Column(name = "correlation_id", length = 128)
    private String correlationId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected LoanPaymentTransaction() {
    }

    public LoanPaymentTransaction(
            LoanAccount loanAccount,
            String actorUsername,
            BigDecimal amount,
            LocalDate paymentDate,
            String reference,
            LoanPaymentChannel channel,
            LoanPaymentStatus status,
            String note,
            String correlationId
    ) {
        this.id = UUID.randomUUID();
        this.loanAccount = loanAccount;
        this.actorUsername = actorUsername;
        this.amount = amount;
        this.paymentDate = paymentDate;
        this.reference = reference;
        this.channel = channel;
        this.status = status;
        this.note = note;
        this.correlationId = correlationId;
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

    public String getActorUsername() {
        return actorUsername;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public LocalDate getPaymentDate() {
        return paymentDate;
    }

    public String getReference() {
        return reference;
    }

    public LoanPaymentChannel getChannel() {
        return channel;
    }

    public LoanPaymentStatus getStatus() {
        return status;
    }

    public String getNote() {
        return note;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
