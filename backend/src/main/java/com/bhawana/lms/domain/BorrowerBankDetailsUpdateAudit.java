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
@Table(name = "borrower_bank_details_update_audit")
public class BorrowerBankDetailsUpdateAudit {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "borrower_id", nullable = false)
    private Borrower borrower;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lsp_id")
    private Lsp lsp;

    @Column(name = "actor_username", nullable = false, length = 255)
    private String actorUsername;

    @Column(name = "actor_type", nullable = false, length = 32)
    private String actorType;

    @Column(name = "previous_bank_account_number", length = 64)
    private String previousBankAccountNumber;

    @Column(name = "previous_bank_name", length = 255)
    private String previousBankName;

    @Column(name = "previous_ifsc_code", length = 11)
    private String previousIfscCode;

    @Column(name = "previous_account_holder_name", length = 255)
    private String previousAccountHolderName;

    @Column(name = "new_bank_account_number", nullable = false, length = 64)
    private String newBankAccountNumber;

    @Column(name = "new_bank_name", length = 255)
    private String newBankName;

    @Column(name = "new_ifsc_code", nullable = false, length = 11)
    private String newIfscCode;

    @Column(name = "new_account_holder_name", length = 255)
    private String newAccountHolderName;

    @Column(name = "client_ip", length = 64)
    private String clientIp;

    @Column(name = "correlation_id", length = 128)
    private String correlationId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected BorrowerBankDetailsUpdateAudit() {
    }

    public BorrowerBankDetailsUpdateAudit(
            Borrower borrower,
            Lsp lsp,
            String actorUsername,
            String actorType,
            String previousBankAccountNumber,
            String previousBankName,
            String previousIfscCode,
            String previousAccountHolderName,
            String newBankAccountNumber,
            String newBankName,
            String newIfscCode,
            String newAccountHolderName,
            String clientIp,
            String correlationId
    ) {
        this.id = UUID.randomUUID();
        this.borrower = borrower;
        this.lsp = lsp;
        this.actorUsername = actorUsername;
        this.actorType = actorType;
        this.previousBankAccountNumber = previousBankAccountNumber;
        this.previousBankName = previousBankName;
        this.previousIfscCode = previousIfscCode;
        this.previousAccountHolderName = previousAccountHolderName;
        this.newBankAccountNumber = newBankAccountNumber;
        this.newBankName = newBankName;
        this.newIfscCode = newIfscCode;
        this.newAccountHolderName = newAccountHolderName;
        this.clientIp = clientIp;
        this.correlationId = correlationId;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public Borrower getBorrower() {
        return borrower;
    }

    public String getPreviousBankAccountNumber() {
        return previousBankAccountNumber;
    }

    public String getNewBankAccountNumber() {
        return newBankAccountNumber;
    }

    public String getPreviousIfscCode() {
        return previousIfscCode;
    }

    public String getNewIfscCode() {
        return newIfscCode;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
