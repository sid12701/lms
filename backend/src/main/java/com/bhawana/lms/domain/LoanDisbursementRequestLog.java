package com.bhawana.lms.domain;

import com.fasterxml.jackson.databind.JsonNode;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "loan_disbursement_request_log")
public class LoanDisbursementRequestLog {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "loan_account_id", nullable = false)
    private LoanAccount loanAccount;

    @Column(name = "actor_username", nullable = false, length = 255)
    private String actorUsername;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "provider_name", nullable = false, length = 64)
    private String providerName;

    @Column(name = "provider_request_id", nullable = false, length = 128)
    private String providerRequestId;

    @Column(name = "provider_status", nullable = false, length = 64)
    private String providerStatus;

    // ICICI Composite Pay mirror fields. Nullable so legacy rows stay valid (see V98).
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_mode", length = 16)
    private DisbursementPaymentMode paymentMode;

    @Column(name = "tran_ref_no", length = 64)
    private String tranRefNo;

    @Column(name = "provider_act_code", length = 16)
    private String providerActCode;

    @Column(name = "bank_rrn", length = 32)
    private String bankRrn;

    @Enumerated(EnumType.STRING)
    @Column(name = "decline_kind", length = 16)
    private DisbursementDeclineKind declineKind;

    @Column(name = "status_check_count", nullable = false)
    private int statusCheckCount;

    @Column(name = "request_payload_json", nullable = false, columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private JsonNode requestPayloadJson;

    @Column(name = "response_payload_json", nullable = false, columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private JsonNode responsePayloadJson;

    @Column(name = "correlation_id", length = 128)
    private String correlationId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected LoanDisbursementRequestLog() {
    }

    public LoanDisbursementRequestLog(
            LoanAccount loanAccount,
            String actorUsername,
            BigDecimal amount,
            String providerName,
            String providerRequestId,
            String providerStatus,
            DisbursementPaymentMode paymentMode,
            String tranRefNo,
            String providerActCode,
            String bankRrn,
            DisbursementDeclineKind declineKind,
            String requestPayloadJson,
            String responsePayloadJson,
            String correlationId
    ) {
        this.id = UUID.randomUUID();
        this.loanAccount = loanAccount;
        this.actorUsername = actorUsername;
        this.amount = amount;
        this.providerName = providerName;
        this.providerRequestId = providerRequestId;
        this.providerStatus = providerStatus;
        this.paymentMode = paymentMode;
        this.tranRefNo = tranRefNo;
        this.providerActCode = providerActCode;
        this.bankRrn = bankRrn;
        this.declineKind = declineKind;
        this.statusCheckCount = 0;
        this.requestPayloadJson = JsonPayloads.requiredObject(requestPayloadJson, "requestPayloadJson");
        this.responsePayloadJson = JsonPayloads.requiredObject(responsePayloadJson, "responsePayloadJson");
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

    public String getProviderName() {
        return providerName;
    }

    public String getProviderRequestId() {
        return providerRequestId;
    }

    public String getProviderStatus() {
        return providerStatus;
    }

    public DisbursementPaymentMode getPaymentMode() {
        return paymentMode;
    }

    public String getTranRefNo() {
        return tranRefNo;
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

    public int getStatusCheckCount() {
        return statusCheckCount;
    }

    public String getRequestPayloadJson() {
        return JsonPayloads.asString(requestPayloadJson);
    }

    public String getResponsePayloadJson() {
        return JsonPayloads.asString(responsePayloadJson);
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

    /** Records the terminal provider verdict once a request resolves (synchronously or via status check). */
    public void updateOutcome(
            String providerStatus,
            String providerActCode,
            String bankRrn,
            DisbursementDeclineKind declineKind,
            String responsePayloadJson
    ) {
        this.providerStatus = providerStatus;
        if (providerActCode != null) {
            this.providerActCode = providerActCode;
        }
        if (bankRrn != null) {
            this.bankRrn = bankRrn;
        }
        if (declineKind != null) {
            this.declineKind = declineKind;
        }
        this.responsePayloadJson = JsonPayloads.requiredObject(responsePayloadJson, "responsePayloadJson");
    }

    /** Each status-check poll bumps this; the worker parks the transaction once the cap is reached. */
    public void recordStatusCheck() {
        this.statusCheckCount += 1;
    }
}
