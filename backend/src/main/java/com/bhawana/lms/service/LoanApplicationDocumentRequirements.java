package com.bhawana.lms.service;

import com.bhawana.lms.domain.LoanApplicationDocumentChecklist;
import com.bhawana.lms.domain.LoanApplicationDocumentChecklistStatus;
import com.bhawana.lms.domain.LoanApplicationDocumentType;

/**
 * Shared rules for the eight intake-required document types (PAN through Loan Agreement).
 * All completion checks for auto-approval, webhooks, and the rule engine use this set.
 */
public final class LoanApplicationDocumentRequirements {

    private LoanApplicationDocumentRequirements() {
    }

    public static boolean isIntakeRequired(LoanApplicationDocumentType documentType) {
        return documentType.isRequiredForDisbursement();
    }

    public static boolean isChecklistItemComplete(LoanApplicationDocumentChecklist item) {
        return item.getStatus() == LoanApplicationDocumentChecklistStatus.SUBMITTED
                || item.getStatus() == LoanApplicationDocumentChecklistStatus.NOT_REQUIRED;
    }

    public static boolean isChecklistItemCompleteForDisbursement(LoanApplicationDocumentChecklist item) {
        return isChecklistItemComplete(item)
                && (item.getStatus() == LoanApplicationDocumentChecklistStatus.NOT_REQUIRED
                        || item.isLmsManagedContent());
    }
}
