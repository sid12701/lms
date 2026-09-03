package com.bhawana.lms.service;

import com.bhawana.lms.common.api.error.BusinessRuleViolationException;
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
import com.bhawana.lms.domain.LoanEventType;
import com.bhawana.lms.repo.LoanAccountRepository;
import com.bhawana.lms.repo.LoanApplicationAuditEventRepository;
import com.bhawana.lms.repo.LoanApplicationRepository;
import com.bhawana.lms.repo.LoanApplicationStatusTransitionRepository;
import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Single transactional owner for loan-application status mutations, transition rows,
 * audit events, and status-change loan events.
 */
@Service
public class LoanApplicationStatusWriter {

    private final LoanAccountRepository loanAccountRepository;
    private final LoanApplicationAuditEventRepository loanApplicationAuditEventRepository;
    private final LoanApplicationRepository loanApplicationRepository;
    private final LoanApplicationStatusTransitionRepository loanApplicationStatusTransitionRepository;
    private final BorrowerActiveLoanChecker borrowerActiveLoanChecker;
    private final LoanRepaymentScheduleService loanRepaymentScheduleService;
    private final LoanEventLog loanEventLog;

    public LoanApplicationStatusWriter(
            LoanAccountRepository loanAccountRepository,
            LoanApplicationAuditEventRepository loanApplicationAuditEventRepository,
            LoanApplicationRepository loanApplicationRepository,
            LoanApplicationStatusTransitionRepository loanApplicationStatusTransitionRepository,
            BorrowerActiveLoanChecker borrowerActiveLoanChecker,
            LoanRepaymentScheduleService loanRepaymentScheduleService,
            LoanEventLog loanEventLog
    ) {
        this.loanAccountRepository = loanAccountRepository;
        this.loanApplicationAuditEventRepository = loanApplicationAuditEventRepository;
        this.loanApplicationRepository = loanApplicationRepository;
        this.loanApplicationStatusTransitionRepository = loanApplicationStatusTransitionRepository;
        this.borrowerActiveLoanChecker = borrowerActiveLoanChecker;
        this.loanRepaymentScheduleService = loanRepaymentScheduleService;
        this.loanEventLog = loanEventLog;
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
        loanEventLog.append(
                savedApplication.getLsp(),
                LoanEventType.LOAN_STATUS_CHANGED,
                "LOAN_APPLICATION",
                savedApplication.getId().toString(),
                savedApplication.getId(),
                LoanEventPayloads.loanStatusChanged(
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
        loanApplicationRepository.findBorrowerByApplicationIdForUpdate(application.getId())
                .orElseThrow(() -> new IllegalStateException(
                        "Cannot create a loan account for an application without a borrower."));

        LoanAccount existingAccount = loanAccountRepository.findByLoanApplication_Id(application.getId())
                .orElse(null);
        if (existingAccount != null) {
            loanRepaymentScheduleService.generateIfAbsent(existingAccount);
            return existingAccount;
        }

        if (borrowerActiveLoanChecker.hasOpenLoanAcrossAllLsps(application.getBorrower().getId())) {
            throw new BusinessRuleViolationException(
                    "BORROWER_HAS_OPEN_LOAN",
                    "Borrower already has an open loan. Approval cannot create another loan account.",
                    Map.of("borrowerId", application.getBorrower().getId().toString())
            );
        }

        LoanAccount loanAccount = loanAccountRepository.save(new LoanAccount(
                application,
                application.getBorrower(),
                application.getLsp(),
                application.getLoanProduct(),
                application.getLoanProductVersion(),
                generateAccountNumber(application),
                application.getRequestedAmount(),
                application.getTenureMonths(),
                LoanAccountStatus.PENDING_DISBURSEMENT,
                Instant.now()
        ));
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
