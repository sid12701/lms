package com.bhawana.lms.service;

import com.bhawana.lms.common.api.error.ApiConflictException;
import com.bhawana.lms.common.api.error.BusinessRuleViolationException;
import com.bhawana.lms.common.api.error.DocumentNotFoundException;
import com.bhawana.lms.common.api.error.UnsupportedDocumentPreviewException;
import com.bhawana.lms.domain.LoanApplicationDocumentChecklist;
import com.bhawana.lms.domain.LoanApplicationDocumentChecklistStatus;
import com.bhawana.lms.domain.LoanApplicationDocumentType;
import com.bhawana.lms.domain.LoanDocumentObject;
import com.bhawana.lms.repo.LoanApplicationDocumentChecklistRepository;
import com.bhawana.lms.repo.LoanDocumentObjectRepository;
import com.bhawana.lms.service.LoanDocumentStorageService.PreparedDocument;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

@Service
public class LoanDocumentService {

    private final LoanApplicationQueryService loanApplicationQueryService;
    private final LoanApplicationDocumentChecklistService documentChecklistService;
    private final LoanApplicationDocumentChecklistRepository loanApplicationDocumentChecklistRepository;
    private final LoanDocumentStorageService loanDocumentStorageService;
    private final LoanAutoApprovalGateService loanAutoApprovalGateService;
    private final LoanDocumentObjectRepository documentObjectRepository;
    private final TransactionTemplate requiresNewTransactionTemplate;

    public LoanDocumentService(
            LoanApplicationQueryService loanApplicationQueryService,
            LoanApplicationDocumentChecklistService documentChecklistService,
            LoanApplicationDocumentChecklistRepository loanApplicationDocumentChecklistRepository,
            LoanDocumentStorageService loanDocumentStorageService,
            LoanAutoApprovalGateService loanAutoApprovalGateService,
            LoanDocumentObjectRepository documentObjectRepository,
            PlatformTransactionManager transactionManager
    ) {
        this.loanApplicationQueryService = loanApplicationQueryService;
        this.documentChecklistService = documentChecklistService;
        this.loanApplicationDocumentChecklistRepository = loanApplicationDocumentChecklistRepository;
        this.loanDocumentStorageService = loanDocumentStorageService;
        this.loanAutoApprovalGateService = loanAutoApprovalGateService;
        this.documentObjectRepository = documentObjectRepository;
        this.requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
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
        return loanAutoApprovalGateService.commitDocumentSubmissions(
                applicationId,
                actorUsername,
                List.of(DocumentSubmission.metadata(
                        documentType,
                        note,
                        fileName,
                        fileReference,
                        sourceReference,
                        contentType
                )),
                null
        ).checklistItems().get(0);
    }

    /**
     * Open a streaming handle to a single stored document for download or inline
     * preview, without buffering the whole file into heap.
     *
     * <p>The checklist metadata is read first (a short, self-contained query that
     * does not hold a connection across the storage round-trip), the inline-preview
     * allowlist is enforced before any bytes are fetched, and only then is the
     * storage object opened. Existence/availability failures surface eagerly from
     * {@code openStream} so the caller can map them to a clean 4xx/5xx before the
     * response body is committed. The returned stream must be closed by the caller.
     */
    public StreamedDocumentContent openDocumentStream(
            UUID applicationId,
            LoanApplicationDocumentType documentType,
            boolean inlinePreview
    ) {
        RetrievableDocumentRef ref = resolveRetrievableDocumentRef(applicationId, documentType);
        if (inlinePreview && !DocumentPreviewSupport.isInlinePreviewable(ref.contentType())) {
            throw new UnsupportedDocumentPreviewException(
                    "Document type " + documentType.name() + " has content type "
                            + ref.contentType() + " which is not supported for inline preview."
            );
        }
        LoanDocumentStorageService.RetrievedDocumentStream stream =
                loanDocumentStorageService.openStream(ref.storageKey());
        return new StreamedDocumentContent(
                ref.fileName(),
                ref.contentType(),
                stream.content(),
                stream.contentLength()
        );
    }

    // Intentionally not @Transactional: each repository read runs in its own short
    // transaction and releases its connection immediately, so no pooled connection
    // is held across the (potentially slow) object-storage round-trip in
    // openDocumentStream. Tenant/RLS scope is re-applied on every connection
    // checkout from the thread-local context, so it is unaffected by the split.
    private RetrievableDocumentRef resolveRetrievableDocumentRef(
            UUID applicationId,
            LoanApplicationDocumentType documentType
    ) {
        LoanApplicationDocumentChecklist checklistItem =
                getDocumentChecklistItem(applicationId, documentType);
        if (!checklistItem.isLmsManagedContent() || checklistItem.getStorageKey() == null) {
            throw new DocumentNotFoundException(
                    "Document content is not LMS-managed or has no storage key: " + documentType.name()
            );
        }
        return new RetrievableDocumentRef(
                checklistItem.getStorageKey(),
                checklistItem.getFileName() != null ? checklistItem.getFileName() : "document.bin",
                checklistItem.getContentType() != null ? checklistItem.getContentType() : "application/octet-stream"
        );
    }

    /** Resolved storage coordinates for a single retrievable document. */
    private record RetrievableDocumentRef(String storageKey, String fileName, String contentType) {
    }

    /** A lazily-streamed single document body with its display metadata and length. */
    public record StreamedDocumentContent(
            String fileName,
            String contentType,
            java.io.InputStream content,
            long contentLength
    ) implements java.io.Closeable {

        @Override
        public void close() throws java.io.IOException {
            content.close();
        }
    }

    public ZipBuildResult buildDocumentZip(UUID applicationId) {
        loanApplicationQueryService.getApplication(applicationId);
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
        return submitStoredDocumentForLsp(
                lspId, applicationId, documentType, actorUsername, note, sourceReference, null, file);
    }

    /**
     * Upload one document. With a {@code correctionReason} this is an explicit correction of
     * approved evidence (H14): it is only accepted after approval, and the replaced version and
     * the approval evidence that referenced it stay on record.
     */
    public LoanApplicationDocumentChecklist submitStoredDocumentForLsp(
            UUID lspId,
            UUID applicationId,
            LoanApplicationDocumentType documentType,
            String actorUsername,
            String note,
            String sourceReference,
            String correctionReason,
            MultipartFile file
    ) {
        return storeAndCommit(
                lspId,
                applicationId,
                actorUsername,
                List.of(new BatchDocumentUpload(documentType, note, sourceReference, file)),
                correctionReason
        ).get(0);
    }

    /**
     * Upload a batch atomically (M04): every item is validated before any object is written,
     * and the metadata for all items commits in one transaction or not at all. Objects written
     * before a failure stay PENDING and are removed by {@link LoanDocumentOrphanReconciler};
     * a retry of the same files resolves to the same objects.
     */
    public List<LoanApplicationDocumentChecklist> submitStoredDocumentsForLsp(
            UUID lspId,
            UUID applicationId,
            String actorUsername,
            List<BatchDocumentUpload> documents
    ) {
        return storeAndCommit(lspId, applicationId, actorUsername, documents, null);
    }

    // Intentionally not @Transactional: object writes must not run inside a database
    // transaction we own (H24). The phases are ordered so that no object is written for a
    // request that validation would reject, and every written object is durably owned first.
    private List<LoanApplicationDocumentChecklist> storeAndCommit(
            UUID lspId,
            UUID applicationId,
            String actorUsername,
            List<BatchDocumentUpload> documents,
            String correctionReason
    ) {
        loanApplicationQueryService.getApplicationForLsp(lspId, applicationId);
        if (documents == null || documents.isEmpty()) {
            throw new IllegalArgumentException("At least one document upload is required.");
        }
        rejectDuplicateDocumentTypes(documents);

        // 1. Validate the whole request: content policy for every file, then upload policy.
        List<PreparedDocument> prepared = documents.stream()
                .map(document -> loanDocumentStorageService.prepare(
                        applicationId,
                        document.documentType(),
                        document.file()
                ))
                .toList();
        List<DocumentSubmission> intended = new ArrayList<>(documents.size());
        for (int index = 0; index < documents.size(); index++) {
            intended.add(submissionFor(documents.get(index), prepared.get(index), null));
        }
        documentChecklistService.checkSubmissionsAllowed(applicationId, intended, correctionReason);

        // 2. Own each object durably, then write it.
        List<DocumentSubmission> submissions = new ArrayList<>(documents.size());
        for (int index = 0; index < documents.size(); index++) {
            PreparedDocument document = prepared.get(index);
            recordPendingObject(document);
            StoredDocument stored = loanDocumentStorageService.store(document);
            submissions.add(submissionFor(documents.get(index), document, stored.canonicalUri()));
        }

        // 3. Metadata, object links and the approval decision commit together (H13).
        return loanAutoApprovalGateService.commitDocumentSubmissions(
                applicationId,
                actorUsername,
                submissions,
                correctionReason
        ).checklistItems();
    }

    /**
     * Commit the ownership record in its own transaction before the object write, so an object
     * whose metadata later rolls back — including under an outer idempotency transaction — is
     * always known to the orphan reconciler.
     */
    private void recordPendingObject(PreparedDocument document) {
        String state = requiresNewTransactionTemplate.execute(status -> documentObjectRepository.upsertPending(
                document.storageKey(),
                document.applicationId(),
                document.documentType().name(),
                document.checksum(),
                document.content().length
        ));
        if (LoanDocumentObject.DELETING.equals(state)) {
            throw new ApiConflictException(
                    "DOCUMENT_STORAGE_RETRY_REQUIRED",
                    "A previous copy of this document is being cleaned up. Retry the upload.",
                    1
            );
        }
    }

    private static DocumentSubmission submissionFor(
            BatchDocumentUpload upload,
            PreparedDocument document,
            String fileReference
    ) {
        return DocumentSubmission.stored(
                upload.documentType(),
                upload.note(),
                upload.sourceReference(),
                document.fileName(),
                fileReference,
                document.contentType(),
                document.content().length,
                document.checksum(),
                document.storageKey()
        );
    }

    private static void rejectDuplicateDocumentTypes(List<BatchDocumentUpload> documents) {
        Set<LoanApplicationDocumentType> seenDocumentTypes = new HashSet<>();
        for (BatchDocumentUpload document : documents) {
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
        }
    }

    public record BatchDocumentUpload(
            LoanApplicationDocumentType documentType,
            String note,
            String sourceReference,
            MultipartFile file
    ) {
    }

    private LoanApplicationDocumentChecklist getDocumentChecklistItem(
            UUID applicationId,
            LoanApplicationDocumentType documentType
    ) {
        loanApplicationQueryService.getApplication(applicationId);
        return loanApplicationDocumentChecklistRepository.findByLoanApplication_IdAndDocumentType(applicationId, documentType)
                .orElseThrow(() -> new DocumentNotFoundException(
                        "Document checklist item not found for type " + documentType.name()
                                + " on application " + applicationId
                ));
    }
}
