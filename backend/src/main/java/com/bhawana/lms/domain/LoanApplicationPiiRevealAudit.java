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
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "loan_application_pii_reveal_audit")
public class LoanApplicationPiiRevealAudit {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "loan_application_id", nullable = false)
    private LoanApplication loanApplication;

    @Column(name = "lsp_id", nullable = false)
    private UUID lspId;

    @Column(name = "actor_username", nullable = false, length = 255)
    private String actorUsername;

    @Column(name = "revealed_fields", nullable = false, length = 1000)
    private String revealedFields;

    @Column(name = "correlation_id", length = 128)
    private String correlationId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected LoanApplicationPiiRevealAudit() {
    }

    public LoanApplicationPiiRevealAudit(
            LoanApplication loanApplication,
            UUID lspId,
            String actorUsername,
            List<String> revealedFields,
            String correlationId
    ) {
        this.id = UUID.randomUUID();
        this.loanApplication = loanApplication;
        this.lspId = lspId;
        this.actorUsername = actorUsername;
        this.revealedFields = revealedFields == null || revealedFields.isEmpty()
                ? ""
                : String.join(",", revealedFields);
        this.correlationId = correlationId;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public LoanApplication getLoanApplication() {
        return loanApplication;
    }

    public UUID getLspId() {
        return lspId;
    }

    public String getActorUsername() {
        return actorUsername;
    }

    public String getRevealedFields() {
        return revealedFields;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
