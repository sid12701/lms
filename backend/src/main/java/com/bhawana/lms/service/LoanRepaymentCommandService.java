package com.bhawana.lms.service;

import com.bhawana.lms.common.correlation.CorrelationIdHolder;
import com.bhawana.lms.common.api.error.ApiConflictException;
import com.bhawana.lms.domain.LoanAccount;
import com.bhawana.lms.domain.LoanAccountClosureReason;
import com.bhawana.lms.domain.LoanApplication;
import com.bhawana.lms.domain.LoanApplicationAuditAction;
import com.bhawana.lms.domain.LoanApplicationStatus;
import com.bhawana.lms.domain.LoanPaymentChannel;
import com.bhawana.lms.domain.LoanPaymentStatus;
import com.bhawana.lms.domain.LoanPaymentTransaction;
import com.bhawana.lms.domain.LoanRepaymentScheduleInstallment;
import com.bhawana.lms.domain.LoanEventType;
import com.bhawana.lms.repo.LoanPaymentTransactionRepository;
import com.bhawana.lms.repo.LoanRepaymentScheduleInstallmentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class LoanRepaymentCommandService {

    /**
     * Contenders on one loan are serialized by its lock and settle within a single attempt, so the
     * retries are for the two cases the lock cannot cover: a version clash with another command
     * writing the application, and the same key raced across two different loans. Either is decided
     * by one committed winner, so a couple of replays is a bound, not a budget.
     */
    private static final int MAX_CONCURRENT_WRITE_ATTEMPTS = 3;

    private final LoanPaymentTransactionRepository loanPaymentTransactionRepository;
    private final LoanRepaymentScheduleInstallmentRepository loanRepaymentScheduleInstallmentRepository;
    private final LoanServicingSupportService loanServicingSupportService;
    private final LoanApplicationStatusWriter loanApplicationStatusWriter;
    private final LoanEventLog loanEventLog;
    private final ObjectMapper objectMapper;
    private final IdempotencyClaimService idempotencyClaimService;
    private final TransactionTemplate transactionTemplate;

    public LoanRepaymentCommandService(
            LoanPaymentTransactionRepository loanPaymentTransactionRepository,
            LoanRepaymentScheduleInstallmentRepository loanRepaymentScheduleInstallmentRepository,
            LoanServicingSupportService loanServicingSupportService,
            LoanApplicationStatusWriter loanApplicationStatusWriter,
            LoanEventLog loanEventLog,
            ObjectMapper objectMapper,
            IdempotencyClaimService idempotencyClaimService,
            PlatformTransactionManager transactionManager
    ) {
        this.loanPaymentTransactionRepository = loanPaymentTransactionRepository;
        this.loanRepaymentScheduleInstallmentRepository = loanRepaymentScheduleInstallmentRepository;
        this.loanServicingSupportService = loanServicingSupportService;
        this.loanApplicationStatusWriter = loanApplicationStatusWriter;
        this.loanEventLog = loanEventLog;
        this.objectMapper = objectMapper;
        this.idempotencyClaimService = idempotencyClaimService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * Records a payment, retrying the complete command when a concurrent writer wins the race.
     *
     * <p>Every retry starts from the replay lookup, so a contender that lost the unique
     * idempotency key returns the winner's committed receipt, and one whose winner rolled back
     * re-executes its own payment instead of reading a receipt that does not exist.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public LoanPaymentTransaction recordPaymentTransactionWithRecovery(
            UUID applicationId,
            String actorUsername,
            String idempotencyKey,
            UUID targetInstallmentId,
            BigDecimal amount,
            LocalDate postedAt,
            String reference,
            LoanPaymentChannel channel
    ) {
        for (int attempt = 1; attempt <= MAX_CONCURRENT_WRITE_ATTEMPTS; attempt++) {
            try {
                return recordPaymentTransaction(
                        applicationId,
                        actorUsername,
                        idempotencyKey,
                        targetInstallmentId,
                        amount,
                        postedAt,
                        reference,
                        channel
                );
            } catch (ConcurrencyFailureException exception) {
                // Optimistic version clash or a lock the database refused: nothing was written.
            } catch (DataIntegrityViolationException exception) {
                if (!isPaymentIdempotencyKeyViolation(exception)) {
                    throw exception;
                }
                // Another request claimed this key first; the retry resolves it as a replay.
            }
        }
        throw new ApiConflictException(
                "CONCURRENT_MODIFICATION",
                "The resource was modified by another request. Retry the operation."
        );
    }

    public LoanPaymentTransaction recordPaymentTransaction(
            UUID applicationId,
            String actorUsername,
            String idempotencyKey,
            UUID targetInstallmentId,
            BigDecimal amount,
            LocalDate postedAt,
            String reference,
            LoanPaymentChannel channel
    ) {
        String normalizedIdempotencyKey = loanServicingSupportService.requireIdempotencyKey(idempotencyKey);
        loanServicingSupportService.validateInstallmentPaymentInputs(amount, postedAt, channel);

        // Ownership before replay: an unknown application fails here, so reusing someone else's
        // key can never reveal that a receipt exists on a loan the caller cannot reach.
        UUID loanAccountId = loanServicingSupportService.getRequiredLoanAccount(applicationId).getId();
        PaymentIdempotencyFingerprint paymentRequest = new PaymentIdempotencyFingerprint(
                applicationId,
                targetInstallmentId,
                loanServicingSupportService.scaleCurrency(amount),
                postedAt,
                loanServicingSupportService.normalizeReference(reference),
                channel
        );

        // Replay before eligibility: the final receipt closes the loan, so re-checking whether the
        // loan still accepts payments first would reject the legitimate retry of a payment that
        // already succeeded.
        Optional<LoanPaymentTransaction> committedReceipt = loanPaymentTransactionRepository
                .findFirstByIdempotencyKeyOrderByCreatedAtAsc(normalizedIdempotencyKey);
        if (committedReceipt.isPresent()) {
            return resolveExistingPayment(committedReceipt.get(), loanAccountId, paymentRequest);
        }

        return transactionTemplate.execute(status -> createInstallmentPayment(
                applicationId,
                actorUsername,
                normalizedIdempotencyKey,
                paymentRequest,
                targetInstallmentId,
                amount,
                postedAt,
                reference,
                channel
        ));
    }

    private LoanPaymentTransaction createInstallmentPayment(
            UUID applicationId,
            String actorUsername,
            String idempotencyKey,
            PaymentIdempotencyFingerprint paymentRequest,
            UUID targetInstallmentId,
            BigDecimal amount,
            LocalDate postedAt,
            String reference,
            LoanPaymentChannel channel
    ) {
        // Shared loan-command lock order (application → account → installment). The locked rows are
        // the state this command decides on, so they are read here rather than carried in from the
        // caller's earlier, unlocked read.
        LoanAccount loanAccount = loanServicingSupportService.lockLoanForUpdate(applicationId);

        // Re-read under the lock: a request carrying this key may have committed while this one
        // waited for it. Settling that here returns the same receipt without spending a rollback
        // on the unique key and a whole retry to reach the same answer.
        Optional<LoanPaymentTransaction> committedReceipt = loanPaymentTransactionRepository
                .findFirstByIdempotencyKeyOrderByCreatedAtAsc(idempotencyKey);
        if (committedReceipt.isPresent()) {
            return resolveExistingPayment(committedReceipt.get(), loanAccount.getId(), paymentRequest);
        }

        LoanApplication application = loanAccount.getLoanApplication();
        loanServicingSupportService.validateRepaymentEligibility(application, loanAccount);

        LoanRepaymentScheduleInstallment installment = loanServicingSupportService.resolveTargetInstallmentForUpdate(
                loanAccount,
                targetInstallmentId
        );
        BigDecimal normalizedAmount = loanServicingSupportService.validateExactInstallmentAmount(installment, amount);

        String normalizedActorUsername = loanServicingSupportService.normalizeActorUsername(actorUsername);
        LoanPaymentTransaction paymentTransaction = idempotencyClaimService.claimLoanPaymentRow(new LoanPaymentTransaction(
                loanAccount,
                installment,
                normalizedActorUsername,
                normalizedAmount,
                postedAt,
                loanServicingSupportService.normalizeReference(reference),
                channel,
                LoanPaymentStatus.RECEIVED,
                null,
                CorrelationIdHolder.get(),
                idempotencyKey,
                IdempotencyFingerprinter.fingerprint(objectMapper, paymentRequest)
        ));

        loanServicingSupportService.applyFullInstallmentPayment(installment, normalizedAmount);
        loanRepaymentScheduleInstallmentRepository.save(installment);
        paymentTransaction.updateAllocation(normalizedAmount, BigDecimal.ZERO.setScale(2));
        LoanPaymentTransaction savedPaymentTransaction = loanPaymentTransactionRepository.save(paymentTransaction);

        boolean wasFullyRepaid = loanAccount.getClosureReason() == LoanAccountClosureReason.FULLY_REPAID;
        loanServicingSupportService.synchronizeLoanAccountClosureState(
                application,
                loanAccount,
                normalizedActorUsername,
                LoanAccountClosureReason.FULLY_REPAID
        );
        transitionToUnderRepaymentIfNeeded(
                application,
                actorUsername,
                "Loan moved under repayment after the first posted payment."
        );
        recordPaymentAudit(application, savedPaymentTransaction, installment, idempotencyKey);
        appendRepaymentEvent(application, loanAccount, savedPaymentTransaction);
        appendFullyRepaidEventIfClosed(application, loanAccount, wasFullyRepaid);
        return savedPaymentTransaction;
    }

    private LoanPaymentTransaction resolveExistingPayment(
            LoanPaymentTransaction existing,
            UUID expectedLoanAccountId,
            PaymentIdempotencyFingerprint paymentRequest
    ) {
        if (!existing.getLoanAccount().getId().equals(expectedLoanAccountId)) {
            throw new ApiConflictException(
                    "IDEMPOTENCY_CONFLICT",
                    "Idempotency-Key has already been used for a different loan application."
            );
        }
        String storedFingerprint = existing.getRequestFingerprint();
        boolean samePayload = storedFingerprint == null
                ? matchesReceiptFields(existing, paymentRequest)
                : storedFingerprint.equals(IdempotencyFingerprinter.fingerprint(objectMapper, paymentRequest));
        if (!samePayload) {
            throw new ApiConflictException(
                    "IDEMPOTENCY_CONFLICT",
                    "Idempotency-Key has already been used for a different request."
            );
        }
        return existing;
    }

    /**
     * Receipts written before request fingerprints were stored carry none, so their payload is
     * compared field by field instead; the request is already normalized the way the receipt was.
     */
    private static boolean matchesReceiptFields(
            LoanPaymentTransaction existing,
            PaymentIdempotencyFingerprint paymentRequest
    ) {
        LoanRepaymentScheduleInstallment installment = existing.getRepaymentInstallment();
        return installment != null
                && installment.getId().equals(paymentRequest.targetInstallmentId())
                && existing.getAmount().compareTo(paymentRequest.amount()) == 0
                && existing.getPaymentDate().equals(paymentRequest.postedAt())
                && Objects.equals(existing.getReference(), paymentRequest.reference())
                && existing.getChannel() == paymentRequest.channel();
    }

    private void recordPaymentAudit(
            LoanApplication application,
            LoanPaymentTransaction paymentTransaction,
            LoanRepaymentScheduleInstallment installment,
            String idempotencyKey
    ) {
        String note = "Installment "
                + installment.getInstallmentNumber()
                + " paid via "
                + paymentTransaction.getChannel().name()
                + (paymentTransaction.getReference() == null
                ? " (reference pending)."
                : " (ref " + paymentTransaction.getReference() + ").")
                + " [idem=" + idempotencyKey + "]";

        loanApplicationStatusWriter.recordAuditEvent(
                application,
                LoanApplicationAuditAction.PAYMENT_RECORDED,
                application.getStatus(),
                application.getStatus(),
                paymentTransaction.getActorUsername(),
                note,
                null
        );
    }

    private void transitionToUnderRepaymentIfNeeded(
            LoanApplication application,
            String actorUsername,
            String note
    ) {
        if (application.getStatus() == LoanApplicationStatus.DISBURSED) {
            loanApplicationStatusWriter.updateStatus(
                    application,
                    LoanApplicationStatusTransitionCommand.statusTransition(
                            LoanApplicationStatus.UNDER_REPAYMENT,
                            actorUsername,
                            note,
                            null,
                            LoanApplicationAuditAction.STATUS_TRANSITION
                    )
            );
        }
    }

    private void appendRepaymentEvent(
            LoanApplication application,
            LoanAccount loanAccount,
            LoanPaymentTransaction paymentTransaction
    ) {
        loanEventLog.append(
                application.getLsp(),
                LoanEventType.LOAN_REPAYMENT_RECORDED,
                "LOAN_PAYMENT_TRANSACTION",
                paymentTransaction.getId().toString(),
                application.getId(),
                LoanEventPayloads.repayment(application, loanAccount, paymentTransaction)
        );
    }

    private void appendFullyRepaidEventIfClosed(
            LoanApplication application,
            LoanAccount loanAccount,
            boolean wasFullyRepaid
    ) {
        if (wasFullyRepaid || loanAccount.getClosureReason() != LoanAccountClosureReason.FULLY_REPAID) {
            return;
        }

        loanEventLog.append(
                application.getLsp(),
                LoanEventType.LOAN_FULLY_REPAID,
                "LOAN_ACCOUNT",
                loanAccount.getId().toString(),
                application.getId(),
                LoanEventPayloads.loanFullyRepaid(application, loanAccount)
        );
    }

    private static boolean isPaymentIdempotencyKeyViolation(DataIntegrityViolationException exception) {
        // Hibernate's violation sits between Spring's wrapper and the driver's exception, so the
        // most specific cause is the driver's and never carries the parsed constraint name.
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof org.hibernate.exception.ConstraintViolationException constraintViolation) {
                String constraintName = constraintViolation.getConstraintName();
                return constraintName != null && constraintName.toLowerCase().replace("\"", "")
                        .contains("uk_loan_payment_transaction_idempotency_key");
            }
        }
        return false;
    }
}
