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
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "loan_account")
public class LoanAccount {

    @Id
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "loan_application_id", nullable = false, unique = true)
    private LoanApplication loanApplication;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "borrower_id", nullable = false)
    private Borrower borrower;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "lsp_id", nullable = false)
    private Lsp lsp;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "loan_product_id", nullable = false)
    private LoanProduct loanProduct;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "loan_product_version_id", nullable = false)
    private LoanProductVersion loanProductVersion;

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

    @Column(name = "disbursed_at")
    private Instant disbursedAt;

    @Column(name = "processing_fee_amount", precision = 19, scale = 2)
    private BigDecimal processingFeeAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "closure_reason", length = 32)
    private LoanAccountClosureReason closureReason;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "closed_by_username", length = 255)
    private String closedByUsername;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "entity_version", nullable = false)
    private long entityVersion;

    protected LoanAccount() {
    }

    public LoanAccount(
            LoanApplication loanApplication,
            Borrower borrower,
            Lsp lsp,
            LoanProduct loanProduct,
            LoanProductVersion loanProductVersion,
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
        this.loanProductVersion = loanProductVersion;
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

    public LoanProductVersion getLoanProductVersion() {
        return loanProductVersion;
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

    public void markInvalid() {
        this.status = LoanAccountStatus.INVALID;
    }

    public void markDisbursementRequested() {
        this.status = LoanAccountStatus.DISBURSEMENT_REQUESTED;
    }

    public void updateDisbursementStatus(LoanAccountStatus status, Instant occurredAt) {
        updateDisbursementStatus(status, occurredAt, null);
    }

    /**
     * Records the disbursement outcome. On a successful DISBURSED transition the processing fee
     * that was actually charged (principal minus cash sent to the borrower) is persisted; for
     * non-success outcomes the fee argument is ignored.
     */
    public void updateDisbursementStatus(LoanAccountStatus status, Instant occurredAt, BigDecimal processingFeeAmount) {
        this.status = status;
        if (status == LoanAccountStatus.DISBURSED) {
            this.disbursedAt = occurredAt;
            this.processingFeeAmount = processingFeeAmount;
        }
    }

    public Instant getApprovedAt() {
        return approvedAt;
    }

    public Instant getDisbursedAt() {
        return disbursedAt;
    }

    public BigDecimal getProcessingFeeAmount() {
        return processingFeeAmount;
    }

    public LoanAccountClosureReason getClosureReason() {
        return closureReason;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    public String getClosedByUsername() {
        return closedByUsername;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getEntityVersion() {
        return entityVersion;
    }

    public void close(LoanAccountClosureReason reason, String actorUsername, Instant occurredAt) {
        this.status = reason == LoanAccountClosureReason.FORECLOSURE
                ? LoanAccountStatus.FORECLOSED
                : LoanAccountStatus.CLOSED;
        this.closureReason = reason;
        this.closedByUsername = actorUsername;
        this.closedAt = occurredAt;
    }
}
