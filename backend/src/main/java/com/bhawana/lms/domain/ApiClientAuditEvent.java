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
@Table(name = "api_client_audit_event")
public class ApiClientAuditEvent {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "api_client_id", nullable = false)
    private ApiClient apiClient;

    @Column(name = "actor_username", nullable = false, length = 255)
    private String actorUsername;

    @Column(nullable = false, length = 64)
    private String action;

    @Column(name = "details_json", nullable = false, columnDefinition = "TEXT")
    private String detailsJson;

    @Column(name = "correlation_id", length = 128)
    private String correlationId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ApiClientAuditEvent() {
    }

    public ApiClientAuditEvent(
            ApiClient apiClient,
            String actorUsername,
            String action,
            String detailsJson,
            String correlationId
    ) {
        this.id = UUID.randomUUID();
        this.apiClient = apiClient;
        this.actorUsername = actorUsername;
        this.action = action;
        this.detailsJson = detailsJson;
        this.correlationId = correlationId;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}
