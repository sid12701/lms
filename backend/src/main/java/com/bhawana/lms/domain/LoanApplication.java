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
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "loan_application")
public class LoanApplication {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "borrower_id", nullable = false)
    private Borrower borrower;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "lsp_id", nullable = false)
    private Lsp lsp;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
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

    @Enumerated(EnumType.STRING)
    @Column(name = "invalid_reason_code", length = 64)
    private LoanInvalidationReason invalidReasonCode;

    @Column(name = "invalid_reason_text", length = 500)
    private String invalidReasonText;

    @Column(name = "invalidated_by_username", length = 255)
    private String invalidatedByUsername;

    @Column(name = "invalidated_at")
    private Instant invalidatedAt;

    @Column(name = "assigned_to_username", length = 128)
    private String assignedToUsername;

    @Column(name = "assigned_by_username", length = 128)
    private String assignedByUsername;

    @Column(name = "assigned_at")
    private Instant assignedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "entity_version", nullable = false)
    private long entityVersion;

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

    public LoanInvalidationReason getInvalidReasonCode() {
        return invalidReasonCode;
    }

    public String getInvalidReasonText() {
        return invalidReasonText;
    }

    public String getInvalidatedByUsername() {
        return invalidatedByUsername;
    }

    public Instant getInvalidatedAt() {
        return invalidatedAt;
    }

    public String getAssignedToUsername() {
        return assignedToUsername;
    }

    public String getAssignedByUsername() {
        return assignedByUsername;
    }

    public Instant getAssignedAt() {
        return assignedAt;
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

    public void transitionTo(LoanApplicationStatus status) {
        this.status = status;
    }

    public void markInvalid(
            LoanInvalidationReason invalidReasonCode,
            String invalidReasonText,
            String invalidatedByUsername,
            Instant invalidatedAt
    ) {
        this.status = LoanApplicationStatus.INVALID;
        this.invalidReasonCode = invalidReasonCode;
        this.invalidReasonText = invalidReasonText;
        this.invalidatedByUsername = invalidatedByUsername;
        this.invalidatedAt = invalidatedAt;
    }

    public void assignTo(String assignedToUsername, String assignedByUsername) {
        this.assignedToUsername = assignedToUsername;
        this.assignedByUsername = assignedByUsername;
        this.assignedAt = Instant.now();
    }

    public void releaseAssignment() {
        this.assignedToUsername = null;
        this.assignedByUsername = null;
        this.assignedAt = null;
    }
}
