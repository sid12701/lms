package com.bhawana.lms.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "loan_disbursement_bank_mismatch_log")
public class LoanDisbursementBankMismatchLog {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "loan_application_id", nullable = false)
    private LoanApplication loanApplication;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "lsp_id", nullable = false)
    private Lsp lsp;

    @Column(name = "submitted_bank_account_number", length = 64)
    private String submittedBankAccountNumber;

    @Column(name = "submitted_ifsc_code", length = 11)
    private String submittedIfscCode;

    @Column(name = "submitted_account_holder_name", length = 255)
    private String submittedAccountHolderName;

    @Column(name = "correlation_id", length = 128)
    private String correlationId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected LoanDisbursementBankMismatchLog() {
    }

    public LoanDisbursementBankMismatchLog(
            LoanApplication loanApplication,
            Lsp lsp,
            String submittedBankAccountNumber,
            String submittedIfscCode,
            String submittedAccountHolderName,
            String correlationId
    ) {
        this.id = UUID.randomUUID();
        this.loanApplication = loanApplication;
        this.lsp = lsp;
        this.submittedBankAccountNumber = submittedBankAccountNumber;
        this.submittedIfscCode = submittedIfscCode;
        this.submittedAccountHolderName = submittedAccountHolderName;
        this.correlationId = correlationId;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
