package com.bhawana.lms.service;

import com.bhawana.lms.common.util.Strings;
import com.bhawana.lms.common.api.error.DocumentUploadRequiredException;
import com.bhawana.lms.common.api.error.KycCompletionRequiredException;
import com.bhawana.lms.common.api.error.ResourceNotFoundException;
import com.bhawana.lms.domain.LoanApplication;
import com.bhawana.lms.domain.LoanApplicationDocumentChecklist;
import com.bhawana.lms.domain.LoanApplicationDocumentChecklistStatus;
import com.bhawana.lms.domain.LoanApplicationDocumentType;
import com.bhawana.lms.domain.LoanEventType;
import com.bhawana.lms.repo.LoanApplicationDocumentChecklistRepository;
import com.bhawana.lms.repo.LoanApplicationRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LoanApplicationDocumentChecklistService {

    private final LoanApplicationRepository loanApplicationRepository;
    private final LoanApplicationDocumentChecklistRepository loanApplicationDocumentChecklistRepository;
    private final LoanEventLog loanEventLog;

    public LoanApplicationDocumentChecklistService(
            LoanApplicationRepository loanApplicationRepository,
            LoanApplicationDocumentChecklistRepository loanApplicationDocumentChecklistRepository,
            LoanEventLog loanEventLog
    ) {
        this.loanApplicationRepository = loanApplicationRepository;
        this.loanApplicationDocumentChecklistRepository = loanApplicationDocumentChecklistRepository;
        this.loanEventLog = loanEventLog;
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
        LoanApplication application = getApplication(applicationId);
        ensureDocumentChecklist(application);

        LoanApplicationDocumentChecklist checklistItem = loanApplicationDocumentChecklistRepository
                .findByLoanApplication_IdAndDocumentType(applicationId, documentType)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Unknown document checklist item: " + documentType.name()
                ));
        boolean wasComplete = hasAllRequiredDocumentsUploaded(applicationId);

        checklistItem.update(
                status,
                note,
                Strings.normalizeActor(actorUsername),
                fileName,
                fileReference,
                sourceReference,
                contentType,
                fileSizeBytes,
                fileChecksum,
                storageKey,
                lmsManagedContent
        );
        LoanApplicationDocumentChecklist savedChecklistItem = loanApplicationDocumentChecklistRepository.save(checklistItem);
        boolean isComplete = hasAllRequiredDocumentsUploaded(applicationId);
        boolean allRequiredDocumentsJustCompleted = !wasComplete && isComplete;
        if (allRequiredDocumentsJustCompleted) {
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
        }
        return new DocumentChecklistUpdateResult(savedChecklistItem, allRequiredDocumentsJustCompleted);
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
