package com.bhawana.lms.service;

import com.bhawana.lms.domain.LoanApplicationStatus;
import com.bhawana.lms.repo.LoanApplicationRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issue #90 — runs auto-approval only on the edge when all eight intake-required
 * documents (including KFS and Loan Agreement) have just been submitted.
 *
 * <p>H13: the edge is decided and acted on in one transaction. {@link #commitDocumentSubmissions}
 * records the documents under the document-write lock and runs the borrower-locked approval in
 * the same transaction, so concurrent final uploads cannot both miss the edge and a crash cannot
 * commit a complete checklist without its approval decision — they commit or roll back
 * together, and a retry re-evaluates from scratch.</p>
 */
@Service
public class LoanAutoApprovalGateService {

    private final LoanApplicationLifecycleService loanApplicationLifecycleService;
    private final LoanApplicationDocumentChecklistService documentChecklistService;
    private final LoanApplicationRepository loanApplicationRepository;
    private final Counter gateSkippedIncomplete;
    private final Counter gateSkippedStatus;
    private final Counter gateFired;

    public LoanAutoApprovalGateService(
            LoanApplicationLifecycleService loanApplicationLifecycleService,
            LoanApplicationDocumentChecklistService documentChecklistService,
            LoanApplicationRepository loanApplicationRepository,
            MeterRegistry meterRegistry
    ) {
        this.loanApplicationLifecycleService = loanApplicationLifecycleService;
        this.documentChecklistService = documentChecklistService;
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

    @Transactional
    public DocumentSubmissionResult commitDocumentSubmissions(
            UUID applicationId,
            String actorUsername,
            List<DocumentSubmission> submissions,
            String correctionReason
    ) {
        DocumentSubmissionResult result = documentChecklistService.recordSubmissions(
                applicationId,
                actorUsername,
                submissions,
                correctionReason
        );
        maybeTriggerAutoApproval(applicationId, actorUsername, result.allRequiredDocumentsJustCompleted());
        return result;
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
