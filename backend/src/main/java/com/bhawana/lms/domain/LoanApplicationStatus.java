package com.bhawana.lms.domain;

public enum LoanApplicationStatus {
    RECEIVED,
    UNDER_REVIEW,
    HOLD,
    APPROVED,
    REJECTED;

    public boolean canTransitionTo(LoanApplicationStatus targetStatus) {
        return switch (this) {
            case RECEIVED -> targetStatus == UNDER_REVIEW;
            case UNDER_REVIEW -> targetStatus == HOLD || targetStatus == APPROVED || targetStatus == REJECTED;
            case HOLD -> targetStatus == UNDER_REVIEW;
            case APPROVED, REJECTED -> false;
        };
    }
}
