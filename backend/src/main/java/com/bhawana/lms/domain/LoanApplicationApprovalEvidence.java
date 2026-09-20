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
 * The document version one checklist item showed when an approval committed (H14). All rows
 * written by one approval share {@code approvalId}; later uploads never rewrite them.
 */
@Entity
@Immutable
@Table(name = "loan_application_approval_evidence")
public class LoanApplicationApprovalEvidence {

    @Id
    private UUID id;

    @Column(name = "approval_id", nullable = false, updatable = false)
    private UUID approvalId;

    @Column(name = "loan_application_id", nullable = false, updatable = false)
    private UUID loanApplicationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "document_type", nullable = false, length = 64, updatable = false)
    private LoanApplicationDocumentType documentType;

    @Enumerated(EnumType.STRING)
    @Column(name = "checklist_status", nullable = false, length = 32, updatable = false)
    private LoanApplicationDocumentChecklistStatus checklistStatus;

    @Column(name = "document_version_id", updatable = false)
    private UUID documentVersionId;

    @Column(name = "file_checksum", length = 128, updatable = false)
    private String fileChecksum;

    @Column(name = "storage_key", length = 500, updatable = false)
    private String storageKey;

    @Column(name = "lms_managed_content", nullable = false, updatable = false)
    private boolean lmsManagedContent;

    @Column(name = "approved_by_username", length = 128, updatable = false)
    private String approvedByUsername;

    @Column(name = "approved_at", nullable = false, updatable = false)
    private Instant approvedAt;

    protected LoanApplicationApprovalEvidence() {
    }

    public LoanApplicationApprovalEvidence(
            UUID approvalId,
            LoanApplicationDocumentChecklist item,
            String approvedByUsername,
            Instant approvedAt
    ) {
        this.id = UUID.randomUUID();
        this.approvalId = approvalId;
        this.loanApplicationId = item.getLoanApplication().getId();
        this.documentType = item.getDocumentType();
        this.checklistStatus = item.getStatus();
        this.documentVersionId = item.getCurrentVersionId();
        this.fileChecksum = item.getFileChecksum();
        this.storageKey = item.getStorageKey();
        this.lmsManagedContent = item.isLmsManagedContent();
        this.approvedByUsername = approvedByUsername;
        this.approvedAt = PersistedTimestamp.normalize(approvedAt);
    }

    public UUID getId() {
        return id;
    }

    public UUID getApprovalId() {
        return approvalId;
    }

    public UUID getLoanApplicationId() {
        return loanApplicationId;
    }

    public LoanApplicationDocumentType getDocumentType() {
        return documentType;
    }

    public LoanApplicationDocumentChecklistStatus getChecklistStatus() {
        return checklistStatus;
    }

    public UUID getDocumentVersionId() {
        return documentVersionId;
    }

    public String getFileChecksum() {
        return fileChecksum;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public boolean isLmsManagedContent() {
        return lmsManagedContent;
    }

    public String getApprovedByUsername() {
        return approvedByUsername;
    }

    public Instant getApprovedAt() {
        return approvedAt;
    }
}
