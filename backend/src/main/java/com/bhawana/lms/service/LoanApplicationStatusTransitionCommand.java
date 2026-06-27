package com.bhawana.lms.service;

import com.bhawana.lms.domain.LoanApplicationAuditAction;
import com.bhawana.lms.domain.LoanApplicationStatus;
import com.bhawana.lms.domain.LoanApplicationStatusReasonCode;

/**
 * Single call shape for {@link LoanApplicationStatusWriter#updateStatus}.
 */
public record LoanApplicationStatusTransitionCommand(
        LoanApplicationStatus targetStatus,
        String actorUsername,
        String note,
        LoanApplicationStatusReasonCode reasonCode,
        LoanApplicationAuditAction auditAction,
        String rejectionReasonJson,
        LoanApplicationStatusTransitioner.TransitionContext transitionContext
) {

    public LoanApplicationStatusTransitionCommand {
        if (transitionContext == null) {
            transitionContext = LoanApplicationStatusTransitioner.TransitionContext.STANDARD;
        }
    }

    public static LoanApplicationStatusTransitionCommand statusTransition(
            LoanApplicationStatus targetStatus,
            String actorUsername,
            String note,
            LoanApplicationStatusReasonCode reasonCode,
            LoanApplicationAuditAction auditAction
    ) {
        return new LoanApplicationStatusTransitionCommand(
                targetStatus,
                actorUsername,
                note,
                reasonCode,
                auditAction,
                null,
                LoanApplicationStatusTransitioner.TransitionContext.STANDARD
        );
    }

    public static LoanApplicationStatusTransitionCommand withRejection(
            LoanApplicationStatus targetStatus,
            String actorUsername,
            String note,
            LoanApplicationStatusReasonCode reasonCode,
            LoanApplicationAuditAction auditAction,
            String rejectionReasonJson
    ) {
        return new LoanApplicationStatusTransitionCommand(
                targetStatus,
                actorUsername,
                note,
                reasonCode,
                auditAction,
                rejectionReasonJson,
                LoanApplicationStatusTransitioner.TransitionContext.STANDARD
        );
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private LoanApplicationStatus targetStatus;
        private String actorUsername;
        private String note;
        private LoanApplicationStatusReasonCode reasonCode;
        private LoanApplicationAuditAction auditAction;
        private String rejectionReasonJson;
        private LoanApplicationStatusTransitioner.TransitionContext transitionContext =
                LoanApplicationStatusTransitioner.TransitionContext.STANDARD;

        public Builder targetStatus(LoanApplicationStatus targetStatus) {
            this.targetStatus = targetStatus;
            return this;
        }

        public Builder actorUsername(String actorUsername) {
            this.actorUsername = actorUsername;
            return this;
        }

        public Builder note(String note) {
            this.note = note;
            return this;
        }

        public Builder reasonCode(LoanApplicationStatusReasonCode reasonCode) {
            this.reasonCode = reasonCode;
            return this;
        }

        public Builder auditAction(LoanApplicationAuditAction auditAction) {
            this.auditAction = auditAction;
            return this;
        }

        public Builder rejectionReasonJson(String rejectionReasonJson) {
            this.rejectionReasonJson = rejectionReasonJson;
            return this;
        }

        public Builder transitionContext(LoanApplicationStatusTransitioner.TransitionContext transitionContext) {
            this.transitionContext = transitionContext;
            return this;
        }

        public LoanApplicationStatusTransitionCommand build() {
            return new LoanApplicationStatusTransitionCommand(
                    targetStatus,
                    actorUsername,
                    note,
                    reasonCode,
                    auditAction,
                    rejectionReasonJson,
                    transitionContext
            );
        }
    }
}
