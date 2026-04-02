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
import java.time.Instant;
import java.util.UUID;

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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private WebhookEventOutboxStatus status;

    @Column(name = "payload_json", nullable = false, columnDefinition = "TEXT")
    private String payloadJson;

    @Column(name = "correlation_id", length = 128)
    private String correlationId;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected WebhookEventOutbox() {
    }

    public WebhookEventOutbox(
            Lsp lsp,
            WebhookEventType eventType,
            String aggregateType,
            String aggregateId,
            WebhookEventOutboxStatus status,
            String payloadJson,
            String correlationId
    ) {
        this.id = UUID.randomUUID();
        this.lsp = lsp;
        this.eventType = eventType;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.status = status;
        this.payloadJson = payloadJson;
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

    public WebhookEventOutboxStatus getStatus() {
        return status;
    }

    public String getPayloadJson() {
        return payloadJson;
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

    public Instant getDeliveredAt() {
        return deliveredAt;
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

    public void markDelivered(Instant attemptedAt) {
        this.status = WebhookEventOutboxStatus.DELIVERED;
        this.attemptCount += 1;
        this.lastAttemptAt = attemptedAt;
        this.nextAttemptAt = null;
        this.deliveredAt = attemptedAt;
        this.lastError = null;
    }

    public void markRetryableFailure(Instant attemptedAt, Instant retryAt, String errorMessage) {
        this.status = WebhookEventOutboxStatus.RETRYABLE_FAILURE;
        this.attemptCount += 1;
        this.lastAttemptAt = attemptedAt;
        this.nextAttemptAt = retryAt;
        this.lastError = truncate(errorMessage);
    }

    public void markPermanentFailure(Instant attemptedAt, String errorMessage) {
        this.status = WebhookEventOutboxStatus.PERMANENT_FAILURE;
        this.attemptCount += 1;
        this.lastAttemptAt = attemptedAt;
        this.nextAttemptAt = null;
        this.lastError = truncate(errorMessage);
    }

    private static String truncate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }
}
