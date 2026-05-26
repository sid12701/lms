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

    @Column(name = "file_reference", length = 500)
    private String fileReference;

    @Column(name = "source_reference", length = 500)
    private String sourceReference;

    @Column(name = "content_type", length = 128)
    private String contentType;

    @Column(name = "lms_managed_content", nullable = false)
    private boolean lmsManagedContent;

    @Column(name = "storage_key", length = 500)
    private String storageKey;

    @Column(name = "file_checksum", length = 128)
    private String fileChecksum;

    @Column(name = "file_size_bytes")
    private Long fileSizeBytes;

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

    public String getSourceReference() {
        return sourceReference;
    }

    public String getContentType() {
        return contentType;
    }

    public boolean isLmsManagedContent() {
        return lmsManagedContent;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public String getFileChecksum() {
        return fileChecksum;
    }

    public Long getFileSizeBytes() {
        return fileSizeBytes;
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
            String sourceReference,
            String contentType
    ) {
        update(
                status,
                note,
                updatedByUsername,
                fileName,
                fileReference,
                sourceReference,
                contentType,
                null,
                null,
                null,
                false
        );
    }

    public void update(
            LoanApplicationDocumentChecklistStatus status,
            String note,
            String updatedByUsername,
            String fileName,
            String fileReference,
            String sourceReference,
            String contentType,
            Long fileSizeBytes,
            String fileChecksum,
            String storageKey,
            boolean lmsManagedContent
    ) {
        this.status = status;
        this.note = normalizeOptional(note);
        this.fileName = normalizeOptional(fileName);
        this.fileReference = normalizeOptional(fileReference);
        this.sourceReference = normalizeOptional(sourceReference);
        this.contentType = normalizeOptional(contentType);
        this.updatedByUsername = normalizeOptional(updatedByUsername);
        this.fileSizeBytes = fileSizeBytes;
        this.fileChecksum = normalizeOptional(fileChecksum);
        this.storageKey = normalizeOptional(storageKey);
        this.lmsManagedContent = lmsManagedContent;
        if (hasUploadMetadata()) {
            this.uploadedAt = Instant.now();
            this.uploadedByUsername = this.updatedByUsername;
        } else {
            this.uploadedAt = null;
            this.uploadedByUsername = null;
            this.fileSizeBytes = null;
            this.fileChecksum = null;
            this.storageKey = null;
            this.lmsManagedContent = false;
        }
    }

    private boolean hasUploadMetadata() {
        return fileName != null || fileReference != null || sourceReference != null || contentType != null;
    }

    private static String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }

        String normalized = value.trim();
        return normalized.isBlank() ? null : normalized;
    }
}
