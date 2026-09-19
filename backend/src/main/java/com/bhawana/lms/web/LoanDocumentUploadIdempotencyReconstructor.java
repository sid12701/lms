package com.bhawana.lms.web;

import com.bhawana.lms.domain.LoanApplicationDocumentChecklist;
import com.bhawana.lms.domain.LoanApplicationDocumentChecklistStatus;
import com.bhawana.lms.domain.LoanApplicationDocumentType;
import com.bhawana.lms.repo.LoanApplicationDocumentChecklistRepository;
import com.bhawana.lms.service.IdempotencyResultReconstructor;
import com.bhawana.lms.web.LspLoanApplicationApiController.BatchDocumentUploadFingerprint;
import com.bhawana.lms.web.LspLoanApplicationApiController.BatchDocumentUploadIdempotencyResponse;
import com.bhawana.lms.web.LspLoanApplicationApiController.DocumentUploadFingerprint;
import com.bhawana.lms.web.LspLoanApplicationApiController.LspDocumentChecklistDetailResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Evidence-based recovery for LSP document uploads whose idempotency record is
 * still pending after the previous owner's lease expired.
 *
 * <p>The upload action stores the object before the checklist update commits,
 * so a crash can leave an orphaned storage object or a committed checklist row
 * with no completed idempotency record. The checklist row is the durable
 * evidence: a SUBMITTED LMS-managed row whose stored checksum matches the
 * fingerprinted request content proves the upload's database half landed, and
 * the response is rebuilt from it without touching storage again. When the
 * checklist does not carry that evidence the outcome stays unknown to this
 * reconstructor — the coordinator then re-executes under the
 * content-addressed storage key, which converges on the same object identity
 * rather than multiplying orphans.
 */
@Component
class LoanDocumentUploadIdempotencyReconstructor implements IdempotencyResultReconstructor {

    private final LoanApplicationDocumentChecklistRepository checklistRepository;

    LoanDocumentUploadIdempotencyReconstructor(
            LoanApplicationDocumentChecklistRepository checklistRepository
    ) {
        this.checklistRepository = checklistRepository;
    }

    @Override
    public boolean supports(String operationKey, Class<?> responseType) {
        return (LspLoanApplicationApiController.LOAN_DOCUMENT_UPLOAD.equals(operationKey)
                        && LspDocumentChecklistDetailResponse.class.isAssignableFrom(responseType))
                || (LspLoanApplicationApiController.LOAN_DOCUMENT_BATCH_UPLOAD.equals(operationKey)
                        && BatchDocumentUploadIdempotencyResponse.class.isAssignableFrom(responseType));
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Optional<T> tryRecover(
            UUID lspId,
            String operationKey,
            Object requestFingerprintSource,
            Class<T> responseType
    ) {
        if (!supports(operationKey, responseType) || lspId == null) {
            return Optional.empty();
        }
        if (requestFingerprintSource instanceof DocumentUploadFingerprint fingerprint) {
            return recoverSingle(fingerprint).map(item -> (T) item);
        }
        if (requestFingerprintSource instanceof BatchDocumentUploadFingerprint batchFingerprint) {
            return recoverBatch(batchFingerprint).map(response -> (T) response);
        }
        return Optional.empty();
    }

    private Optional<LspDocumentChecklistDetailResponse> recoverSingle(DocumentUploadFingerprint fingerprint) {
        return loadMatchingChecklistItem(fingerprint)
                .map(LspLoanApplicationResponses::toDocumentChecklistDetailResponse);
    }

    private Optional<BatchDocumentUploadIdempotencyResponse> recoverBatch(
            BatchDocumentUploadFingerprint fingerprint
    ) {
        List<LspDocumentChecklistDetailResponse> recovered = new ArrayList<>(fingerprint.parts().size());
        for (DocumentUploadFingerprint part : fingerprint.parts()) {
            Optional<LoanApplicationDocumentChecklist> item = loadMatchingChecklistItem(part);
            if (item.isEmpty()) {
                return Optional.empty();
            }
            recovered.add(LspLoanApplicationResponses.toDocumentChecklistDetailResponse(item.get()));
        }
        return Optional.of(new BatchDocumentUploadIdempotencyResponse(recovered));
    }

    /**
     * The committed checklist row is the recovery evidence: it must show a
     * SUBMITTED LMS-managed document whose stored checksum is exactly the
     * fingerprinted request content. Anything else (still pending, a different
     * checksum written by a later distinct upload) is not proof of this
     * request's outcome.
     */
    private Optional<LoanApplicationDocumentChecklist> loadMatchingChecklistItem(
            DocumentUploadFingerprint fingerprint
    ) {
        UUID applicationId;
        LoanApplicationDocumentType documentType;
        try {
            applicationId = UUID.fromString(fingerprint.applicationId());
            documentType = LoanApplicationDocumentType.valueOf(fingerprint.documentType());
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
        return checklistRepository
                .findByLoanApplication_IdAndDocumentType(applicationId, documentType)
                .filter(item -> item.getStatus() == LoanApplicationDocumentChecklistStatus.SUBMITTED)
                .filter(LoanApplicationDocumentChecklist::isLmsManagedContent)
                .filter(item -> fingerprint.contentSha256().equals(item.getFileChecksum()))
                .filter(item -> item.getStorageKey() != null);
    }
}
