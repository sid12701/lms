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
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "lsp_audit_event")
public class LspAuditEvent {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "lsp_id", nullable = false)
    private Lsp lsp;

    @Column(name = "actor_username", nullable = false, length = 255)
    private String actorUsername;

    @Column(nullable = false, length = 64)
    private String action;

    @Enumerated(EnumType.STRING)
    @Column(length = 64)
    private LspStatusChangeReason reason;

    @Column(columnDefinition = "text")
    private String note;

    @Column(name = "cascaded_client_count", nullable = false)
    private int cascadedClientCount;

    @Column(name = "details_json", nullable = false, columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private JsonNode detailsJson;

    @Column(name = "correlation_id", length = 128)
    private String correlationId;

    @Column(name = "actor_ip", length = 64)
    private String actorIp;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected LspAuditEvent() {
    }

    public LspAuditEvent(
            Lsp lsp,
            String actorUsername,
            String action,
            LspStatusChangeReason reason,
            String note,
            int cascadedClientCount,
            String detailsJson,
            String correlationId
    ) {
        this(lsp, actorUsername, action, reason, note, cascadedClientCount, detailsJson, correlationId, null);
    }

    public LspAuditEvent(
            Lsp lsp,
            String actorUsername,
            String action,
            LspStatusChangeReason reason,
            String note,
            int cascadedClientCount,
            String detailsJson,
            String correlationId,
            String actorIp
    ) {
        this.id = UUID.randomUUID();
        this.lsp = lsp;
        this.actorUsername = actorUsername;
        this.action = action;
        this.reason = reason;
        this.note = note;
        this.cascadedClientCount = cascadedClientCount;
        this.detailsJson = JsonPayloads.requiredObject(detailsJson, "detailsJson");
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

    public Lsp getLsp() {
        return lsp;
    }

    public String getActorUsername() {
        return actorUsername;
    }

    public String getAction() {
        return action;
    }

    public LspStatusChangeReason getReason() {
        return reason;
    }

    public String getNote() {
        return note;
    }

    public int getCascadedClientCount() {
        return cascadedClientCount;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public JsonNode getDetailsJson() {
        return detailsJson;
    }

    public String getActorIp() {
        return actorIp;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
