package com.bhawana.lms.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Lifecycle status for a {@link LoanApplication}.
 *
 * <p>Authoritative state machine for Gap #11. Terminal states (REJECTED,
 * INVALID, CLOSED, FORECLOSED) cannot transition out. INVALID is reachable
 * from any pre-disbursal status (LSP-initiated cancel) via the dedicated
 * invalidate path, which uses {@link #isPreDisbursal()} as its gate.</p>
 */
public enum LoanApplicationStatus {
    INITIALIZED,
    AWAITING_APPROVAL,
    APPROVED_PENDING_DISBURSAL,
    REJECTED,
    DISBURSEMENT_RETRY,
    INVALID,
    DISBURSED,
    UNDER_REPAYMENT,
    CLOSED,
    FORECLOSED;

    public boolean canTransitionTo(LoanApplicationStatus targetStatus) {
        if (targetStatus == null || targetStatus == this) {
            return false;
        }
        return switch (this) {
            case INITIALIZED -> targetStatus == AWAITING_APPROVAL
                    || targetStatus == INVALID;
            case AWAITING_APPROVAL -> targetStatus == APPROVED_PENDING_DISBURSAL
                    || targetStatus == REJECTED
                    || targetStatus == INVALID;
            case APPROVED_PENDING_DISBURSAL -> targetStatus == DISBURSED
                    || targetStatus == DISBURSEMENT_RETRY
                    || targetStatus == INVALID;
            case DISBURSEMENT_RETRY -> targetStatus == DISBURSED
                    || targetStatus == DISBURSEMENT_RETRY
                    || targetStatus == INVALID;
            case DISBURSED -> targetStatus == UNDER_REPAYMENT;
            case UNDER_REPAYMENT -> targetStatus == CLOSED
                    || targetStatus == FORECLOSED;
            case REJECTED, INVALID, CLOSED, FORECLOSED -> false;
        };
    }

    public boolean isTerminal() {
        return TERMINAL_STATUSES.contains(this);
    }

    public boolean isPreDisbursal() {
        return PRE_DISBURSAL_STATUSES.contains(this);
    }

    private static final Set<LoanApplicationStatus> TERMINAL_STATUSES =
            EnumSet.of(REJECTED, INVALID, CLOSED, FORECLOSED);

    private static final Set<LoanApplicationStatus> PRE_DISBURSAL_STATUSES =
            EnumSet.of(INITIALIZED, AWAITING_APPROVAL, APPROVED_PENDING_DISBURSAL, DISBURSEMENT_RETRY);
}
