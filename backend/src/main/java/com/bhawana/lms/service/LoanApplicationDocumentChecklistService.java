package com.bhawana.lms.service;

import com.bhawana.lms.common.util.Strings;
import com.bhawana.lms.common.api.error.ApiConflictException;
import com.bhawana.lms.common.api.error.BusinessRuleViolationException;
import com.bhawana.lms.common.api.error.DocumentUploadRequiredException;
import com.bhawana.lms.common.api.error.KycCompletionRequiredException;
import com.bhawana.lms.common.api.error.ResourceNotFoundException;
import com.bhawana.lms.domain.LoanApplication;
import com.bhawana.lms.domain.LoanApplicationApprovalEvidence;
import com.bhawana.lms.domain.LoanApplicationDocumentChecklist;
import com.bhawana.lms.domain.LoanApplicationDocumentChecklistStatus;
import com.bhawana.lms.domain.LoanApplicationDocumentType;
import com.bhawana.lms.domain.LoanApplicationDocumentVersion;
import com.bhawana.lms.domain.LoanApplicationDocumentVersionKind;
import com.bhawana.lms.domain.LoanApplicationStatus;
import com.bhawana.lms.domain.LoanEventType;
import com.bhawana.lms.repo.LoanApplicationApprovalEvidenceRepository;
import com.bhawana.lms.repo.LoanApplicationDocumentChecklistRepository;
import com.bhawana.lms.repo.LoanApplicationDocumentVersionRepository;
import com.bhawana.lms.repo.LoanApplicationRepository;
import com.bhawana.lms.repo.LoanDocumentObjectRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LoanApplicationDocumentChecklistService {

    private final LoanApplicationRepository loanApplicationRepository;
    private final LoanApplicationDocumentChecklistRepository loanApplicationDocumentChecklistRepository;
    private final LoanApplicationDocumentVersionRepository documentVersionRepository;
    private final LoanApplicationApprovalEvidenceRepository approvalEvidenceRepository;
    private final LoanDocumentObjectRepository documentObjectRepository;
    private final LoanEventLog loanEventLog;
    private final EntityManager entityManager;

    public LoanApplicationDocumentChecklistService(
            LoanApplicationRepository loanApplicationRepository,
            LoanApplicationDocumentChecklistRepository loanApplicationDocumentChecklistRepository,
            LoanApplicationDocumentVersionRepository documentVersionRepository,
            LoanApplicationApprovalEvidenceRepository approvalEvidenceRepository,
            LoanDocumentObjectRepository documentObjectRepository,
            LoanEventLog loanEventLog,
            EntityManager entityManager
    ) {
        this.loanApplicationRepository = loanApplicationRepository;
        this.loanApplicationDocumentChecklistRepository = loanApplicationDocumentChecklistRepository;
        this.documentVersionRepository = documentVersionRepository;
        this.approvalEvidenceRepository = approvalEvidenceRepository;
        this.documentObjectRepository = documentObjectRepository;
        this.loanEventLog = loanEventLog;
        this.entityManager = entityManager;
    }

    public void seedDocumentChecklist(LoanApplication application, String actorUsername) {
        List<LoanApplicationDocumentChecklist> checklistItems = List.of(
                buildChecklistItem(application, LoanApplicationDocumentType.PAN_CARD, actorUsername),
                buildChecklistItem(application, LoanApplicationDocumentType.AADHAAR_FILE, actorUsername),
                buildChecklistItem(application, LoanApplicationDocumentType.ADDRESS_PROOF, actorUsername),
                buildChecklistItem(application, LoanApplicationDocumentType.INCOME_PROOF, actorUsername),
                buildChecklistItem(application, LoanApplicationDocumentType.BANK_STATEMENT, actorUsername),
                buildChecklistItem(application, LoanApplicationDocumentType.SELFIE_PHOTOGRAPH, actorUsername),
                buildChecklistItem(application, LoanApplicationDocumentType.KFS, actorUsername),
                buildChecklistItem(application, LoanApplicationDocumentType.LOAN_AGREEMENT, actorUsername)
        );
        loanApplicationDocumentChecklistRepository.saveAll(checklistItems);
    }

    public void ensureDocumentChecklist(LoanApplication application) {
        if (!loanApplicationDocumentChecklistRepository
                .findByLoanApplication_IdOrderByCreatedAtAsc(application.getId())
                .isEmpty()) {
            return;
        }
        seedDocumentChecklist(application, "system");
    }

    @Transactional(readOnly = true)
    public boolean hasAllRequiredDocumentsUploaded(UUID applicationId) {
        return loanApplicationDocumentChecklistRepository.findByLoanApplication_IdOrderByCreatedAtAsc(applicationId)
                .stream()
                .filter(item -> LoanApplicationDocumentRequirements.isIntakeRequired(item.getDocumentType()))
                .allMatch(LoanApplicationDocumentRequirements::isChecklistItemComplete);
    }

    @Transactional(readOnly = true)
    public boolean hasAllRequiredLmsManagedDocuments(UUID applicationId) {
        LoanApplication application = getApplication(applicationId);
        ensureDocumentChecklist(application);
        return loanApplicationDocumentChecklistRepository.findByLoanApplication_IdOrderByCreatedAtAsc(applicationId)
                .stream()
                .filter(item -> LoanApplicationDocumentRequirements.isIntakeRequired(item.getDocumentType()))
                .allMatch(LoanApplicationDocumentRequirements::isChecklistItemCompleteForDisbursement);
    }

    @Transactional(readOnly = true)
    public void validateRequiredDocumentsUploadedBeforeDisbursement(UUID applicationId) {
        LoanApplication application = getApplication(applicationId);
        ensureDocumentChecklist(application);

        List<LoanApplicationDocumentType> blockingDocumentTypes = loanApplicationDocumentChecklistRepository
                .findByLoanApplication_IdOrderByCreatedAtAsc(applicationId)
                .stream()
                .filter(item -> item.getDocumentType().isRequiredForDisbursement())
                .filter(item -> item.getStatus() != LoanApplicationDocumentChecklistStatus.SUBMITTED
                        && item.getStatus() != LoanApplicationDocumentChecklistStatus.NOT_REQUIRED)
                .map(LoanApplicationDocumentChecklist::getDocumentType)
                .toList();

        if (!blockingDocumentTypes.isEmpty()) {
            throw new DocumentUploadRequiredException(blockingDocumentTypes);
        }
    }

    @Transactional(readOnly = true)
    public void validateKycCompletionBeforeApproval(UUID applicationId) {
        LoanApplication application = getApplication(applicationId);
        ensureDocumentChecklist(application);

        List<LoanApplicationDocumentType> blockingDocumentTypes = loanApplicationDocumentChecklistRepository
                .findByLoanApplication_IdOrderByCreatedAtAsc(applicationId)
                .stream()
                .filter(item -> LoanApplicationDocumentRequirements.isIntakeRequired(item.getDocumentType()))
                .filter(item -> !LoanApplicationDocumentRequirements.isChecklistItemComplete(item))
                .map(LoanApplicationDocumentChecklist::getDocumentType)
                .toList();

        if (!blockingDocumentTypes.isEmpty()) {
            throw new KycCompletionRequiredException(blockingDocumentTypes);
        }
    }

    /**
     * Single-item primitive for internal callers (demo seeding). It takes the same
     * document-write lock and appends a version, but applies no LSP upload policy.
     */
    @Transactional
    public DocumentChecklistUpdateResult updateDocumentChecklistItem(
            UUID applicationId,
            LoanApplicationDocumentType documentType,
            String actorUsername,
            LoanApplicationDocumentChecklistStatus status,
            String note,
            String fileName,
            String fileReference,
            String sourceReference,
            String contentType,
            Long fileSizeBytes,
            String fileChecksum,
            String storageKey,
            boolean lmsManagedContent
    ) {
        LoanApplication application = lockApplicationForDocumentWrite(applicationId);
        ensureDocumentChecklist(application);
        boolean wasComplete = hasAllRequiredDocumentsUploaded(applicationId);
        LoanApplicationDocumentChecklist checklistItem = requireChecklistItem(applicationId, documentType);
        writeVersionedItem(
                application,
                checklistItem,
                status,
                new DocumentSubmission(
                        documentType, note, fileName, fileReference, sourceReference, contentType,
                        fileSizeBytes, fileChecksum, storageKey, lmsManagedContent
                ),
                actorUsername,
                LoanApplicationDocumentVersionKind.SUBMISSION,
                null,
                null
        );
        boolean allRequiredDocumentsJustCompleted = appendDocumentsUploadedOnCompletion(application, wasComplete);
        return new DocumentChecklistUpdateResult(checklistItem, allRequiredDocumentsJustCompleted);
    }

    /**
     * Upload policy check run before any object is written (H14, M04). It reads without locks,
     * so {@link #recordSubmissions} repeats it under the document-write lock.
     */
    @Transactional(readOnly = true)
    public void checkSubmissionsAllowed(
            UUID applicationId,
            List<DocumentSubmission> submissions,
            String correctionReason
    ) {
        LoanApplication application = getApplication(applicationId);
        ensureDocumentChecklist(application);
        String normalizedReason = normalizeCorrectionReason(correctionReason);
        for (DocumentSubmission submission : submissions) {
            LoanApplicationDocumentChecklist current = requireChecklistItem(applicationId, submission.documentType());
            if (!isUnchanged(current, submission)) {
                enforceSubmissionAllowed(application.getStatus(), current, submission, normalizedReason);
            }
        }
    }

    /**
     * Record a set of document submissions atomically (H13, H14, M04).
     *
     * <p>Takes the document-write lock (borrower, then application — the shared loan-command
     * order), so checklist completion is decided by exactly one transaction at a time and a
     * concurrent approval either sees all of these writes or none. Each changed item gets an
     * immutable version; an item already showing exactly this submission is left as it is, so
     * a retried upload converges instead of adding a version or tripping the evidence lock.
     * Stored objects are linked in the same transaction. DOCUMENTS_UPLOADED is appended once,
     * on the incomplete-to-complete edge.</p>
     */
    @Transactional
    public DocumentSubmissionResult recordSubmissions(
            UUID applicationId,
            String actorUsername,
            List<DocumentSubmission> submissions,
            String correctionReason
    ) {
        LoanApplication application = lockApplicationForDocumentWrite(applicationId);
        ensureDocumentChecklist(application);
        String normalizedReason = normalizeCorrectionReason(correctionReason);
        boolean wasComplete = hasAllRequiredDocumentsUploaded(applicationId);

        List<LoanApplicationDocumentChecklist> recorded = new ArrayList<>(submissions.size());
        for (DocumentSubmission submission : submissions) {
            LoanApplicationDocumentChecklist current = requireChecklistItem(applicationId, submission.documentType());
            if (isUnchanged(current, submission)) {
                recorded.add(current);
                continue;
            }
            enforceSubmissionAllowed(application.getStatus(), current, submission, normalizedReason);
            boolean correction = normalizedReason != null;
            writeVersionedItem(
                    application,
                    current,
                    LoanApplicationDocumentChecklistStatus.SUBMITTED,
                    submission,
                    actorUsername,
                    correction ? LoanApplicationDocumentVersionKind.CORRECTION : LoanApplicationDocumentVersionKind.SUBMISSION,
                    normalizedReason,
                    correction ? currentApprovalEvidenceId(applicationId, submission.documentType()) : null
            );
            if (submission.lmsManagedContent() && submission.storageKey() != null
                    && documentObjectRepository.markLinked(submission.storageKey()) != 1) {
                // The orphan reconciler claimed the object (its grace period expired before this
                // metadata committed). Fail the whole write; a retry re-stores the object.
                throw new ApiConflictException(
                        "DOCUMENT_STORAGE_RETRY_REQUIRED",
                        "The uploaded document object is no longer available. Retry the upload.",
                        1
                );
            }
            recorded.add(current);
        }
        boolean allRequiredDocumentsJustCompleted = appendDocumentsUploadedOnCompletion(application, wasComplete);
        return new DocumentSubmissionResult(List.copyOf(recorded), allRequiredDocumentsJustCompleted);
    }

    /**
     * Freeze the document versions an approval commits against (H14). Called by every
     * approval path in the approval transaction, which already holds the borrower lock that
     * every document write takes first — so no upload can interleave with the capture.
     */
    @Transactional
    public void captureApprovalEvidence(UUID applicationId, String approvedByUsername) {
        UUID approvalId = UUID.randomUUID();
        Instant approvedAt = Instant.now();
        String actor = Strings.normalizeActor(approvedByUsername);
        for (LoanApplicationDocumentChecklist item
                : loanApplicationDocumentChecklistRepository.findByLoanApplication_IdOrderByCreatedAtAsc(applicationId)) {
            approvalEvidenceRepository.save(new LoanApplicationApprovalEvidence(approvalId, item, actor, approvedAt));
        }
    }

    /**
     * Which submissions each lifecycle state accepts (H14).
     *
     * <ul>
     *   <li>Before approval, and after a rejection (ops may reopen it; #135): any submission.
     *       Auto-approval still runs only from INITIALIZED / AWAITING_APPROVAL.</li>
     *   <li>Approved, not yet disbursed: approved evidence is frozen. An ordinary upload may
     *       only supply the LMS-held copy of a document that was approved as an external
     *       reference (disbursement requires LMS-held copies); anything else needs an explicit
     *       correction, which is recorded with its reason and the approval it corrects.</li>
     *   <li>Invalid, disbursed or later: no document writes.</li>
     * </ul>
     */
    static void enforceSubmissionAllowed(
            LoanApplicationStatus status,
            LoanApplicationDocumentChecklist current,
            DocumentSubmission submission,
            String correctionReason
    ) {
        switch (status) {
            case INITIALIZED, AWAITING_APPROVAL, REJECTED -> {
                if (correctionReason != null) {
                    throw new BusinessRuleViolationException(
                            "DOCUMENT_CORRECTION_NOT_APPLICABLE",
                            "Documents can be replaced directly until the application is approved.",
                            Map.of("correctionReason", "Only allowed after approval.")
                    );
                }
            }
            case APPROVED_PENDING_DISBURSAL, DISBURSEMENT_RETRY -> {
                if (correctionReason != null) {
                    if (!submission.lmsManagedContent()) {
                        throw new BusinessRuleViolationException(
                                "DOCUMENT_CORRECTION_REQUIRES_FILE",
                                "A correction must upload the corrected document into LMS-managed storage.",
                                Map.of("documentType", submission.documentType().name())
                        );
                    }
                    return;
                }
                if (submission.lmsManagedContent() && !current.isLmsManagedContent()) {
                    return;
                }
                throw new ApiConflictException(
                        "DOCUMENT_EVIDENCE_LOCKED",
                        "The application is approved; " + submission.documentType().name()
                                + " is approved evidence and can only be replaced by an explicit correction."
                );
            }
            default -> throw new ApiConflictException(
                    "DOCUMENT_UPLOAD_NOT_ALLOWED",
                    "Documents cannot be submitted while the application is " + status.name() + "."
            );
        }
    }

    private LoanApplication lockApplicationForDocumentWrite(UUID applicationId) {
        loanApplicationRepository.findBorrowerByApplicationIdForUpdate(applicationId)
                .orElseThrow(() -> new ResourceNotFoundException("Unknown loan application id: " + applicationId));
        LoanApplication application = loanApplicationRepository.findByIdForUpdate(applicationId)
                .orElseThrow(() -> new ResourceNotFoundException("Unknown loan application id: " + applicationId));
        // The caller may have read the application before the lock wait; decide on current state.
        entityManager.refresh(application);
        return application;
    }

    private void writeVersionedItem(
            LoanApplication application,
            LoanApplicationDocumentChecklist checklistItem,
            LoanApplicationDocumentChecklistStatus status,
            DocumentSubmission submission,
            String actorUsername,
            LoanApplicationDocumentVersionKind kind,
            String correctionReason,
            UUID correctsEvidenceId
    ) {
        checklistItem.update(
                status,
                submission.note(),
                Strings.normalizeActor(actorUsername),
                submission.fileName(),
                submission.fileReference(),
                submission.sourceReference(),
                submission.contentType(),
                submission.fileSizeBytes(),
                submission.fileChecksum(),
                submission.storageKey(),
                submission.lmsManagedContent()
        );
        int versionNumber = documentVersionRepository.findMaxVersionNumber(
                application.getId(),
                checklistItem.getDocumentType()
        ) + 1;
        LoanApplicationDocumentVersion version = documentVersionRepository.save(new LoanApplicationDocumentVersion(
                checklistItem,
                versionNumber,
                kind,
                application.getStatus(),
                correctionReason,
                correctsEvidenceId
        ));
        checklistItem.pointAtVersion(version.getId());
        loanApplicationDocumentChecklistRepository.save(checklistItem);
    }

    private boolean appendDocumentsUploadedOnCompletion(LoanApplication application, boolean wasComplete) {
        if (wasComplete || !hasAllRequiredDocumentsUploaded(application.getId())) {
            return false;
        }
        List<String> completedTypes = loanApplicationDocumentChecklistRepository
                .findByLoanApplication_IdOrderByCreatedAtAsc(application.getId())
                .stream()
                .filter(item -> LoanApplicationDocumentRequirements.isIntakeRequired(item.getDocumentType()))
                .filter(LoanApplicationDocumentRequirements::isChecklistItemComplete)
                .map(item -> item.getDocumentType().name())
                .toList();
        loanEventLog.append(
                application.getLsp(),
                LoanEventType.DOCUMENTS_UPLOADED,
                "LOAN_APPLICATION",
                application.getId().toString(),
                application.getId(),
                LoanEventPayloads.documentsUploaded(application, completedTypes)
        );
        return true;
    }

    /** True when the item already shows exactly this submission, so re-recording it is a no-op. */
    private static boolean isUnchanged(LoanApplicationDocumentChecklist current, DocumentSubmission submission) {
        if (current.getStatus() != LoanApplicationDocumentChecklistStatus.SUBMITTED
                || current.isLmsManagedContent() != submission.lmsManagedContent()
                || !Objects.equals(current.getNote(), Strings.normalizeOptional(submission.note()))
                || !Objects.equals(current.getSourceReference(), Strings.normalizeOptional(submission.sourceReference()))) {
            return false;
        }
        if (submission.lmsManagedContent()) {
            // The content-addressed key already covers the bytes and the file name.
            return Objects.equals(current.getStorageKey(), Strings.normalizeOptional(submission.storageKey()));
        }
        return Objects.equals(current.getFileName(), Strings.normalizeOptional(submission.fileName()))
                && Objects.equals(current.getFileReference(), Strings.normalizeOptional(submission.fileReference()))
                && Objects.equals(current.getContentType(), Strings.normalizeOptional(submission.contentType()));
    }

    private UUID currentApprovalEvidenceId(UUID applicationId, LoanApplicationDocumentType documentType) {
        List<LoanApplicationApprovalEvidence> evidence =
                approvalEvidenceRepository.findByLoanApplicationIdOrderByApprovedAtDesc(applicationId);
        if (evidence.isEmpty()) {
            return null; // approved before V131: no captured evidence to reference
        }
        UUID latestApprovalId = evidence.get(0).getApprovalId();
        return evidence.stream()
                .filter(row -> row.getApprovalId().equals(latestApprovalId))
                .filter(row -> row.getDocumentType() == documentType)
                .map(LoanApplicationApprovalEvidence::getId)
                .findFirst()
                .orElse(null);
    }

    private static String normalizeCorrectionReason(String correctionReason) {
        String normalized = Strings.normalizeOptional(correctionReason);
        if (normalized != null && normalized.length() > 500) {
            throw new BusinessRuleViolationException(
                    "DOCUMENT_CORRECTION_REASON_TOO_LONG",
                    "Correction reason must be at most 500 characters.",
                    Map.of("correctionReason", "At most 500 characters.")
            );
        }
        return normalized;
    }

    private LoanApplicationDocumentChecklist requireChecklistItem(
            UUID applicationId,
            LoanApplicationDocumentType documentType
    ) {
        return loanApplicationDocumentChecklistRepository
                .findByLoanApplication_IdAndDocumentType(applicationId, documentType)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Unknown document checklist item: " + documentType.name()
                ));
    }

    private LoanApplication getApplication(UUID applicationId) {
        return loanApplicationRepository.findDetailedById(applicationId)
                .orElseThrow(() -> new ResourceNotFoundException("Unknown loan application id: " + applicationId));
    }

    private static LoanApplicationDocumentChecklist buildChecklistItem(
            LoanApplication application,
            LoanApplicationDocumentType documentType,
            String actorUsername
    ) {
        return new LoanApplicationDocumentChecklist(
                application,
                documentType,
                documentType.isRequiredByDefault(),
                documentType.isRequiredByDefault()
                        ? LoanApplicationDocumentChecklistStatus.PENDING
                        : LoanApplicationDocumentChecklistStatus.NOT_REQUIRED,
                documentType.isRequiredByDefault() ? "Awaiting " + documentType.getDisplayName() : "Optional placeholder",
                actorUsername
        );
    }
}
