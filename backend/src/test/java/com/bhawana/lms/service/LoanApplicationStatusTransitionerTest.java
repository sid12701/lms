package com.bhawana.lms.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.bhawana.lms.common.api.error.BusinessRuleViolationException;
import com.bhawana.lms.domain.LoanApplicationStatus;
import org.junit.jupiter.api.Test;

class LoanApplicationStatusTransitionerTest {

    @Test
    void enforceTransitionAllowsDocumentedLifecycleEdges() {
        assertDoesNotThrow(() -> LoanApplicationStatusTransitioner.enforceTransition(
                LoanApplicationStatus.INITIALIZED,
                LoanApplicationStatus.AWAITING_APPROVAL
        ));
        assertDoesNotThrow(() -> LoanApplicationStatusTransitioner.enforceTransition(
                LoanApplicationStatus.AWAITING_APPROVAL,
                LoanApplicationStatus.APPROVED_PENDING_DISBURSAL
        ));
        assertDoesNotThrow(() -> LoanApplicationStatusTransitioner.enforceTransition(
                LoanApplicationStatus.DISBURSED,
                LoanApplicationStatus.FORECLOSED
        ));
    }

    @Test
    void workerContextAllowsApprovedToRejectedForPolicyFailures() {
        assertDoesNotThrow(() -> LoanApplicationStatusTransitioner.enforceTransition(
                LoanApplicationStatus.APPROVED_PENDING_DISBURSAL,
                LoanApplicationStatus.REJECTED,
                LoanApplicationStatusTransitioner.TransitionContext.WORKER
        ));
    }

    @Test
    void standardContextRejectsApprovedToRejected() {
        assertThrows(
                BusinessRuleViolationException.class,
                () -> LoanApplicationStatusTransitioner.enforceTransition(
                        LoanApplicationStatus.APPROVED_PENDING_DISBURSAL,
                        LoanApplicationStatus.REJECTED
                )
        );
    }

    @Test
    void enforceTransitionRejectsInvalidEdge() {
        BusinessRuleViolationException exception = assertThrows(
                BusinessRuleViolationException.class,
                () -> LoanApplicationStatusTransitioner.enforceTransition(
                        LoanApplicationStatus.REJECTED,
                        LoanApplicationStatus.APPROVED_PENDING_DISBURSAL
                )
        );
        assertEquals("INVALID_STATUS_TRANSITION", exception.getErrorCode());
        assertEquals(
                "Cannot transition loan application from REJECTED to APPROVED_PENDING_DISBURSAL.",
                exception.getMessage()
        );
    }

    @Test
    void enforceAutoApprovalAllowedOnlyForIntakeStatuses() {
        assertDoesNotThrow(() -> LoanApplicationStatusTransitioner.enforceAutoApprovalAllowed(
                LoanApplicationStatus.INITIALIZED
        ));
        assertDoesNotThrow(() -> LoanApplicationStatusTransitioner.enforceAutoApprovalAllowed(
                LoanApplicationStatus.AWAITING_APPROVAL
        ));

        BusinessRuleViolationException exception = assertThrows(
                BusinessRuleViolationException.class,
                () -> LoanApplicationStatusTransitioner.enforceAutoApprovalAllowed(
                        LoanApplicationStatus.REJECTED
                )
        );
        assertEquals("AUTO_APPROVAL_NOT_ALLOWED", exception.getErrorCode());
        assertEquals("REJECTED", exception.getFieldErrors().get("status"));
    }
}
