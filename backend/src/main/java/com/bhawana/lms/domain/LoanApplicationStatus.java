package com.bhawana.lms.domain;

public enum LoanApplicationStatus {
    RECEIVED,
    UNDER_REVIEW,
    APPROVED,
    REJECTED;

    public boolean canTransitionTo(LoanApplicationStatus targetStatus) {
        return switch (this) {
            case RECEIVED -> targetStatus == UNDER_REVIEW;
            case UNDER_REVIEW -> targetStatus == APPROVED || targetStatus == REJECTED;
            case APPROVED, REJECTED -> false;
        };
    }
}
