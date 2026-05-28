package com.bhawana.lms.domain;

import com.fasterxml.jackson.databind.JsonNode;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "loan_application_intake_audit")
public class LoanApplicationIntakeAudit {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "loan_application_id", nullable = false)
    private LoanApplication loanApplication;

    @Column(name = "actor_username", nullable = false, length = 255)
    private String actorUsername;

    @Column(name = "correlation_id", length = 128)
    private String correlationId;

    @Column(name = "payload_json", nullable = false, columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private JsonNode payloadJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected LoanApplicationIntakeAudit() {
    }

    public LoanApplicationIntakeAudit(
            LoanApplication loanApplication,
            String actorUsername,
            String correlationId,
            String payloadJson
    ) {
        this.id = UUID.randomUUID();
        this.loanApplication = loanApplication;
        this.actorUsername = actorUsername;
        this.correlationId = correlationId;
        this.payloadJson = JsonPayloads.requiredObject(payloadJson, "payloadJson");
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public LoanApplication getLoanApplication() {
        return loanApplication;
    }

    public String getActorUsername() {
        return actorUsername;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public String getPayloadJson() {
        return JsonPayloads.asString(payloadJson);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
