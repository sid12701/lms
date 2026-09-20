package com.bhawana.lms.domain;

import com.bhawana.lms.common.util.PersistedTimestamp;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "report_request")
public class ReportRequest {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "report_type", nullable = false, length = 64)
    private ReportType reportType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ReportRequestStatus status;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "lsp_id")
    private Lsp lsp;

    @Column(name = "disbursal_date_from")
    private LocalDate disbursalDateFrom;

    @Column(name = "disbursal_date_to")
    private LocalDate disbursalDateTo;

    @Column(name = "requested_by_username", nullable = false, length = 255)
    private String requestedByUsername;

    @Column(name = "notification_email", length = 255)
    private String notificationEmail;

    @Column(name = "notification_sent_at")
    private Instant notificationSentAt;

    @Column(name = "notification_error_message", length = 1000)
    private String notificationErrorMessage;

    @Column(name = "processing_owner", length = 160)
    private String processingOwner;

    @Column(name = "processing_expires_at")
    private Instant processingExpiresAt;

    @Column(name = "processing_attempt", nullable = false)
    private int processingAttempt;

    @Column(name = "notification_attempts", nullable = false)
    private int notificationAttempts;

    @Column(name = "file_name", length = 255)
    private String fileName;

    @Column(name = "media_type", length = 128)
    private String mediaType;

    @Column(name = "storage_key", length = 500)
    private String storageKey;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "entity_version", nullable = false)
    private long entityVersion;

    protected ReportRequest() {
    }

    public ReportRequest(
            ReportType reportType,
            Lsp lsp,
            LocalDate disbursalDateFrom,
            LocalDate disbursalDateTo,
            String requestedByUsername,
            String notificationEmail
    ) {
        this.id = UUID.randomUUID();
        this.reportType = reportType;
        this.status = ReportRequestStatus.PENDING;
        this.lsp = lsp;
        this.disbursalDateFrom = disbursalDateFrom;
        this.disbursalDateTo = disbursalDateTo;
        this.requestedByUsername = requestedByUsername;
        this.notificationEmail = normalize(notificationEmail);
    }

    @PrePersist
    void onCreate() {
        Instant now = PersistedTimestamp.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = PersistedTimestamp.now();
    }

    public UUID getId() {
        return id;
    }

    public ReportType getReportType() {
        return reportType;
    }

    public ReportRequestStatus getStatus() {
        return status;
    }

    public Lsp getLsp() {
        return lsp;
    }

    public LocalDate getDisbursalDateFrom() {
        return disbursalDateFrom;
    }

    public LocalDate getDisbursalDateTo() {
        return disbursalDateTo;
    }

    public String getRequestedByUsername() {
        return requestedByUsername;
    }

    public String getNotificationEmail() {
        return notificationEmail;
    }

    public Instant getNotificationSentAt() {
        return notificationSentAt;
    }

    public String getNotificationErrorMessage() {
        return notificationErrorMessage;
    }

    public String getFileName() {
        return fileName;
    }

    public String getMediaType() {
        return mediaType;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getEntityVersion() {
        return entityVersion;
    }

    public String getProcessingOwner() {
        return processingOwner;
    }

    public Instant getProcessingExpiresAt() {
        return processingExpiresAt;
    }

    public int getProcessingAttempt() {
        return processingAttempt;
    }

    public int getNotificationAttempts() {
        return notificationAttempts;
    }

    /**
     * Transitions the request into PROCESSING under a worker lease. Writes go through the
     * repository's fenced updates in production; this keeps the entity honest on paths (for
     * example the non-Postgres claim fallback) that mutate state through the entity.
     */
    public void claimProcessing(String owner, Instant expiresAt) {
        this.status = ReportRequestStatus.PROCESSING;
        this.processingOwner = owner;
        this.processingExpiresAt = PersistedTimestamp.normalize(expiresAt);
        this.processingAttempt += 1;
        this.errorMessage = null;
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
