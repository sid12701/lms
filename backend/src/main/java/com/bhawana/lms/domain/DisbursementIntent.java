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
@Table(name = "disbursement_intent")
public class DisbursementIntent {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "loan_account_id", nullable = false)
    private LoanAccount loanAccount;

    @Column(name = "tran_ref_no", nullable = false, unique = true, length = 64)
    private String tranRefNo;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_mode", nullable = false, length = 16)
    private DisbursementPaymentMode paymentMode;

    @Column(name = "beneficiary_name", nullable = false, length = 255)
    private String beneficiaryName;

    @Column(name = "beneficiary_account_number", nullable = false, length = 64)
    private String beneficiaryAccountNumber;

    @Column(name = "beneficiary_ifsc", nullable = false, length = 16)
    private String beneficiaryIfsc;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private DisbursementIntentState state;

    @Column(name = "lease_owner", length = 128)
    private String leaseOwner;

    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "provider_request_id", length = 128)
    private String providerRequestId;

    @Column(name = "provider_act_code", length = 16)
    private String providerActCode;

    @Column(name = "bank_rrn", length = 32)
    private String bankRrn;

    @Enumerated(EnumType.STRING)
    @Column(name = "decline_kind", length = 16)
    private DisbursementDeclineKind declineKind;

    @Column(name = "created_by", nullable = false, length = 255)
    private String createdBy;

    @Column(name = "correlation_id", length = 128)
    private String correlationId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected DisbursementIntent() {
    }

    public DisbursementIntent(
            UUID id,
            LoanAccount loanAccount,
            String tranRefNo,
            BigDecimal amount,
            DisbursementPaymentMode paymentMode,
            String beneficiaryName,
            String beneficiaryAccountNumber,
            String beneficiaryIfsc,
            String createdBy,
            String correlationId
    ) {
        this.id = id;
        this.loanAccount = loanAccount;
        this.tranRefNo = tranRefNo;
        this.amount = amount;
        this.paymentMode = paymentMode;
        this.beneficiaryName = beneficiaryName;
        this.beneficiaryAccountNumber = beneficiaryAccountNumber;
        this.beneficiaryIfsc = beneficiaryIfsc;
        this.state = DisbursementIntentState.CREATED;
        this.attemptCount = 0;
        this.createdBy = createdBy;
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

    public String getTranRefNo() {
        return tranRefNo;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public DisbursementPaymentMode getPaymentMode() {
        return paymentMode;
    }

    public String getBeneficiaryName() {
        return beneficiaryName;
    }

    public String getBeneficiaryAccountNumber() {
        return beneficiaryAccountNumber;
    }

    public String getBeneficiaryIfsc() {
        return beneficiaryIfsc;
    }

    public DisbursementIntentState getState() {
        return state;
    }

    public String getLeaseOwner() {
        return leaseOwner;
    }

    public Instant getLeaseExpiresAt() {
        return leaseExpiresAt;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public String getProviderRequestId() {
        return providerRequestId;
    }

    public String getProviderActCode() {
        return providerActCode;
    }

    public String getBankRrn() {
        return bankRrn;
    }

    public DisbursementDeclineKind getDeclineKind() {
        return declineKind;
    }

    public String getCreatedBy() {
        return createdBy;
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

    public void stampLease(String leaseOwner, Instant leaseExpiresAt) {
        this.leaseOwner = leaseOwner;
        this.leaseExpiresAt = leaseExpiresAt;
        this.attemptCount = attemptCount + 1;
    }

    public void clearLease() {
        this.leaseOwner = null;
        this.leaseExpiresAt = null;
    }

    /**
     * Persists the point of no automatic retry before the provider side effect. Once this state is
     * committed, recovery must use the deterministic transaction reference and status API.
     */
    public void markProviderCallStarted() {
        this.state = DisbursementIntentState.REQUESTED;
        clearLease();
    }

    public void recordProviderResponse(
            DisbursementIntentState state,
            String providerRequestId,
            String providerActCode,
            String bankRrn,
            DisbursementDeclineKind declineKind
    ) {
        this.state = state;
        this.providerRequestId = providerRequestId;
        this.providerActCode = providerActCode;
        this.bankRrn = bankRrn;
        this.declineKind = declineKind;
        clearLease();
    }
}
