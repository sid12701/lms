package com.bhawana.lms.service;

import com.bhawana.lms.domain.LoanApplicationDocumentChecklist;

/**
 * Outcome of a checklist row mutation, including whether all eight intake-required
 * document types (disbursement-required set) just became complete.
 */
public record DocumentChecklistUpdateResult(
        LoanApplicationDocumentChecklist checklistItem,
        boolean allRequiredDocumentsJustCompleted
) {
}
