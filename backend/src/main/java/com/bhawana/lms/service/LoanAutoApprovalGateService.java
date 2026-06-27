package com.bhawana.lms.service;

import com.bhawana.lms.domain.LoanApplicationStatus;
import com.bhawana.lms.repo.LoanApplicationRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Issue #90 — runs auto-approval only on the edge when all eight intake-required
 * documents (including KFS and Loan Agreement) have just been submitted.
 */
@Service
public class LoanAutoApprovalGateService {

    private final LoanApplicationLifecycleService loanApplicationLifecycleService;
    private final LoanApplicationRepository loanApplicationRepository;
    private final Counter gateSkippedIncomplete;
    private final Counter gateSkippedStatus;
    private final Counter gateFired;

    public LoanAutoApprovalGateService(
            LoanApplicationLifecycleService loanApplicationLifecycleService,
            LoanApplicationRepository loanApplicationRepository,
            MeterRegistry meterRegistry
    ) {
        this.loanApplicationLifecycleService = loanApplicationLifecycleService;
        this.loanApplicationRepository = loanApplicationRepository;
        this.gateSkippedIncomplete = Counter.builder("lms.auto_approval.gate")
                .description("Auto-approval gate decisions after document upload")
                .tag("outcome", "skipped_incomplete")
                .register(meterRegistry);
        this.gateSkippedStatus = Counter.builder("lms.auto_approval.gate")
                .description("Auto-approval gate decisions after document upload")
                .tag("outcome", "skipped_status")
                .register(meterRegistry);
        this.gateFired = Counter.builder("lms.auto_approval.gate")
                .description("Auto-approval gate decisions after document upload")
                .tag("outcome", "fired")
                .register(meterRegistry);
    }

    public void maybeTriggerAutoApproval(
            UUID applicationId,
            String actorUsername,
            boolean allRequiredDocumentsJustCompleted
    ) {
        if (!allRequiredDocumentsJustCompleted) {
            gateSkippedIncomplete.increment();
            return;
        }
        LoanApplicationStatus status = loanApplicationRepository.findById(applicationId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown loan application id: " + applicationId))
                .getStatus();
        if (status != LoanApplicationStatus.INITIALIZED && status != LoanApplicationStatus.AWAITING_APPROVAL) {
            gateSkippedStatus.increment();
            return;
        }
        gateFired.increment();
        loanApplicationLifecycleService.autoApproveIfEligibleForLsp(applicationId, actorUsername);
    }
}
