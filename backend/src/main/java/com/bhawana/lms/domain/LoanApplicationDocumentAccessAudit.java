package com.bhawana.lms.domain;

import jakarta.persistence.Column;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.ElementCollection;
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
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "loan_application_document_access_audit")
public class LoanApplicationDocumentAccessAudit {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "loan_application_id", nullable = false)
    private LoanApplication loanApplication;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 64)
    private LoanApplicationDocumentAccessAuditAction action;

    @Column(name = "actor_username", nullable = false, length = 255)
    private String actorUsername;

    @Column(nullable = false, length = 500)
    private String summary;

    @Column(name = "document_types", nullable = false, length = 500)
    private String documentTypes;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "loan_application_document_access_audit_type",
            joinColumns = @JoinColumn(name = "audit_id")
    )
    @Enumerated(EnumType.STRING)
    @Column(name = "document_type", nullable = false, length = 64)
    private Set<LoanApplicationDocumentType> normalizedDocumentTypes = new LinkedHashSet<>();

    @Column(name = "correlation_id", length = 128)
    private String correlationId;

    @Column(name = "actor_ip", length = 64)
    private String actorIp;

    @Column(name = "byte_count")
    private Long byteCount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected LoanApplicationDocumentAccessAudit() {
    }

    public LoanApplicationDocumentAccessAudit(
            LoanApplication loanApplication,
            LoanApplicationDocumentAccessAuditAction action,
            String actorUsername,
            String summary,
            List<LoanApplicationDocumentType> documentTypes,
            String correlationId
    ) {
        this.id = UUID.randomUUID();
        this.loanApplication = loanApplication;
        this.action = action;
        this.actorUsername = actorUsername;
        this.summary = summary;
        this.documentTypes = documentTypes == null || documentTypes.isEmpty()
                ? ""
                : documentTypes.stream().map(Enum::name).reduce((left, right) -> left + "," + right).orElse("");
        this.normalizedDocumentTypes = documentTypes == null
                ? new LinkedHashSet<>()
                : new LinkedHashSet<>(documentTypes);
        this.correlationId = correlationId;
        this.actorIp = null;
        this.byteCount = null;
    }

    public LoanApplicationDocumentAccessAudit(
            LoanApplication loanApplication,
            LoanApplicationDocumentAccessAuditAction action,
            String actorUsername,
            String summary,
            List<LoanApplicationDocumentType> documentTypes,
            String correlationId,
            String actorIp,
            Long byteCount
    ) {
        this(loanApplication, action, actorUsername, summary, documentTypes, correlationId);
        this.actorIp = actorIp;
        this.byteCount = byteCount;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public LoanApplication getLoanApplication() {
        return loanApplication;
    }

    public LoanApplicationDocumentAccessAuditAction getAction() {
        return action;
    }

    public String getActorUsername() {
        return actorUsername;
    }

    public String getSummary() {
        return summary;
    }

    public List<LoanApplicationDocumentType> getDocumentTypes() {
        if (normalizedDocumentTypes != null && !normalizedDocumentTypes.isEmpty()) {
            return normalizedDocumentTypes.stream()
                    .sorted(Comparator.comparingInt(Enum::ordinal))
                    .toList();
        }

        if (documentTypes == null || documentTypes.isBlank()) {
            return List.of();
        }

        return Arrays.stream(documentTypes.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(LoanApplicationDocumentType::valueOf)
                .toList();
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public String getActorIp() {
        return actorIp;
    }

    public Long getByteCount() {
        return byteCount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
