package com.bhawana.lms.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "loan_account")
public class LoanAccount {

    @Id
    private UUID id;

    @OneToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "loan_application_id", nullable = false, unique = true)
    private LoanApplication loanApplication;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "borrower_id", nullable = false)
    private Borrower borrower;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "lsp_id", nullable = false)
    private Lsp lsp;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "loan_product_id", nullable = false)
    private LoanProduct loanProduct;

    @Column(name = "account_number", nullable = false, unique = true, length = 64)
    private String accountNumber;

    @Column(name = "principal_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal principalAmount;

    @Column(name = "tenure_months", nullable = false)
    private int tenureMonths;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 64)
    private LoanAccountStatus status;

    @Column(name = "approved_at", nullable = false)
    private Instant approvedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected LoanAccount() {
    }

    public LoanAccount(
            LoanApplication loanApplication,
            Borrower borrower,
            Lsp lsp,
            LoanProduct loanProduct,
            String accountNumber,
            BigDecimal principalAmount,
            int tenureMonths,
            LoanAccountStatus status,
            Instant approvedAt
    ) {
        this.id = UUID.randomUUID();
        this.loanApplication = loanApplication;
        this.borrower = borrower;
        this.lsp = lsp;
        this.loanProduct = loanProduct;
        this.accountNumber = accountNumber;
        this.principalAmount = principalAmount;
        this.tenureMonths = tenureMonths;
        this.status = status;
        this.approvedAt = approvedAt;
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

    public Borrower getBorrower() {
        return borrower;
    }

    public Lsp getLsp() {
        return lsp;
    }

    public LoanProduct getLoanProduct() {
        return loanProduct;
    }

    public String getAccountNumber() {
        return accountNumber;
    }

    public BigDecimal getPrincipalAmount() {
        return principalAmount;
    }

    public int getTenureMonths() {
        return tenureMonths;
    }

    public LoanAccountStatus getStatus() {
        return status;
    }

    public Instant getApprovedAt() {
        return approvedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
