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
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
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

    @Column(name = "file_name", length = 255)
    private String fileName;

    @Column(name = "media_type", length = 128)
    private String mediaType;

    @Column(name = "report_content", columnDefinition = "TEXT")
    private String reportContent;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

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
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
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

    public String getReportContent() {
        return reportContent;
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

    public void markProcessing() {
        this.status = ReportRequestStatus.PROCESSING;
        this.errorMessage = null;
    }

    public void markCompleted(String fileName, String mediaType, String reportContent, Instant completedAt) {
        this.status = ReportRequestStatus.COMPLETED;
        this.fileName = fileName;
        this.mediaType = mediaType;
        this.reportContent = reportContent;
        this.completedAt = completedAt;
        this.errorMessage = null;
    }

    public void markFailed(String errorMessage) {
        this.status = ReportRequestStatus.FAILED;
        this.errorMessage = truncate(errorMessage);
        this.reportContent = null;
        this.fileName = null;
        this.mediaType = null;
        this.completedAt = null;
    }

    public void markNotificationSent(Instant sentAt) {
        this.notificationSentAt = sentAt;
        this.notificationErrorMessage = null;
    }

    public void markNotificationFailed(String errorMessage) {
        this.notificationSentAt = null;
        this.notificationErrorMessage = truncate(errorMessage);
    }

    private static String truncate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
