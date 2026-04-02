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

@Entity
@Table(name = "webhook_event_delivery_attempt")
public class WebhookEventDeliveryAttempt {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "outbox_event_id", nullable = false)
    private WebhookEventOutbox outboxEvent;

    @Column(name = "attempt_number", nullable = false)
    private int attemptNumber;

    @Column(name = "request_url", nullable = false, length = 500)
    private String requestUrl;

    @Column(name = "request_event_type", nullable = false, length = 64)
    private String requestEventType;

    @Column(name = "request_delivery_id", nullable = false, length = 64)
    private String requestDeliveryId;

    @Column(name = "request_timestamp", nullable = false, length = 64)
    private String requestTimestamp;

    @Column(name = "request_signature", nullable = false, length = 255)
    private String requestSignature;

    @Column(name = "response_status_code")
    private Integer responseStatusCode;

    @Column(name = "response_body", columnDefinition = "TEXT")
    private String responseBody;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private WebhookEventDeliveryAttemptStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected WebhookEventDeliveryAttempt() {
    }

    public WebhookEventDeliveryAttempt(
            WebhookEventOutbox outboxEvent,
            int attemptNumber,
            String requestUrl,
            String requestEventType,
            String requestDeliveryId,
            String requestTimestamp,
            String requestSignature,
            Integer responseStatusCode,
            String responseBody,
            String errorMessage,
            WebhookEventDeliveryAttemptStatus status
    ) {
        this.id = UUID.randomUUID();
        this.outboxEvent = outboxEvent;
        this.attemptNumber = attemptNumber;
        this.requestUrl = requestUrl;
        this.requestEventType = requestEventType;
        this.requestDeliveryId = requestDeliveryId;
        this.requestTimestamp = requestTimestamp;
        this.requestSignature = requestSignature;
        this.responseStatusCode = responseStatusCode;
        this.responseBody = responseBody;
        this.errorMessage = errorMessage;
        this.status = status;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public WebhookEventOutbox getOutboxEvent() {
        return outboxEvent;
    }

    public int getAttemptNumber() {
        return attemptNumber;
    }

    public String getRequestUrl() {
        return requestUrl;
    }

    public String getRequestEventType() {
        return requestEventType;
    }

    public String getRequestDeliveryId() {
        return requestDeliveryId;
    }

    public String getRequestTimestamp() {
        return requestTimestamp;
    }

    public String getRequestSignature() {
        return requestSignature;
    }

    public Integer getResponseStatusCode() {
        return responseStatusCode;
    }

    public String getResponseBody() {
        return responseBody;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public WebhookEventDeliveryAttemptStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
