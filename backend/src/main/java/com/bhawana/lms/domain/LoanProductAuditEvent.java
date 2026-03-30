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
@Table(name = "loan_product_audit_event")
public class LoanProductAuditEvent {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "loan_product_id", nullable = false)
    private LoanProduct loanProduct;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 64)
    private LoanProductAuditAction action;

    @Column(name = "actor_username", nullable = false, length = 255)
    private String actorUsername;

    @Column(nullable = false, length = 500)
    private String summary;

    @Column(name = "correlation_id", length = 128)
    private String correlationId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected LoanProductAuditEvent() {
    }

    public LoanProductAuditEvent(
            LoanProduct loanProduct,
            LoanProductAuditAction action,
            String actorUsername,
            String summary,
            String correlationId
    ) {
        this.id = UUID.randomUUID();
        this.loanProduct = loanProduct;
        this.action = action;
        this.actorUsername = actorUsername;
        this.summary = summary;
        this.correlationId = correlationId;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public LoanProduct getLoanProduct() {
        return loanProduct;
    }

    public LoanProductAuditAction getAction() {
        return action;
    }

    public String getActorUsername() {
        return actorUsername;
    }

    public String getSummary() {
        return summary;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
