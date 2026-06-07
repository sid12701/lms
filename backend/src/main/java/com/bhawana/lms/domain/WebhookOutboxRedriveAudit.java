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
@Table(name = "webhook_outbox_redrive_audit")
public class WebhookOutboxRedriveAudit {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "webhook_event_id", nullable = false)
    private WebhookEventOutbox webhookEvent;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "lsp_id", nullable = false)
    private Lsp lsp;

    @Column(name = "actor_username", nullable = false, length = 255)
    private String actorUsername;

    @Column(name = "actor_ip", length = 64)
    private String actorIp;

    @Column(name = "correlation_id", length = 128)
    private String correlationId;

    @Column(name = "redrive_count", nullable = false)
    private int redriveCount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected WebhookOutboxRedriveAudit() {
    }

    public WebhookOutboxRedriveAudit(
            WebhookEventOutbox webhookEvent,
            Lsp lsp,
            String actorUsername,
            String actorIp,
            String correlationId,
            int redriveCount
    ) {
        this.id = UUID.randomUUID();
        this.webhookEvent = webhookEvent;
        this.lsp = lsp;
        this.actorUsername = actorUsername;
        this.actorIp = actorIp;
        this.correlationId = correlationId;
        this.redriveCount = redriveCount;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getWebhookEventId() {
        return webhookEvent.getId();
    }

    public UUID getLspId() {
        return lsp.getId();
    }

    public String getActorUsername() {
        return actorUsername;
    }

    public String getActorIp() {
        return actorIp;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public int getRedriveCount() {
        return redriveCount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
