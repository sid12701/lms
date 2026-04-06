package com.bhawana.lms.domain;

public enum LoanApplicationStatus {
    INITIALIZED,
    AWAITING_APPROVAL,
    APPROVED_PENDING_DISBURSAL,
    REJECTED,
    PAYMENT_REINITIATION,
    DISBURSED,
    UNDER_REPAYMENT,
    CLOSED;

    public boolean canTransitionTo(LoanApplicationStatus targetStatus) {
        return switch (this) {
            case INITIALIZED -> targetStatus == AWAITING_APPROVAL;
            case AWAITING_APPROVAL -> targetStatus == APPROVED_PENDING_DISBURSAL || targetStatus == REJECTED;
            case APPROVED_PENDING_DISBURSAL,
                    REJECTED,
                    PAYMENT_REINITIATION,
                    DISBURSED,
                    UNDER_REPAYMENT,
                    CLOSED -> false;
        };
    }
}
