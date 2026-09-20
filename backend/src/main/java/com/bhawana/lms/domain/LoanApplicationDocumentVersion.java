package com.bhawana.lms.domain;

import com.bhawana.lms.common.util.PersistedTimestamp;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/**
 * One immutable document write for a checklist item (H14). The checklist row is the current
 * view; this is the history an approval and later review can point at.
 */
@Entity
@Immutable
@Table(name = "loan_application_document_version")
public class LoanApplicationDocumentVersion {

    @Id
    private UUID id;

    @Column(name = "loan_application_id", nullable = false, updatable = false)
    private UUID loanApplicationId;

    @Column(name = "checklist_item_id", nullable = false, updatable = false)
    private UUID checklistItemId;

    @Enumerated(EnumType.STRING)
    @Column(name = "document_type", nullable = false, length = 64, updatable = false)
    private LoanApplicationDocumentType documentType;

    @Column(name = "version_number", nullable = false, updatable = false)
    private int versionNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 16, updatable = false)
    private LoanApplicationDocumentVersionKind kind;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32, updatable = false)
    private LoanApplicationDocumentChecklistStatus status;

    @Column(name = "note", length = 500, updatable = false)
    private String note;

    @Column(name = "file_name", length = 255, updatable = false)
    private String fileName;

    @Column(name = "file_reference", length = 500, updatable = false)
    private String fileReference;

    @Column(name = "source_reference", length = 500, updatable = false)
    private String sourceReference;

    @Column(name = "content_type", length = 128, updatable = false)
    private String contentType;

    @Column(name = "lms_managed_content", nullable = false, updatable = false)
    private boolean lmsManagedContent;

    @Column(name = "storage_key", length = 500, updatable = false)
    private String storageKey;

    @Column(name = "file_checksum", length = 128, updatable = false)
    private String fileChecksum;

    @Column(name = "file_size_bytes", updatable = false)
    private Long fileSizeBytes;

    @Enumerated(EnumType.STRING)
    @Column(name = "application_status", length = 32, updatable = false)
    private LoanApplicationStatus applicationStatus;

    @Column(name = "correction_reason", length = 500, updatable = false)
    private String correctionReason;

    @Column(name = "corrects_evidence_id", updatable = false)
    private UUID correctsEvidenceId;

    @Column(name = "recorded_by_username", length = 128, updatable = false)
    private String recordedByUsername;

    @Column(name = "recorded_at", nullable = false, updatable = false)
    private Instant recordedAt;

    protected LoanApplicationDocumentVersion() {
    }

    /** Snapshot the checklist item exactly as it now stands. */
    public LoanApplicationDocumentVersion(
            LoanApplicationDocumentChecklist item,
            int versionNumber,
            LoanApplicationDocumentVersionKind kind,
            LoanApplicationStatus applicationStatus,
            String correctionReason,
            UUID correctsEvidenceId
    ) {
        this.id = UUID.randomUUID();
        this.loanApplicationId = item.getLoanApplication().getId();
        this.checklistItemId = item.getId();
        this.documentType = item.getDocumentType();
        this.versionNumber = versionNumber;
        this.kind = kind;
        this.status = item.getStatus();
        this.note = item.getNote();
        this.fileName = item.getFileName();
        this.fileReference = item.getFileReference();
        this.sourceReference = item.getSourceReference();
        this.contentType = item.getContentType();
        this.lmsManagedContent = item.isLmsManagedContent();
        this.storageKey = item.getStorageKey();
        this.fileChecksum = item.getFileChecksum();
        this.fileSizeBytes = item.getFileSizeBytes();
        this.applicationStatus = applicationStatus;
        this.correctionReason = correctionReason;
        this.correctsEvidenceId = correctsEvidenceId;
        this.recordedByUsername = item.getUpdatedByUsername();
        this.recordedAt = PersistedTimestamp.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getLoanApplicationId() {
        return loanApplicationId;
    }

    public UUID getChecklistItemId() {
        return checklistItemId;
    }

    public LoanApplicationDocumentType getDocumentType() {
        return documentType;
    }

    public int getVersionNumber() {
        return versionNumber;
    }

    public LoanApplicationDocumentVersionKind getKind() {
        return kind;
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

    public LoanApplicationStatus getApplicationStatus() {
        return applicationStatus;
    }

    public String getCorrectionReason() {
        return correctionReason;
    }

    public UUID getCorrectsEvidenceId() {
        return correctsEvidenceId;
    }

    public String getRecordedByUsername() {
        return recordedByUsername;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }
}
