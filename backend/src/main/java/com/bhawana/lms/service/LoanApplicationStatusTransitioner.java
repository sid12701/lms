package com.bhawana.lms.service;

import com.bhawana.lms.common.api.error.BusinessRuleViolationException;
import com.bhawana.lms.domain.LoanApplicationStatus;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Central guard rail for {@link LoanApplicationStatus} mutations (#135).
 * Callers must not bypass these checks when changing application status.
 */
public final class LoanApplicationStatusTransitioner {

    public enum TransitionContext {
        /** Ops transitions and auto-approval — {@link LoanApplicationStatus#canTransitionTo} only. */
        STANDARD,
        /** System-admin manual override — validated by {@link LoanApplicationLifecycleService#manuallyOverrideStatus}. */
        MANUAL_OVERRIDE,
        /** Automated disbursement worker policy actions. */
        WORKER
    }

    private LoanApplicationStatusTransitioner() {
    }

    public static void enforceTransition(
            LoanApplicationStatus fromStatus,
            LoanApplicationStatus toStatus,
            TransitionContext context
    ) {
        if (fromStatus == toStatus) {
            return;
        }
        if (isAllowed(fromStatus, toStatus, context)) {
            return;
        }
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        fieldErrors.put("fromStatus", fromStatus.name());
        fieldErrors.put("toStatus", toStatus.name());
        throw new BusinessRuleViolationException(
                "INVALID_STATUS_TRANSITION",
                "Cannot transition loan application from " + fromStatus.name() + " to " + toStatus.name() + ".",
                fieldErrors
        );
    }

    public static void enforceTransition(LoanApplicationStatus fromStatus, LoanApplicationStatus toStatus) {
        enforceTransition(fromStatus, toStatus, TransitionContext.STANDARD);
    }

    private static boolean isAllowed(
            LoanApplicationStatus fromStatus,
            LoanApplicationStatus toStatus,
            TransitionContext context
    ) {
        if (fromStatus.canTransitionTo(toStatus)) {
            return true;
        }
        return switch (context) {
            case STANDARD -> false;
            case MANUAL_OVERRIDE -> true;
            case WORKER -> (fromStatus == LoanApplicationStatus.APPROVED_PENDING_DISBURSAL
                    || fromStatus == LoanApplicationStatus.DISBURSEMENT_RETRY)
                    && toStatus == LoanApplicationStatus.REJECTED;
        };
    }

    public static void enforceAutoApprovalAllowed(LoanApplicationStatus currentStatus) {
        if (currentStatus == LoanApplicationStatus.INITIALIZED
                || currentStatus == LoanApplicationStatus.AWAITING_APPROVAL) {
            return;
        }
        throw new BusinessRuleViolationException(
                "AUTO_APPROVAL_NOT_ALLOWED",
                "Auto-approval can only run while the application is awaiting intake or approval review.",
                Map.of("status", currentStatus.name())
        );
    }
}
