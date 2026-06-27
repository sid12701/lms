package com.bhawana.lms.common.api.error;

import com.bhawana.lms.domain.LoanApplicationDocumentType;
import java.util.List;

public class DocumentUploadRequiredException extends RuntimeException {

    private final List<LoanApplicationDocumentType> blockingDocumentTypes;

    public DocumentUploadRequiredException(List<LoanApplicationDocumentType> blockingDocumentTypes) {
        super("Loan disbursement cannot be requested until required documents are uploaded.");
        this.blockingDocumentTypes = List.copyOf(blockingDocumentTypes);
    }

    public List<LoanApplicationDocumentType> getBlockingDocumentTypes() {
        return blockingDocumentTypes;
    }
}

