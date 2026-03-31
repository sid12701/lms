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
import java.util.UUID;

@Entity
@Table(name = "loan_application_document_checklist")
public class LoanApplicationDocumentChecklist {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "loan_application_id", nullable = false)
    private LoanApplication loanApplication;

    @Enumerated(EnumType.STRING)
    @Column(name = "document_type", nullable = false, length = 64)
    private LoanApplicationDocumentType documentType;

    @Column(name = "required", nullable = false)
    private boolean required;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private LoanApplicationDocumentChecklistStatus status;

    @Column(name = "note", length = 500)
    private String note;

    @Column(name = "file_name", length = 255)
    private String fileName;

    @Column(name = "file_reference", length = 255)
    private String fileReference;

    @Column(name = "file_reference_source", length = 255)
    private String fileReferenceSource;

    @Column(name = "content_type", length = 128)
    private String contentType;

    @Column(name = "uploaded_at")
    private Instant uploadedAt;

    @Column(name = "uploaded_by_username", length = 128)
    private String uploadedByUsername;

    @Column(name = "updated_by_username", length = 128)
    private String updatedByUsername;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected LoanApplicationDocumentChecklist() {
    }

    public LoanApplicationDocumentChecklist(
            LoanApplication loanApplication,
            LoanApplicationDocumentType documentType,
            boolean required,
            LoanApplicationDocumentChecklistStatus status,
            String note,
            String updatedByUsername
    ) {
        this.id = UUID.randomUUID();
        this.loanApplication = loanApplication;
        this.documentType = documentType;
        this.required = required;
        this.status = status;
        this.note = normalizeOptional(note);
        this.updatedByUsername = normalizeOptional(updatedByUsername);
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

    public LoanApplication getLoanApplication() {
        return loanApplication;
    }

    public LoanApplicationDocumentType getDocumentType() {
        return documentType;
    }

    public boolean isRequired() {
        return required;
    }

    public LoanApplicationDocumentChecklistStatus getStatus() {
        return status;
    }

    public String getNote() {
        return note;
    }

    public String getFileName() {
        return fileName;
    }

    public String getFileReference() {
        return fileReference;
    }

    public String getFileReferenceSource() {
        return fileReferenceSource;
    }

    public String getContentType() {
        return contentType;
    }

    public Instant getUploadedAt() {
        return uploadedAt;
    }

    public String getUploadedByUsername() {
        return uploadedByUsername;
    }

    public String getUpdatedByUsername() {
        return updatedByUsername;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void update(
            LoanApplicationDocumentChecklistStatus status,
            String note,
            String updatedByUsername,
            String fileName,
            String fileReference,
            String fileReferenceSource,
            String contentType,
            Instant uploadedAt,
            String uploadedByUsername
    ) {
        this.status = status;
        if (note != null) {
            this.note = normalizeOptional(note);
        }
        this.fileName = normalizeOptional(fileName);
        this.fileReference = normalizeOptional(fileReference);
        this.fileReferenceSource = normalizeOptional(fileReferenceSource);
        this.contentType = normalizeOptional(contentType);
        this.uploadedAt = uploadedAt;
        this.uploadedByUsername = normalizeOptional(uploadedByUsername);
        this.updatedByUsername = normalizeOptional(updatedByUsername);
    }

    private static String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }

        String normalized = value.trim();
        return normalized.isBlank() ? null : normalized;
    }
}
