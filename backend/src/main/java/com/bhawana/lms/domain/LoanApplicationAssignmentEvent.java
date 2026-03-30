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
@Table(name = "loan_application_assignment_event")
public class LoanApplicationAssignmentEvent {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "loan_application_id", nullable = false)
    private LoanApplication loanApplication;

    @Column(name = "from_assignee_username", length = 128)
    private String fromAssigneeUsername;

    @Column(name = "to_assignee_username", length = 128)
    private String toAssigneeUsername;

    @Column(name = "actor_username", nullable = false, length = 128)
    private String actorUsername;

    @Column(length = 500)
    private String note;

    @Column(name = "correlation_id", length = 128)
    private String correlationId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected LoanApplicationAssignmentEvent() {
    }

    public LoanApplicationAssignmentEvent(
            LoanApplication loanApplication,
            String fromAssigneeUsername,
            String toAssigneeUsername,
            String actorUsername,
            String note,
            String correlationId
    ) {
        this.id = UUID.randomUUID();
        this.loanApplication = loanApplication;
        this.fromAssigneeUsername = fromAssigneeUsername;
        this.toAssigneeUsername = toAssigneeUsername;
        this.actorUsername = actorUsername;
        this.note = note;
        this.correlationId = correlationId;
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

    public String getFromAssigneeUsername() {
        return fromAssigneeUsername;
    }

    public String getToAssigneeUsername() {
        return toAssigneeUsername;
    }

    public String getActorUsername() {
        return actorUsername;
    }

    public String getNote() {
        return note;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
