package com.bhawana.lms.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bhawana.lms.domain.LoanApplicationDocumentChecklistStatus;
import com.bhawana.lms.domain.LoanApplicationDocumentType;
import org.junit.jupiter.api.Test;

class LoanApplicationDocumentRequirementsTest {

    @Test
    void intakeRequiredMatchesAllEightDisbursementRequiredTypes() {
        assertTrue(LoanApplicationDocumentRequirements.isIntakeRequired(LoanApplicationDocumentType.PAN_CARD));
        assertTrue(LoanApplicationDocumentRequirements.isIntakeRequired(LoanApplicationDocumentType.KFS));
        assertTrue(LoanApplicationDocumentRequirements.isIntakeRequired(LoanApplicationDocumentType.LOAN_AGREEMENT));
    }

    @Test
    void checklistItemCompleteTreatsSubmittedAndNotRequiredAsComplete() {
        var submitted = org.mockito.Mockito.mock(com.bhawana.lms.domain.LoanApplicationDocumentChecklist.class);
        org.mockito.Mockito.when(submitted.getStatus()).thenReturn(LoanApplicationDocumentChecklistStatus.SUBMITTED);
        assertTrue(LoanApplicationDocumentRequirements.isChecklistItemComplete(submitted));

        var notRequired = org.mockito.Mockito.mock(com.bhawana.lms.domain.LoanApplicationDocumentChecklist.class);
        org.mockito.Mockito.when(notRequired.getStatus()).thenReturn(LoanApplicationDocumentChecklistStatus.NOT_REQUIRED);
        assertTrue(LoanApplicationDocumentRequirements.isChecklistItemComplete(notRequired));

        var pending = org.mockito.Mockito.mock(com.bhawana.lms.domain.LoanApplicationDocumentChecklist.class);
        org.mockito.Mockito.when(pending.getStatus()).thenReturn(LoanApplicationDocumentChecklistStatus.PENDING);
        assertFalse(LoanApplicationDocumentRequirements.isChecklistItemComplete(pending));
    }
}
