package com.bhawana.lms.service;

import com.bhawana.lms.common.correlation.CorrelationIdHolder;
import com.bhawana.lms.domain.LoanAccount;
import com.bhawana.lms.domain.LoanAccountStatus;
import com.bhawana.lms.domain.LoanApplication;
import com.bhawana.lms.domain.LoanApplicationAuditAction;
import com.bhawana.lms.domain.LoanApplicationStatus;
import com.bhawana.lms.domain.LoanApplicationStatusReasonCode;
import com.bhawana.lms.domain.LspStatus;
import com.bhawana.lms.repo.LoanAccountRepository;
import com.bhawana.lms.repo.LoanDisbursementRequestLogRepository;
import com.bhawana.lms.tenant.TenantScopedExecution;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per-application disbursement worker actions. Isolated from
 * {@link LoanDisbursementWorkerService} so each application runs in its own
 * transaction (Spring proxy applies to calls through this bean, not self-invocation).
 */
@Service
public class LoanDisbursementWorkerProcessor {

    private static final Logger log = LoggerFactory.getLogger(LoanDisbursementWorkerProcessor.class);

    private final LoanAccountRepository loanAccountRepository;
    private final LoanDisbursementRequestLogRepository loanDisbursementRequestLogRepository;
    private final LoanApplicationQueryService loanApplicationQueryService;
    private final LoanDisbursementCommandService loanDisbursementCommandService;
    private final LoanApplicationStatusWriter loanApplicationStatusWriter;
    private final DisbursementPreflightValidator disbursementPreflightValidator;
    private final BorrowerBankDetailsService borrowerBankDetailsService;
    private final OpsAlertEmitters opsAlertEmitters;
    private final LoanDisbursementWorkerProperties properties;

    public LoanDisbursementWorkerProcessor(
            LoanAccountRepository loanAccountRepository,
            LoanDisbursementRequestLogRepository loanDisbursementRequestLogRepository,
            LoanApplicationQueryService loanApplicationQueryService,
            LoanDisbursementCommandService loanDisbursementCommandService,
            LoanApplicationStatusWriter loanApplicationStatusWriter,
            DisbursementPreflightValidator disbursementPreflightValidator,
            BorrowerBankDetailsService borrowerBankDetailsService,
            OpsAlertEmitters opsAlertEmitters,
            LoanDisbursementWorkerProperties properties
    ) {
        this.loanAccountRepository = loanAccountRepository;
        this.loanDisbursementRequestLogRepository = loanDisbursementRequestLogRepository;
        this.loanApplicationQueryService = loanApplicationQueryService;
        this.loanDisbursementCommandService = loanDisbursementCommandService;
        this.loanApplicationStatusWriter = loanApplicationStatusWriter;
        this.disbursementPreflightValidator = disbursementPreflightValidator;
        this.borrowerBankDetailsService = borrowerBankDetailsService;
        this.opsAlertEmitters = opsAlertEmitters;
        this.properties = properties;
    }

    /**
     * @return true when the worker took action (processed, rejected, or exhausted retries); false when skipped.
     */
    @Transactional
    public boolean processApplication(UUID applicationId) {
        return TenantScopedExecution.callAsAdmin(() -> processApplicationAsAdmin(applicationId));
    }

    private boolean processApplicationAsAdmin(UUID applicationId) {
        LoanApplication application = loanApplicationQueryService.getApplication(applicationId);
        if (application.getLsp() == null || application.getLsp().getStatus() != LspStatus.ACTIVE) {
            return false;
        }
        if (application.getStatus() != LoanApplicationStatus.APPROVED_PENDING_DISBURSAL
                && application.getStatus() != LoanApplicationStatus.DISBURSEMENT_RETRY) {
            return false;
        }

        LoanAccount loanAccount = loanAccountRepository.findByLoanApplication_Id(applicationId).orElse(null);
        if (loanAccount == null) {
            rejectForBoundViolation(application, "MISSING_LOAN_ACCOUNT", "Loan account is not available for disbursement.");
            return true;
        }
        if (loanAccount.getStatus() == LoanAccountStatus.DISBURSEMENT_REQUESTED) {
            return false;
        }

        Map<String, String> violations = disbursementPreflightValidator.validateAutomatedDisbursement(application, loanAccount);
        if (!violations.isEmpty()) {
            rejectForBoundViolation(application, "AUTOMATED_DISBURSEMENT_VALIDATION", violations);
            return true;
        }

        DisbursementBankDetailsValidation bankValidation =
                disbursementPreflightValidator.validateWorkerDisbursementBankDetails(application);
        if (!bankValidation.violations().isEmpty()) {
            rejectForBoundViolation(application, "AUTOMATED_DISBURSEMENT_BANK_VALIDATION", bankValidation.violations());
            return true;
        }
        if (!bankValidation.warnings().isEmpty()) {
            borrowerBankDetailsService.recordSoftHolderNameMismatch(
                    application,
                    application.getLsp().getId(),
                    application.getBorrower().getFullName(),
                    application.getBorrower().getAccountHolderName()
            );
        }

        long priorAttempts = loanDisbursementRequestLogRepository.countByLoanAccount_Id(loanAccount.getId());
        if (priorAttempts >= properties.getMaxAttempts()) {
            if (application.getStatus() != LoanApplicationStatus.DISBURSEMENT_RETRY) {
                loanApplicationStatusWriter.updateStatus(
                        application,
                        LoanApplicationStatusTransitionCommand.statusTransition(
                                LoanApplicationStatus.DISBURSEMENT_RETRY,
                                LoanDisbursementWorkerService.WORKER_ACTOR,
                                "Automated disbursement retries exhausted.",
                                LoanApplicationStatusReasonCode.POLICY_EXCEPTION,
                                LoanApplicationAuditAction.STATUS_TRANSITION
                        )
                );
                opsAlertEmitters.emitDisbursementRetryExhausted(application, (int) priorAttempts);
            }
            return true;
        }

        try {
            loanDisbursementCommandService.initiateDisbursement(applicationId, LoanDisbursementWorkerService.WORKER_ACTOR);
            if (properties.isAutoResolveMockOutcome()) {
                loanDisbursementCommandService.autoResolveAfterInitiate(
                        applicationId,
                        LoanDisbursementWorkerService.WORKER_ACTOR,
                        null,
                        CorrelationIdHolder.get()
                );
            }
            return true;
        } catch (RuntimeException exception) {
            log.warn(
                    "Automated disbursement attempt failed for application {}: {}",
                    applicationId,
                    exception.getMessage()
            );
            long attemptsAfterFailure = loanDisbursementRequestLogRepository.countByLoanAccount_Id(loanAccount.getId());
            if (attemptsAfterFailure >= properties.getMaxAttempts()) {
                loanApplicationStatusWriter.updateStatus(
                        application,
                        LoanApplicationStatusTransitionCommand.statusTransition(
                                LoanApplicationStatus.DISBURSEMENT_RETRY,
                                LoanDisbursementWorkerService.WORKER_ACTOR,
                                "Automated disbursement retries exhausted after adapter failure.",
                                LoanApplicationStatusReasonCode.POLICY_EXCEPTION,
                                LoanApplicationAuditAction.STATUS_TRANSITION
                        )
                );
                opsAlertEmitters.emitDisbursementRetryExhausted(application, (int) attemptsAfterFailure);
            }
            return true;
        }
    }

    private void rejectForBoundViolation(
            LoanApplication application,
            String violationType,
            Map<String, String> violations
    ) {
        String message = violations.values().stream().findFirst().orElse("Automated disbursement validation failed.");
        opsAlertEmitters.emitLspBoundViolation(application, violationType, message, violations);
        loanApplicationStatusWriter.updateStatus(
                application,
                LoanApplicationStatusTransitionCommand.builder()
                        .targetStatus(LoanApplicationStatus.REJECTED)
                        .actorUsername(LoanDisbursementWorkerService.WORKER_ACTOR)
                        .note("Rejected by disbursement worker: " + message)
                        .reasonCode(LoanApplicationStatusReasonCode.FAILED_VERIFICATION)
                        .auditAction(LoanApplicationAuditAction.STATUS_TRANSITION)
                        .transitionContext(LoanApplicationStatusTransitioner.TransitionContext.WORKER)
                        .build()
        );
    }

    private void rejectForBoundViolation(LoanApplication application, String violationType, String message) {
        rejectForBoundViolation(application, violationType, Map.of("summary", message));
    }
}
