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
@Table(name = "loan_repayment_schedule_installment")
public class LoanRepaymentScheduleInstallment {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "loan_account_id", nullable = false)
    private LoanAccount loanAccount;

    @Column(name = "installment_number", nullable = false)
    private int installmentNumber;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Column(name = "opening_principal", nullable = false, precision = 19, scale = 2)
    private BigDecimal openingPrincipal;

    @Column(name = "principal_due", nullable = false, precision = 19, scale = 2)
    private BigDecimal principalDue;

    @Column(name = "interest_due", nullable = false, precision = 19, scale = 2)
    private BigDecimal interestDue;

    @Column(name = "installment_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal installmentAmount;

    @Column(name = "closing_principal", nullable = false, precision = 19, scale = 2)
    private BigDecimal closingPrincipal;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 64)
    private LoanRepaymentScheduleInstallmentStatus status;

    @Column(name = "paid_principal", nullable = false, precision = 19, scale = 2)
    private BigDecimal paidPrincipal;

    @Column(name = "paid_interest", nullable = false, precision = 19, scale = 2)
    private BigDecimal paidInterest;

    @Column(name = "paid_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal paidAmount;

    @Column(name = "outstanding_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal outstandingAmount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected LoanRepaymentScheduleInstallment() {
    }

    public LoanRepaymentScheduleInstallment(
            LoanAccount loanAccount,
            int installmentNumber,
            LocalDate dueDate,
            BigDecimal openingPrincipal,
            BigDecimal principalDue,
            BigDecimal interestDue,
            BigDecimal installmentAmount,
            BigDecimal closingPrincipal
    ) {
        this.id = UUID.randomUUID();
        this.loanAccount = loanAccount;
        this.installmentNumber = installmentNumber;
        this.dueDate = dueDate;
        this.openingPrincipal = openingPrincipal;
        this.principalDue = principalDue;
        this.interestDue = interestDue;
        this.installmentAmount = installmentAmount;
        this.closingPrincipal = closingPrincipal;
        this.status = LoanRepaymentScheduleInstallmentStatus.PENDING;
        this.paidPrincipal = BigDecimal.ZERO.setScale(2);
        this.paidInterest = BigDecimal.ZERO.setScale(2);
        this.paidAmount = BigDecimal.ZERO.setScale(2);
        this.outstandingAmount = installmentAmount;
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

    public int getInstallmentNumber() {
        return installmentNumber;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public BigDecimal getOpeningPrincipal() {
        return openingPrincipal;
    }

    public BigDecimal getPrincipalDue() {
        return principalDue;
    }

    public BigDecimal getInterestDue() {
        return interestDue;
    }

    public BigDecimal getInstallmentAmount() {
        return installmentAmount;
    }

    public BigDecimal getClosingPrincipal() {
        return closingPrincipal;
    }

    public LoanRepaymentScheduleInstallmentStatus getStatus() {
        return status;
    }

    public BigDecimal getPaidPrincipal() {
        return paidPrincipal;
    }

    public BigDecimal getPaidInterest() {
        return paidInterest;
    }

    public BigDecimal getPaidAmount() {
        return paidAmount;
    }

    public BigDecimal getOutstandingAmount() {
        return outstandingAmount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void resetAllocation() {
        this.status = LoanRepaymentScheduleInstallmentStatus.PENDING;
        this.paidPrincipal = BigDecimal.ZERO.setScale(2);
        this.paidInterest = BigDecimal.ZERO.setScale(2);
        this.paidAmount = BigDecimal.ZERO.setScale(2);
        this.outstandingAmount = installmentAmount;
    }

    public BigDecimal applyPayment(BigDecimal amount) {
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO.setScale(2);
        }

        BigDecimal interestOutstanding = interestDue.subtract(paidInterest).max(BigDecimal.ZERO);
        BigDecimal interestApplied = amount.min(interestOutstanding);
        paidInterest = paidInterest.add(interestApplied);
        BigDecimal remaining = amount.subtract(interestApplied);

        BigDecimal principalOutstanding = principalDue.subtract(paidPrincipal).max(BigDecimal.ZERO);
        BigDecimal principalApplied = remaining.min(principalOutstanding);
        paidPrincipal = paidPrincipal.add(principalApplied);

        BigDecimal appliedAmount = interestApplied.add(principalApplied);
        paidAmount = paidInterest.add(paidPrincipal);
        outstandingAmount = installmentAmount.subtract(paidAmount).max(BigDecimal.ZERO).setScale(2);
        status = outstandingAmount.compareTo(BigDecimal.ZERO) == 0
                ? LoanRepaymentScheduleInstallmentStatus.PAID
                : paidAmount.compareTo(BigDecimal.ZERO) > 0
                ? LoanRepaymentScheduleInstallmentStatus.PARTIALLY_PAID
                : LoanRepaymentScheduleInstallmentStatus.PENDING;

        return appliedAmount.setScale(2);
    }
}
