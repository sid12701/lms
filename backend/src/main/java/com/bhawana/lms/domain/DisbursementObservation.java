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
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * H02 — immutable canonical provider evidence. One row per attempted initiate/poll, including
 * timeouts (disposition {@code UNKNOWN}), unresolved status queries, duplicates observed after
 * a terminal outcome ({@code isDuplicate}) and stale responses.
 *
 * <p>The mutable {@code loan_disbursement_request_log} stays the compatibility state; this
 * table is never updated or deleted in production (enforced by
 * {@code disbursement_observation_append_only}). Every row carries the frozen instruction
 * (beneficiary IFSC/account, rail), the claim attempt, correlation identity and the protected
 * request/response payloads so any outcome can be reconstructed without the live borrower row.
 */
@Entity
@Table(name = "disbursement_observation")
public class DisbursementObservation {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "loan_account_id", nullable = false)
    private LoanAccount loanAccount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "intent_id")
    private DisbursementIntent intent;

    // NULL = the stored evidence carries no reference: operator-only, never invented.
    @Column(name = "tran_ref_no", length = 64)
    private String tranRefNo;

    @Column(name = "attempt", nullable = false)
    private int attempt;

    // Durable per-call identity for polls (pre-network poll sequence). NULL for INITIATE/LEGACY.
    @Column(name = "poll_seq")
    private Integer pollSeq;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 16)
    private DisbursementObservationKind kind;

    @Enumerated(EnumType.STRING)
    @Column(name = "disposition", nullable = false, length = 16)
    private DisbursementDisposition disposition;

    @Column(name = "query_resolved", nullable = false)
    private boolean queryResolved;

    @Column(name = "is_duplicate", nullable = false)
    private boolean duplicate;

    @Column(name = "provider_name", nullable = false, length = 64)
    private String providerName;

    @Column(name = "provider_request_id", length = 128)
    private String providerRequestId;

    @Column(name = "act_code", length = 16)
    private String actCode;

    @Column(name = "bank_rrn", length = 32)
    private String bankRrn;

    @Enumerated(EnumType.STRING)
    @Column(name = "decline_kind", length = 16)
    private DisbursementDeclineKind declineKind;

    // NULL = not stored in provider evidence: never backfilled from borrower/intent rows.
    @Column(name = "beneficiary_ifsc", length = 16)
    private String beneficiaryIfsc;

    @Column(name = "beneficiary_account_number", length = 64)
    private String beneficiaryAccountNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_mode", length = 16)
    private DisbursementPaymentMode paymentMode;

    @Column(name = "request_payload_json", nullable = false, columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private JsonNode requestPayloadJson;

    @Column(name = "response_payload_json", nullable = false, columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private JsonNode responsePayloadJson;

    @Column(name = "correlation_id", length = 128)
    private String correlationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "provenance", nullable = false, length = 16)
    private DisbursementObservationProvenance provenance;

    @Column(name = "created_by", nullable = false, length = 255)
    private String createdBy;

    @Column(name = "observed_at", nullable = false)
    private Instant observedAt;

    protected DisbursementObservation() {
    }

    public DisbursementObservation(
            LoanAccount loanAccount,
            DisbursementIntent intent,
            String tranRefNo,
            int attempt,
            Integer pollSeq,
            DisbursementObservationKind kind,
            DisbursementDisposition disposition,
            boolean queryResolved,
            boolean duplicate,
            String providerName,
            String providerRequestId,
            String actCode,
            String bankRrn,
            DisbursementDeclineKind declineKind,
            String beneficiaryIfsc,
            String beneficiaryAccountNumber,
            DisbursementPaymentMode paymentMode,
            String requestPayloadJson,
            String responsePayloadJson,
            String correlationId,
            DisbursementObservationProvenance provenance,
            String createdBy
    ) {
        this.id = UUID.randomUUID();
        this.loanAccount = loanAccount;
        this.intent = intent;
        this.tranRefNo = tranRefNo;
        this.attempt = attempt;
        this.pollSeq = pollSeq;
        this.kind = kind;
        this.disposition = disposition;
        this.queryResolved = queryResolved;
        this.duplicate = duplicate;
        this.providerName = providerName;
        this.providerRequestId = providerRequestId;
        this.actCode = actCode;
        this.bankRrn = bankRrn;
        this.declineKind = declineKind;
        this.beneficiaryIfsc = beneficiaryIfsc;
        this.beneficiaryAccountNumber = beneficiaryAccountNumber;
        this.paymentMode = paymentMode;
        this.requestPayloadJson = JsonPayloads.requiredObject(requestPayloadJson, "requestPayloadJson");
        this.responsePayloadJson = JsonPayloads.requiredObject(responsePayloadJson, "responsePayloadJson");
        this.correlationId = correlationId;
        this.provenance = provenance;
        this.createdBy = createdBy;
    }

    @PrePersist
    void onCreate() {
        if (observedAt == null) {
            observedAt = Instant.now();
        }
    }

    /**
     * Definitive provider evidence usable for manual resolution: resolved SUCCESS/FAILED on a
     * stored reference. Reference-free rows can never authorize an outcome.
     */
    public boolean isDefinitive() {
        return tranRefNo != null
                && queryResolved
                && !duplicate
                && provenance == DisbursementObservationProvenance.LIVE
                && (disposition == DisbursementDisposition.SUCCESS
                        || disposition == DisbursementDisposition.FAILED);
    }

    public UUID getId() {
        return id;
    }

    public LoanAccount getLoanAccount() {
        return loanAccount;
    }

    public DisbursementIntent getIntent() {
        return intent;
    }

    public String getTranRefNo() {
        return tranRefNo;
    }

    public int getAttempt() {
        return attempt;
    }

    /** Pre-network poll sequence carried into the result; NULL for INITIATE/LEGACY rows. */
    public Integer getPollSeq() {
        return pollSeq;
    }

    public DisbursementObservationKind getKind() {
        return kind;
    }

    public DisbursementDisposition getDisposition() {
        return disposition;
    }

    public boolean isQueryResolved() {
        return queryResolved;
    }

    public boolean isDuplicate() {
        return duplicate;
    }

    public String getProviderName() {
        return providerName;
    }

    public String getProviderRequestId() {
        return providerRequestId;
    }

    public String getActCode() {
        return actCode;
    }

    public String getBankRrn() {
        return bankRrn;
    }

    public DisbursementDeclineKind getDeclineKind() {
        return declineKind;
    }

    public String getBeneficiaryIfsc() {
        return beneficiaryIfsc;
    }

    public String getBeneficiaryAccountNumber() {
        return beneficiaryAccountNumber;
    }

    public DisbursementPaymentMode getPaymentMode() {
        return paymentMode;
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

    public DisbursementObservationProvenance getProvenance() {
        return provenance;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public Instant getObservedAt() {
        return observedAt;
    }
}
