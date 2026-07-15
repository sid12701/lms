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
@Table(name = "borrower_pii_reveal_audit")
public class BorrowerPiiRevealAudit {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "borrower_id", nullable = false)
    private Borrower borrower;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lsp_id")
    private Lsp lsp;

    @Column(name = "actor_username", nullable = false, length = 255)
    private String actorUsername;

    @Column(name = "actor_type", nullable = false, length = 64)
    private String actorType;

    @Column(name = "revealed_fields", nullable = false, length = 255)
    private String revealedFields;

    @Column(name = "client_ip", length = 64)
    private String clientIp;

    @Column(name = "correlation_id", length = 128)
    private String correlationId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected BorrowerPiiRevealAudit() {
    }

    public BorrowerPiiRevealAudit(
            Borrower borrower,
            Lsp lsp,
            String actorUsername,
            String actorType,
            String revealedFields,
            String clientIp,
            String correlationId
    ) {
        this.id = UUID.randomUUID();
        this.borrower = borrower;
        this.lsp = lsp;
        this.actorUsername = actorUsername;
        this.actorType = actorType;
        this.revealedFields = revealedFields;
        this.clientIp = clientIp;
        this.correlationId = correlationId;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public UUID getId() {
        return id;
    }

    public Borrower getBorrower() {
        return borrower;
    }

    public Lsp getLsp() {
        return lsp;
    }

    public String getActorUsername() {
        return actorUsername;
    }

    public String getActorType() {
        return actorType;
    }

    public String getRevealedFields() {
        return revealedFields;
    }

    public String getClientIp() {
        return clientIp;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
