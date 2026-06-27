package com.bhawana.lms.service;

import com.bhawana.lms.common.correlation.CorrelationIdHolder;
import com.bhawana.lms.common.util.Strings;
import com.bhawana.lms.domain.LoanAccount;
import com.bhawana.lms.domain.LoanAccountStatus;
import com.bhawana.lms.domain.LoanApplication;
import com.bhawana.lms.domain.LoanApplicationAuditAction;
import com.bhawana.lms.domain.LoanApplicationAuditEvent;
import com.bhawana.lms.domain.LoanApplicationStatus;
import com.bhawana.lms.domain.LoanApplicationStatusReasonCode;
import com.bhawana.lms.domain.LoanApplicationStatusTransition;
import com.bhawana.lms.domain.WebhookEventType;
import com.bhawana.lms.repo.LoanAccountRepository;
import com.bhawana.lms.repo.LoanApplicationAuditEventRepository;
import com.bhawana.lms.repo.LoanApplicationRepository;
import com.bhawana.lms.repo.LoanApplicationStatusTransitionRepository;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Single transactional owner for loan-application status mutations, transition rows,
 * audit events, and status-change webhooks.
 */
@Service
public class LoanApplicationStatusWriter {

    private final LoanAccountRepository loanAccountRepository;
    private final LoanApplicationAuditEventRepository loanApplicationAuditEventRepository;
    private final LoanApplicationRepository loanApplicationRepository;
    private final LoanApplicationStatusTransitionRepository loanApplicationStatusTransitionRepository;
    private final LoanRepaymentScheduleService loanRepaymentScheduleService;
    private final WebhookOutboxService webhookOutboxService;

    public LoanApplicationStatusWriter(
            LoanAccountRepository loanAccountRepository,
            LoanApplicationAuditEventRepository loanApplicationAuditEventRepository,
            LoanApplicationRepository loanApplicationRepository,
            LoanApplicationStatusTransitionRepository loanApplicationStatusTransitionRepository,
            LoanRepaymentScheduleService loanRepaymentScheduleService,
            WebhookOutboxService webhookOutboxService
    ) {
        this.loanAccountRepository = loanAccountRepository;
        this.loanApplicationAuditEventRepository = loanApplicationAuditEventRepository;
        this.loanApplicationRepository = loanApplicationRepository;
        this.loanApplicationStatusTransitionRepository = loanApplicationStatusTransitionRepository;
        this.loanRepaymentScheduleService = loanRepaymentScheduleService;
        this.webhookOutboxService = webhookOutboxService;
    }

    @Transactional
    public LoanApplication updateStatus(
            LoanApplication application,
            LoanApplicationStatusTransitionCommand command
    ) {
        LoanApplicationStatus targetStatus = command.targetStatus();
        LoanApplicationStatus currentStatus = application.getStatus();
        if (currentStatus == targetStatus) {
            return application;
        }

        LoanApplicationStatusTransitioner.enforceTransition(
                currentStatus,
                targetStatus,
                command.transitionContext()
        );
        application.transitionTo(targetStatus);
        LoanApplication savedApplication = loanApplicationRepository.save(application);
        String resolvedNote = Strings.normalizeOptional(command.note()) == null
                ? defaultTransitionNote(currentStatus, targetStatus)
                : Strings.normalizeOptional(command.note());
        loanApplicationStatusTransitionRepository.save(new LoanApplicationStatusTransition(
                savedApplication,
                currentStatus,
                targetStatus,
                Strings.normalizeActor(command.actorUsername()),
                resolvedNote,
                command.reasonCode(),
                CorrelationIdHolder.get(),
                command.rejectionReasonJson()
        ));
        recordAuditEvent(
                savedApplication,
                command.auditAction(),
                currentStatus,
                targetStatus,
                command.actorUsername(),
                resolvedNote,
                command.reasonCode()
        );
        webhookOutboxService.enqueueIfSubscribed(
                savedApplication.getLsp(),
                WebhookEventType.LOAN_STATUS_CHANGED,
                "LOAN_APPLICATION",
                savedApplication.getId().toString(),
                savedApplication.getId(),
                LoanWebhookPayloads.loanStatusChanged(
                        savedApplication,
                        currentStatus,
                        targetStatus,
                        command.reasonCode()
                )
        );
        return savedApplication;
    }

    @Transactional
    public LoanAccount ensureLoanAccountForApprovedApplication(LoanApplication application) {
        LoanAccount loanAccount = loanAccountRepository.findByLoanApplication_Id(application.getId())
                .orElseGet(() -> loanAccountRepository.save(new LoanAccount(
                        application,
                        application.getBorrower(),
                        application.getLsp(),
                        application.getLoanProduct(),
                        generateAccountNumber(application),
                        application.getRequestedAmount(),
                        application.getTenureMonths(),
                        LoanAccountStatus.PENDING_DISBURSEMENT,
                        Instant.now()
                )));
        loanRepaymentScheduleService.generateIfAbsent(loanAccount);
        return loanAccount;
    }

    public void recordAuditEvent(
            LoanApplication application,
            LoanApplicationAuditAction action,
            LoanApplicationStatus fromStatus,
            LoanApplicationStatus toStatus,
            String actorUsername,
            String note,
            LoanApplicationStatusReasonCode reasonCode
    ) {
        loanApplicationAuditEventRepository.save(new LoanApplicationAuditEvent(
                application,
                action,
                Strings.normalizeActor(actorUsername),
                fromStatus,
                toStatus,
                note,
                reasonCode,
                CorrelationIdHolder.get()
        ));
    }

    private static String defaultTransitionNote(
            LoanApplicationStatus currentStatus,
            LoanApplicationStatus targetStatus
    ) {
        return "Transitioned loan application from " + currentStatus.name() + " to " + targetStatus.name();
    }

    private static String generateAccountNumber(LoanApplication application) {
        String compactId = application.getId().toString().replace("-", "").toUpperCase();
        return "LMS-LN-" + compactId.substring(0, 12);
    }
}
