package com.bhawana.lms.common.api.error;

import com.bhawana.lms.domain.LoanApplicationDocumentType;
import java.util.List;

public class KycCompletionRequiredException extends RuntimeException {

    private final List<LoanApplicationDocumentType> blockingDocumentTypes;

    public KycCompletionRequiredException(List<LoanApplicationDocumentType> blockingDocumentTypes) {
        super("Loan application cannot be approved until required KYC documents are complete.");
        this.blockingDocumentTypes = List.copyOf(blockingDocumentTypes);
    }

    public List<LoanApplicationDocumentType> getBlockingDocumentTypes() {
        return blockingDocumentTypes;
    }
}

