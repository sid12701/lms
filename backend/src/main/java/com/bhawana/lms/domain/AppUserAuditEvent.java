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
@Table(name = "app_user_audit_event")
public class AppUserAuditEvent {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @Column(name = "actor_username", nullable = false, length = 255)
    private String actorUsername;

    @Column(name = "before_state_json", nullable = false, columnDefinition = "TEXT")
    private String beforeStateJson;

    @Column(name = "after_state_json", nullable = false, columnDefinition = "TEXT")
    private String afterStateJson;

    @Column(name = "correlation_id", length = 128)
    private String correlationId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AppUserAuditEvent() {
    }

    public AppUserAuditEvent(
            AppUser user,
            String actorUsername,
            String beforeStateJson,
            String afterStateJson,
            String correlationId
    ) {
        this.id = UUID.randomUUID();
        this.user = user;
        this.actorUsername = actorUsername;
        this.beforeStateJson = beforeStateJson;
        this.afterStateJson = afterStateJson;
        this.correlationId = correlationId;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }
}
