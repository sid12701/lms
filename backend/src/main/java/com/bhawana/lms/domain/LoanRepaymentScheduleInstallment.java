package com.bhawana.lms.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
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

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

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
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
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

    public Instant getCreatedAt() {
        return createdAt;
    }
}
