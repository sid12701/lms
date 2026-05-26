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
@Table(name = "ops_alert")
public class OpsAlert {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 64)
    private OpsAlertType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private OpsAlertSeverity severity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private OpsAlertStatus status;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(nullable = false, length = 1000)
    private String message;

    @Column(name = "subject_type", length = 64)
    private String subjectType;

    @Column(name = "subject_id")
    private UUID subjectId;

    @Column(name = "correlation_id", length = 128)
    private String correlationId;

    @Column(name = "context_json", columnDefinition = "TEXT")
    private String contextJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "acknowledged_at")
    private Instant acknowledgedAt;

    @Column(name = "acknowledged_by_username", length = 255)
    private String acknowledgedByUsername;

    @Column(name = "acknowledgement_note", length = 500)
    private String acknowledgementNote;

    protected OpsAlert() {
    }

    public OpsAlert(
            OpsAlertType type,
            OpsAlertSeverity severity,
            String title,
            String message,
            String subjectType,
            UUID subjectId,
            String correlationId,
            String contextJson
    ) {
        this.id = UUID.randomUUID();
        this.type = type;
        this.severity = severity;
        this.status = OpsAlertStatus.NEW;
        this.title = title;
        this.message = message;
        this.subjectType = normalize(subjectType);
        this.subjectId = subjectId;
        this.correlationId = normalize(correlationId);
        this.contextJson = normalize(contextJson);
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public OpsAlertType getType() {
        return type;
    }

    public OpsAlertSeverity getSeverity() {
        return severity;
    }

    public OpsAlertStatus getStatus() {
        return status;
    }

    public String getTitle() {
        return title;
    }

    public String getMessage() {
        return message;
    }

    public String getSubjectType() {
        return subjectType;
    }

    public UUID getSubjectId() {
        return subjectId;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public String getContextJson() {
        return contextJson;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getAcknowledgedAt() {
        return acknowledgedAt;
    }

    public String getAcknowledgedByUsername() {
        return acknowledgedByUsername;
    }

    public String getAcknowledgementNote() {
        return acknowledgementNote;
    }

    public void acknowledge(String actorUsername, String note) {
        this.status = OpsAlertStatus.ACKNOWLEDGED;
        this.acknowledgedAt = Instant.now();
        this.acknowledgedByUsername = normalize(actorUsername);
        this.acknowledgementNote = normalize(note);
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isBlank() ? null : normalized;
    }
}
