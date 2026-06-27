package com.bhawana.lms.domain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LoanApplicationStatusTest {

    @Test
    void cannotTransitionToSameStatus() {
        for (LoanApplicationStatus status : LoanApplicationStatus.values()) {
            assertFalse(status.canTransitionTo(status));
        }
    }

    @Test
    void disbursementRetryDoesNotSelfLoop() {
        assertFalse(LoanApplicationStatus.DISBURSEMENT_RETRY.canTransitionTo(LoanApplicationStatus.DISBURSEMENT_RETRY));
        assertTrue(LoanApplicationStatus.DISBURSEMENT_RETRY.canTransitionTo(LoanApplicationStatus.DISBURSED));
        assertTrue(LoanApplicationStatus.DISBURSEMENT_RETRY.canTransitionTo(LoanApplicationStatus.INVALID));
    }
}
