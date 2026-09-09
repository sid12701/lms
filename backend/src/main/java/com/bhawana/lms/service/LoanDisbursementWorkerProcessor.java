package com.bhawana.lms.service;

import com.bhawana.lms.domain.Borrower;
import com.bhawana.lms.domain.LoanAccount;
import com.bhawana.lms.domain.LoanAccountStatus;
import com.bhawana.lms.domain.LoanApplication;
import com.bhawana.lms.domain.LoanApplicationAuditAction;
import com.bhawana.lms.domain.LoanApplicationStatus;
import com.bhawana.lms.domain.LoanApplicationStatusReasonCode;
import com.bhawana.lms.domain.LspStatus;
import com.bhawana.lms.repo.DisbursementIntentRepository;
import com.bhawana.lms.repo.LoanAccountRepository;
import com.bhawana.lms.repo.LoanApplicationRepository;
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
    private final LoanApplicationRepository loanApplicationRepository;
    private final DisbursementIntentRepository disbursementIntentRepository;
    private final LoanDisbursementRequestLogRepository loanDisbursementRequestLogRepository;
    private final LoanDisbursementCommandService loanDisbursementCommandService;
    private final LoanApplicationStatusWriter loanApplicationStatusWriter;
    private final DisbursementPreflightValidator disbursementPreflightValidator;
    private final BorrowerBankDetailsService borrowerBankDetailsService;
    private final OpsAlertEmitters opsAlertEmitters;
    private final LoanDisbursementWorkerProperties properties;

    public LoanDisbursementWorkerProcessor(
            LoanAccountRepository loanAccountRepository,
            LoanApplicationRepository loanApplicationRepository,
            DisbursementIntentRepository disbursementIntentRepository,
            LoanDisbursementRequestLogRepository loanDisbursementRequestLogRepository,
            LoanDisbursementCommandService loanDisbursementCommandService,
            LoanApplicationStatusWriter loanApplicationStatusWriter,
            DisbursementPreflightValidator disbursementPreflightValidator,
            BorrowerBankDetailsService borrowerBankDetailsService,
            OpsAlertEmitters opsAlertEmitters,
            LoanDisbursementWorkerProperties properties
    ) {
        this.loanAccountRepository = loanAccountRepository;
        this.loanApplicationRepository = loanApplicationRepository;
        this.disbursementIntentRepository = disbursementIntentRepository;
        this.loanDisbursementRequestLogRepository = loanDisbursementRequestLogRepository;
        this.loanDisbursementCommandService = loanDisbursementCommandService;
        this.loanApplicationStatusWriter = loanApplicationStatusWriter;
        this.disbursementPreflightValidator = disbursementPreflightValidator;
        this.borrowerBankDetailsService = borrowerBankDetailsService;
        this.opsAlertEmitters = opsAlertEmitters;
        this.properties = properties;
    }

    /**
     * H01: ONE atomic transaction for guarded validation plus initiation. There is
     * deliberately NO catch here — any failure rolls this item's transaction back and
     * propagates through the Spring proxy to {@link LoanDisbursementWorkerService},
     * which catches only AFTER the transaction has ended. Catching inside would leave
     * the transaction rollback-only and poison the rest of the tick.
     *
     * @return true when the worker took action (processed, rejected, or exhausted
     * retries); false when skipped. Never returns true on failure.
     */
    @Transactional
    public boolean processApplication(UUID applicationId) {
        return TenantScopedExecution.callAsAdmin(() -> processLocked(applicationId));
    }

    private boolean processLocked(UUID applicationId) {
        // Common lock order: borrower → application → account → live intent. The borrower
        // lock comes first because approval and bank-detail changes serialize cross-loan work
        // on the same shared borrower lock; acquiring it here keeps every concurrent writer
        // in one global order. Every decision below reads the locked (refreshed) rows, so a
        // submission committed just before the locks were granted is always observed.
        Borrower borrower = loanApplicationRepository.findBorrowerByApplicationIdForUpdate(applicationId).orElse(null);
        LoanApplication application = loanApplicationRepository.findByIdForUpdate(applicationId).orElse(null);
        if (borrower == null || application == null) {
            return false;
        }
        if (application.getLsp() == null || application.getLsp().getStatus() != LspStatus.ACTIVE) {
            return false;
        }
        if (application.getStatus() != LoanApplicationStatus.APPROVED_PENDING_DISBURSAL
                && application.getStatus() != LoanApplicationStatus.DISBURSEMENT_RETRY) {
            return false;
        }

        LoanAccount loanAccount = loanAccountRepository.findByLoanApplication_IdForUpdate(applicationId).orElse(null);
        if (loanAccount == null) {
            rejectForBoundViolation(application, "MISSING_LOAN_ACCOUNT", "Loan account is not available for disbursement.");
            return true;
        }
        // H01/C04: skip both submitted (in-flight REQUESTED) and parked (uncertain-money
        // PENDING_RECONCILIATION) states BEFORE any validation or initiation attempt, and
        // never re-initiate while a live intent exists. The only forward path for them is
        // reconciliation of the original reference — never a new intent.
        if (loanAccount.getStatus() == LoanAccountStatus.DISBURSEMENT_REQUESTED
                || loanAccount.getStatus() == LoanAccountStatus.DISBURSEMENT_PENDING_RECONCILIATION) {
            return false;
        }
        if (disbursementIntentRepository.findLiveByLoanAccountIdForUpdate(loanAccount.getId()).isPresent()) {
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

        // Pre-initiation exhaustion stays in this atomic transaction under the same locks:
        // the attempt count cannot move underneath the check.
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

        // Tx-A joins this transaction (C04: the intent workflow is the only path; the provider
        // call happens after this transaction commits — WorkerService executes it). No catch:
        // a failure rolls back and is reported by the caller, never as success.
        loanDisbursementCommandService.initiateDisbursement(applicationId, LoanDisbursementWorkerService.WORKER_ACTOR);
        return true;
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
