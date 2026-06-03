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
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "webhook_event_outbox")
public class WebhookEventOutbox {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "lsp_id", nullable = false)
    private Lsp lsp;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 64)
    private WebhookEventType eventType;

    @Column(name = "aggregate_type", nullable = false, length = 64)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, length = 64)
    private String aggregateId;

    @Column(name = "loan_application_id")
    private UUID loanApplicationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private WebhookEventOutboxStatus status;

    @Column(name = "payload_json", nullable = false, columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private JsonNode payloadJson;

    @Column(name = "correlation_id", length = 128)
    private String correlationId;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "claim_expires_at")
    private Instant claimExpiresAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "entity_version", nullable = false)
    private long entityVersion;

    protected WebhookEventOutbox() {
    }

    public WebhookEventOutbox(
            Lsp lsp,
            WebhookEventType eventType,
            String aggregateType,
            String aggregateId,
            UUID loanApplicationId,
            WebhookEventOutboxStatus status,
            String payloadJson,
            String correlationId
    ) {
        this.id = UUID.randomUUID();
        this.lsp = lsp;
        this.eventType = eventType;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.loanApplicationId = loanApplicationId;
        this.status = status;
        this.payloadJson = JsonPayloads.requiredObject(payloadJson, "payloadJson");
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

    public Lsp getLsp() {
        return lsp;
    }

    public WebhookEventType getEventType() {
        return eventType;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public String getAggregateId() {
        return aggregateId;
    }

    public UUID getLoanApplicationId() {
        return loanApplicationId;
    }

    public WebhookEventOutboxStatus getStatus() {
        return status;
    }

    public String getPayloadJson() {
        return JsonPayloads.asString(payloadJson);
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public Instant getLastAttemptAt() {
        return lastAttemptAt;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public Instant getClaimExpiresAt() {
        return claimExpiresAt;
    }

    public Instant getDeliveredAt() {
        return deliveredAt;
    }

    public void claim(Instant claimExpiresAt) {
        this.status = WebhookEventOutboxStatus.IN_FLIGHT;
        this.claimExpiresAt = claimExpiresAt;
    }

    public String getLastError() {
        return lastError;
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

    public void markDelivered(Instant attemptedAt) {
        this.status = WebhookEventOutboxStatus.DELIVERED;
        this.attemptCount += 1;
        this.lastAttemptAt = attemptedAt;
        this.nextAttemptAt = null;
        this.claimExpiresAt = null;
        this.deliveredAt = attemptedAt;
        this.lastError = null;
    }

    public void markRetryableFailure(Instant attemptedAt, Instant retryAt, String errorMessage) {
        this.status = WebhookEventOutboxStatus.RETRYABLE_FAILURE;
        this.attemptCount += 1;
        this.lastAttemptAt = attemptedAt;
        this.nextAttemptAt = retryAt;
        this.claimExpiresAt = null;
        this.lastError = truncate(errorMessage);
    }

    public void markPermanentFailure(Instant attemptedAt, String errorMessage) {
        this.status = WebhookEventOutboxStatus.PERMANENT_FAILURE;
        this.attemptCount += 1;
        this.lastAttemptAt = attemptedAt;
        this.nextAttemptAt = null;
        this.claimExpiresAt = null;
        this.lastError = truncate(errorMessage);
    }

    private static String truncate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }
}
