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
import java.util.UUID;

@Entity
@Table(name = "loan_application")
public class LoanApplication {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "borrower_id", nullable = false)
    private Borrower borrower;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "lsp_id", nullable = false)
    private Lsp lsp;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "loan_product_id", nullable = false)
    private LoanProduct loanProduct;

    @Column(name = "external_loan_id", nullable = false, length = 128)
    private String externalLoanId;

    @Column(name = "source_channel", nullable = false, length = 64)
    private String sourceChannel;

    @Column(name = "requested_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal requestedAmount;

    @Column(name = "tenure_months", nullable = false)
    private int tenureMonths;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private LoanApplicationStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected LoanApplication() {
    }

    public LoanApplication(
            Borrower borrower,
            Lsp lsp,
            LoanProduct loanProduct,
            String externalLoanId,
            String sourceChannel,
            BigDecimal requestedAmount,
            int tenureMonths,
            LoanApplicationStatus status
    ) {
        this.id = UUID.randomUUID();
        this.borrower = borrower;
        this.lsp = lsp;
        this.loanProduct = loanProduct;
        this.externalLoanId = externalLoanId.trim();
        this.sourceChannel = sourceChannel.trim().toUpperCase();
        this.requestedAmount = requestedAmount;
        this.tenureMonths = tenureMonths;
        this.status = status;
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

    public Borrower getBorrower() {
        return borrower;
    }

    public Lsp getLsp() {
        return lsp;
    }

    public LoanProduct getLoanProduct() {
        return loanProduct;
    }

    public String getExternalLoanId() {
        return externalLoanId;
    }

    public String getSourceChannel() {
        return sourceChannel;
    }

    public BigDecimal getRequestedAmount() {
        return requestedAmount;
    }

    public int getRequestedTenureMonths() {
        return tenureMonths;
    }

    public int getTenureMonths() {
        return tenureMonths;
    }

    public LoanApplicationStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void transitionTo(LoanApplicationStatus status) {
        this.status = status;
    }
}
