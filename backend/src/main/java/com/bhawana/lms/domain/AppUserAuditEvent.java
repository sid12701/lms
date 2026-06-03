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
@Table(name = "app_user_audit_event")
public class AppUserAuditEvent {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @Column(name = "actor_username", nullable = false, length = 255)
    private String actorUsername;

    @Column(name = "before_state_json", nullable = false, columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private JsonNode beforeStateJson;

    @Column(name = "after_state_json", nullable = false, columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private JsonNode afterStateJson;

    @Column(name = "correlation_id", length = 128)
    private String correlationId;

    @Column(name = "actor_ip", length = 64)
    private String actorIp;

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
        this(user, actorUsername, beforeStateJson, afterStateJson, correlationId, null);
    }

    public AppUserAuditEvent(
            AppUser user,
            String actorUsername,
            String beforeStateJson,
            String afterStateJson,
            String correlationId,
            String actorIp
    ) {
        this.id = UUID.randomUUID();
        this.user = user;
        this.actorUsername = actorUsername;
        this.beforeStateJson = JsonPayloads.requiredObject(beforeStateJson, "beforeStateJson");
        this.afterStateJson = JsonPayloads.requiredObject(afterStateJson, "afterStateJson");
        this.correlationId = correlationId;
        this.actorIp = actorIp;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return user.getId();
    }

    public String getActorUsername() {
        return actorUsername;
    }

    public JsonNode getBeforeStateJson() {
        return beforeStateJson;
    }

    public JsonNode getAfterStateJson() {
        return afterStateJson;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public String getActorIp() {
        return actorIp;
    }
}
