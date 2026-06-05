package com.bhawana.lms.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "auth_event_audit")
public class AuthEventAudit {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String username;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "api_client_id")
    private UUID apiClientId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 64)
    private AuthEventType eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_reason", length = 64)
    private AuthEventFailureReason failureReason;

    @Column(name = "actor_ip", length = 64)
    private String actorIp;

    @Column(name = "correlation_id", length = 128)
    private String correlationId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AuthEventAudit() {
    }

    public AuthEventAudit(
            String username,
            UUID userId,
            UUID apiClientId,
            AuthEventType eventType,
            AuthEventFailureReason failureReason,
            String actorIp,
            String correlationId
    ) {
        this.id = UUID.randomUUID();
        this.username = username;
        this.userId = userId;
        this.apiClientId = apiClientId;
        this.eventType = eventType;
        this.failureReason = failureReason;
        this.actorIp = actorIp;
        this.correlationId = correlationId;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getApiClientId() {
        return apiClientId;
    }

    public AuthEventType getEventType() {
        return eventType;
    }

    public AuthEventFailureReason getFailureReason() {
        return failureReason;
    }

    public String getActorIp() {
        return actorIp;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
