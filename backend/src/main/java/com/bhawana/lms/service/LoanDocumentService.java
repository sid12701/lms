package com.bhawana.lms.service;

import com.bhawana.lms.domain.LoanApplicationDocumentChecklist;
import com.bhawana.lms.domain.LoanApplicationDocumentChecklistStatus;
import com.bhawana.lms.domain.LoanApplicationDocumentType;
import com.bhawana.lms.repo.LoanApplicationDocumentChecklistRepository;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class LoanDocumentService {

    private final LoanApplicationService loanApplicationService;
    private final LoanApplicationDocumentChecklistRepository loanApplicationDocumentChecklistRepository;
    private final LoanDocumentStorageService loanDocumentStorageService;
    private final LoanApprovalService loanApprovalService;

    public LoanDocumentService(
            LoanApplicationService loanApplicationService,
            LoanApplicationDocumentChecklistRepository loanApplicationDocumentChecklistRepository,
            LoanDocumentStorageService loanDocumentStorageService,
            LoanApprovalService loanApprovalService
    ) {
        this.loanApplicationService = loanApplicationService;
        this.loanApplicationDocumentChecklistRepository = loanApplicationDocumentChecklistRepository;
        this.loanDocumentStorageService = loanDocumentStorageService;
        this.loanApprovalService = loanApprovalService;
    }

    @Transactional(readOnly = true)
    public RetrievedDocumentContent retrieveDocumentContent(UUID applicationId, LoanApplicationDocumentType documentType) {
        LoanApplicationDocumentChecklist checklistItem =
                loanApplicationService.getDocumentChecklistItem(applicationId, documentType);
        if (!checklistItem.isLmsManagedContent() || checklistItem.getStorageKey() == null) {
            throw new IllegalStateException(
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
            throw new IllegalStateException("No documents found in storage for application " + applicationId);
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
        LoanApplicationDocumentChecklist checklistItem = persistStoredDocumentForLsp(
                lspId,
                applicationId,
                documentType,
                actorUsername,
                note,
                sourceReference,
                file
        );
        loanApprovalService.autoApproveIfEligibleForLsp(applicationId, actorUsername);
        return checklistItem;
    }

    public List<LoanApplicationDocumentChecklist> submitStoredDocumentsForLsp(
            UUID lspId,
            UUID applicationId,
            String actorUsername,
            List<BatchDocumentUpload> documents
    ) {
        List<LoanApplicationDocumentChecklist> uploaded = persistStoredDocumentsForLsp(
                lspId,
                applicationId,
                actorUsername,
                documents
        );
        loanApprovalService.autoApproveIfEligibleForLsp(applicationId, actorUsername);
        return uploaded;
    }

    @Transactional
    LoanApplicationDocumentChecklist persistStoredDocumentForLsp(
            UUID lspId,
            UUID applicationId,
            LoanApplicationDocumentType documentType,
            String actorUsername,
            String note,
            String sourceReference,
            MultipartFile file
    ) {
        loanApplicationService.getApplicationForLsp(lspId, applicationId);
        StoredDocument storedDocument = loanDocumentStorageService.store(applicationId, documentType, file);
        return loanApplicationService.updateDocumentChecklistItem(
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
        loanApplicationService.getApplicationForLsp(lspId, applicationId);
        if (documents == null || documents.isEmpty()) {
            throw new IllegalArgumentException("At least one document upload is required.");
        }

        Set<LoanApplicationDocumentType> seenDocumentTypes = new HashSet<>();
        return documents.stream()
                .map(document -> {
                    if (!seenDocumentTypes.add(document.documentType())) {
                        throw new IllegalArgumentException(
                                "Duplicate document type in batch upload: " + document.documentType().name()
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
                    );
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
