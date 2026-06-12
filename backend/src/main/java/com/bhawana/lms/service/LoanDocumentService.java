package com.bhawana.lms.service;

import com.bhawana.lms.common.web.BusinessRuleViolationException;
import com.bhawana.lms.common.web.DocumentNotFoundException;
import com.bhawana.lms.domain.LoanApplicationDocumentChecklist;
import com.bhawana.lms.domain.LoanApplicationDocumentChecklistStatus;
import com.bhawana.lms.domain.LoanApplicationDocumentType;
import com.bhawana.lms.repo.LoanApplicationDocumentChecklistRepository;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class LoanDocumentService {

    private final LoanApplicationQueryService loanApplicationQueryService;
    private final LoanApplicationLifecycleService loanApplicationLifecycleService;
    private final LoanApplicationService loanApplicationService;
    private final LoanApplicationDocumentChecklistRepository loanApplicationDocumentChecklistRepository;
    private final LoanDocumentStorageService loanDocumentStorageService;
    private final LoanAutoApprovalGateService loanAutoApprovalGateService;

    public LoanDocumentService(
            LoanApplicationQueryService loanApplicationQueryService,
            LoanApplicationLifecycleService loanApplicationLifecycleService,
            LoanApplicationService loanApplicationService,
            LoanApplicationDocumentChecklistRepository loanApplicationDocumentChecklistRepository,
            LoanDocumentStorageService loanDocumentStorageService,
            LoanAutoApprovalGateService loanAutoApprovalGateService
    ) {
        this.loanApplicationQueryService = loanApplicationQueryService;
        this.loanApplicationLifecycleService = loanApplicationLifecycleService;
        this.loanApplicationService = loanApplicationService;
        this.loanApplicationDocumentChecklistRepository = loanApplicationDocumentChecklistRepository;
        this.loanDocumentStorageService = loanDocumentStorageService;
        this.loanAutoApprovalGateService = loanAutoApprovalGateService;
    }

    @Transactional(readOnly = true)
    public List<LoanApplicationDocumentChecklist> listSubmittedDocumentsForLsp(UUID lspId, UUID applicationId) {
        loanApplicationQueryService.getApplicationForLsp(lspId, applicationId);
        return loanApplicationDocumentChecklistRepository
                .findByLoanApplication_IdOrderByCreatedAtAsc(applicationId)
                .stream()
                .filter(item -> item.getStatus() != LoanApplicationDocumentChecklistStatus.PENDING
                        && item.getStatus() != LoanApplicationDocumentChecklistStatus.NOT_REQUIRED)
                .toList();
    }

    @Transactional
    public LoanApplicationDocumentChecklist submitDocumentMetadataForLsp(
            UUID lspId,
            UUID applicationId,
            LoanApplicationDocumentType documentType,
            String actorUsername,
            String note,
            String fileName,
            String fileReference,
            String sourceReference,
            String contentType
    ) {
        loanApplicationQueryService.getApplicationForLsp(lspId, applicationId);
        DocumentChecklistUpdateResult outcome = loanApplicationLifecycleService.updateDocumentChecklistItem(
                applicationId,
                documentType,
                actorUsername,
                LoanApplicationDocumentChecklistStatus.SUBMITTED,
                note,
                fileName,
                fileReference,
                sourceReference,
                contentType,
                null,
                null,
                null,
                false
        );
        loanAutoApprovalGateService.maybeTriggerAutoApproval(
                applicationId,
                actorUsername,
                outcome.allRequiredDocumentsJustCompleted()
        );
        return outcome.checklistItem();
    }

    @Transactional(readOnly = true)
    public RetrievedDocumentContent retrieveDocumentContent(UUID applicationId, LoanApplicationDocumentType documentType) {
        LoanApplicationDocumentChecklist checklistItem =
                loanApplicationService.getDocumentChecklistItem(applicationId, documentType);
        if (!checklistItem.isLmsManagedContent() || checklistItem.getStorageKey() == null) {
            throw new DocumentNotFoundException(
                    "Document content is not LMS-managed or has no storage key: " + documentType.name()
            );
        }
        byte[] content = loanDocumentStorageService.retrieve(checklistItem.getStorageKey());
        return new RetrievedDocumentContent(
                checklistItem.getFileName() != null ? checklistItem.getFileName() : "document.bin",
                checklistItem.getContentType() != null ? checklistItem.getContentType() : "application/octet-stream",
                content
        );
    }

    public record RetrievedDocumentContent(String fileName, String contentType, byte[] content) {
    }

    public ZipBuildResult buildDocumentZip(UUID applicationId) {
        loanApplicationService.getApplication(applicationId);
        List<LoanApplicationDocumentChecklist> downloadableDocuments = loanApplicationDocumentChecklistRepository
                .findByLoanApplication_IdOrderByCreatedAtAsc(applicationId)
                .stream()
                .filter(checklistItem -> checklistItem.isLmsManagedContent() && checklistItem.getStorageKey() != null)
                .toList();
        if (downloadableDocuments.isEmpty()) {
            throw new DocumentNotFoundException("No documents found in storage for application " + applicationId);
        }
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ZipOutputStream zos = new ZipOutputStream(baos)) {
            Set<String> zipEntryNames = new HashSet<>();
            List<LoanApplicationDocumentType> includedTypes = new java.util.ArrayList<>();
            for (LoanApplicationDocumentChecklist checklistItem : downloadableDocuments) {
                includedTypes.add(checklistItem.getDocumentType());
                String entryName = resolveZipEntryName(checklistItem, zipEntryNames);
                byte[] content = loanDocumentStorageService.retrieve(checklistItem.getStorageKey());
                zos.putNextEntry(new ZipEntry(entryName));
                zos.write(content);
                zos.closeEntry();
            }
            zos.finish();
            return new ZipBuildResult(baos.toByteArray(), includedTypes);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to build ZIP archive for application " + applicationId, exception);
        }
    }

    public record ZipBuildResult(byte[] content, List<LoanApplicationDocumentType> includedTypes) {
    }

    private static String resolveZipEntryName(
            LoanApplicationDocumentChecklist checklistItem,
            Set<String> usedEntryNames
    ) {
        String baseName = checklistItem.getFileName() == null || checklistItem.getFileName().isBlank()
                ? "document.bin"
                : checklistItem.getFileName().trim();
        String folderName = checklistItem.getDocumentType().name().toLowerCase();
        String candidate = folderName + "/" + baseName;
        if (usedEntryNames.add(candidate)) {
            return candidate;
        }

        int counter = 2;
        while (!usedEntryNames.add(folderName + "/" + counter + "-" + baseName)) {
            counter++;
        }
        return folderName + "/" + counter + "-" + baseName;
    }

    public LoanApplicationDocumentChecklist submitStoredDocumentForLsp(
            UUID lspId,
            UUID applicationId,
            LoanApplicationDocumentType documentType,
            String actorUsername,
            String note,
            String sourceReference,
            MultipartFile file
    ) {
        DocumentChecklistUpdateResult outcome = persistStoredDocumentForLsp(
                lspId,
                applicationId,
                documentType,
                actorUsername,
                note,
                sourceReference,
                file
        );
        loanAutoApprovalGateService.maybeTriggerAutoApproval(
                applicationId,
                actorUsername,
                outcome.allRequiredDocumentsJustCompleted()
        );
        return outcome.checklistItem();
    }

    public List<LoanApplicationDocumentChecklist> submitStoredDocumentsForLsp(
            UUID lspId,
            UUID applicationId,
            String actorUsername,
            List<BatchDocumentUpload> documents
    ) {
        boolean wasComplete = loanApplicationService.hasAllRequiredDocumentsUploaded(applicationId);
        List<LoanApplicationDocumentChecklist> uploaded = persistStoredDocumentsForLsp(
                lspId,
                applicationId,
                actorUsername,
                documents
        );
        loanAutoApprovalGateService.maybeTriggerAutoApproval(
                applicationId,
                actorUsername,
                !wasComplete && loanApplicationService.hasAllRequiredDocumentsUploaded(applicationId)
        );
        return uploaded;
    }

    @Transactional
    DocumentChecklistUpdateResult persistStoredDocumentForLsp(
            UUID lspId,
            UUID applicationId,
            LoanApplicationDocumentType documentType,
            String actorUsername,
            String note,
            String sourceReference,
            MultipartFile file
    ) {
        loanApplicationQueryService.getApplicationForLsp(lspId, applicationId);
        StoredDocument storedDocument = loanDocumentStorageService.store(applicationId, documentType, file);
        return loanApplicationLifecycleService.updateDocumentChecklistItem(
                applicationId,
                documentType,
                actorUsername,
                LoanApplicationDocumentChecklistStatus.SUBMITTED,
                note,
                storedDocument.fileName(),
                storedDocument.canonicalUri(),
                sourceReference,
                storedDocument.contentType(),
                storedDocument.fileSizeBytes(),
                storedDocument.fileChecksum(),
                storedDocument.storageKey(),
                true
        );
    }

    @Transactional
    List<LoanApplicationDocumentChecklist> persistStoredDocumentsForLsp(
            UUID lspId,
            UUID applicationId,
            String actorUsername,
            List<BatchDocumentUpload> documents
    ) {
        loanApplicationQueryService.getApplicationForLsp(lspId, applicationId);
        if (documents == null || documents.isEmpty()) {
            throw new IllegalArgumentException("At least one document upload is required.");
        }

        Set<LoanApplicationDocumentType> seenDocumentTypes = new HashSet<>();
        return documents.stream()
                .map(document -> {
                    if (!seenDocumentTypes.add(document.documentType())) {
                        Map<String, String> fieldErrors = new LinkedHashMap<>();
                        fieldErrors.put(
                                "documentType",
                                "Duplicate document type in batch upload: " + document.documentType().name()
                        );
                        throw new BusinessRuleViolationException(
                                "DUPLICATE_DOCUMENT_TYPE",
                                "Each document type may appear only once in a batch upload.",
                                fieldErrors
                        );
                    }
                    return persistStoredDocumentForLsp(
                            lspId,
                            applicationId,
                            document.documentType(),
                            actorUsername,
                            document.note(),
                            document.sourceReference(),
                            document.file()
                    ).checklistItem();
                })
                .toList();
    }

    public record BatchDocumentUpload(
            LoanApplicationDocumentType documentType,
            String note,
            String sourceReference,
            MultipartFile file
    ) {
    }
}
