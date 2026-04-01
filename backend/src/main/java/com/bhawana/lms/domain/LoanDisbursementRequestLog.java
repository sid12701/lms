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
import java.util.UUID;

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

    @Column(name = "request_payload_json", nullable = false, columnDefinition = "TEXT")
    private String requestPayloadJson;

    @Column(name = "response_payload_json", nullable = false, columnDefinition = "TEXT")
    private String responsePayloadJson;

    @Column(name = "correlation_id", length = 128)
    private String correlationId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected LoanDisbursementRequestLog() {
    }

    public LoanDisbursementRequestLog(
            LoanAccount loanAccount,
            String actorUsername,
            BigDecimal amount,
            String providerName,
            String providerRequestId,
            String providerStatus,
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
        this.requestPayloadJson = requestPayloadJson;
        this.responsePayloadJson = responsePayloadJson;
        this.correlationId = correlationId;
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

    public String getRequestPayloadJson() {
        return requestPayloadJson;
    }

    public String getResponsePayloadJson() {
        return responsePayloadJson;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
