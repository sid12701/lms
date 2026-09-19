package com.bhawana.lms.service;

import com.bhawana.lms.domain.LoanApplicationDocumentChecklist;
import java.util.List;

/**
 * Outcome of recording one or more document submissions in a single transaction, including
 * whether all intake-required documents became complete in it.
 */
public record DocumentSubmissionResult(
        List<LoanApplicationDocumentChecklist> checklistItems,
        boolean allRequiredDocumentsJustCompleted
) {
}
