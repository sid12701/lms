package com.bhawana.lms.domain;

import com.bhawana.lms.common.util.Strings;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/**
 * First-class LSP↔borrower visibility relationship (Spec S19). Sibling of the
 * legacy {@code borrower_lsp_access} element-collection table during dual-write;
 * carries sourcing timestamps and consent placeholders the collection could not.
 */
@Entity
@Table(
        name = "borrower_lsp_relationship",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_borrower_lsp_relationship",
                columnNames = {"borrower_id", "lsp_id"}
        )
)
public class BorrowerLspRelationship {

    public static final String SOURCE_LOAN_ONBOARDING = "LOAN_ONBOARDING";
    public static final String SOURCE_BACKFILL = "BACKFILL";
    public static final String SOURCE_SEED = "SEED";

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "borrower_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Borrower borrower;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "lsp_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Lsp lsp;

    @Column(name = "first_sourced_at", nullable = false)
    private Instant firstSourcedAt;

    @Column(name = "last_touched_at", nullable = false)
    private Instant lastTouchedAt;

    @Column(name = "source_channel", length = 64)
    private String sourceChannel;

    @Column(name = "consent_captured_at")
    private Instant consentCapturedAt;

    @Column(name = "consent_version", length = 64)
    private String consentVersion;

    protected BorrowerLspRelationship() {
    }

    public BorrowerLspRelationship(Borrower borrower, Lsp lsp, String sourceChannel) {
        this.id = UUID.randomUUID();
        this.borrower = borrower;
        this.lsp = lsp;
        this.sourceChannel = Strings.normalizeOptional(sourceChannel);
        Instant now = Instant.now();
        this.firstSourcedAt = now;
        this.lastTouchedAt = now;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (firstSourcedAt == null) {
            firstSourcedAt = now;
        }
        if (lastTouchedAt == null) {
            lastTouchedAt = now;
        }
    }

    @PreUpdate
    void onUpdate() {
        lastTouchedAt = Instant.now();
    }

    public void touch() {
        this.lastTouchedAt = Instant.now();
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

    public Instant getFirstSourcedAt() {
        return firstSourcedAt;
    }

    public Instant getLastTouchedAt() {
        return lastTouchedAt;
    }

    public String getSourceChannel() {
        return sourceChannel;
    }

    public Instant getConsentCapturedAt() {
        return consentCapturedAt;
    }

    public String getConsentVersion() {
        return consentVersion;
    }
}
