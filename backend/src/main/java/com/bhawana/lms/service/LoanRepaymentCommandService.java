package com.bhawana.lms.service;

import com.bhawana.lms.common.correlation.CorrelationIdHolder;
import com.bhawana.lms.common.web.ApiConflictException;
import com.bhawana.lms.domain.LoanAccount;
import com.bhawana.lms.domain.LoanAccountClosureReason;
import com.bhawana.lms.domain.LoanApplication;
import com.bhawana.lms.domain.LoanApplicationAuditAction;
import com.bhawana.lms.domain.LoanApplicationStatus;
import com.bhawana.lms.domain.LoanPaymentChannel;
import com.bhawana.lms.domain.LoanPaymentStatus;
import com.bhawana.lms.domain.LoanPaymentTransaction;
import com.bhawana.lms.domain.LoanRepaymentScheduleInstallment;
import com.bhawana.lms.domain.WebhookEventType;
import com.bhawana.lms.repo.LoanPaymentTransactionRepository;
import com.bhawana.lms.repo.LoanRepaymentScheduleInstallmentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LoanRepaymentCommandService {

    private final LoanPaymentTransactionRepository loanPaymentTransactionRepository;
    private final LoanRepaymentScheduleInstallmentRepository loanRepaymentScheduleInstallmentRepository;
    private final LoanServicingSupportService loanServicingSupportService;
    private final LoanApplicationLifecycleService loanApplicationLifecycleService;
    private final WebhookOutboxService webhookOutboxService;
    private final ObjectMapper objectMapper;
    private final IdempotencyClaimService idempotencyClaimService;

    public LoanRepaymentCommandService(
            LoanPaymentTransactionRepository loanPaymentTransactionRepository,
            LoanRepaymentScheduleInstallmentRepository loanRepaymentScheduleInstallmentRepository,
            LoanServicingSupportService loanServicingSupportService,
            LoanApplicationLifecycleService loanApplicationLifecycleService,
            WebhookOutboxService webhookOutboxService,
            ObjectMapper objectMapper,
            IdempotencyClaimService idempotencyClaimService
    ) {
        this.loanPaymentTransactionRepository = loanPaymentTransactionRepository;
        this.loanRepaymentScheduleInstallmentRepository = loanRepaymentScheduleInstallmentRepository;
        this.loanServicingSupportService = loanServicingSupportService;
        this.loanApplicationLifecycleService = loanApplicationLifecycleService;
        this.webhookOutboxService = webhookOutboxService;
        this.objectMapper = objectMapper;
        this.idempotencyClaimService = idempotencyClaimService;
    }

    @Transactional
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
        } catch (org.springframework.orm.ObjectOptimisticLockingFailureException exception) {
            return recoverPaymentAfterConcurrentWrite(
                    applicationId,
                    idempotencyKey,
                    targetInstallmentId,
                    amount,
                    postedAt,
                    reference,
                    channel
            );
        }
    }

    @Transactional
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

        LoanApplication application = loanServicingSupportService.getApplication(applicationId);
        LoanAccount loanAccount = loanServicingSupportService.getRequiredLoanAccount(applicationId);
        loanServicingSupportService.validateRepaymentEligibility(application, loanAccount);

        String requestFingerprint = fingerprintPaymentRequest(
                applicationId,
                targetInstallmentId,
                amount,
                postedAt,
                reference,
                channel
        );

        return loanPaymentTransactionRepository.findFirstByIdempotencyKeyOrderByCreatedAtAsc(normalizedIdempotencyKey)
                .map(existing -> resolveExistingPayment(existing, applicationId, requestFingerprint))
                .orElseGet(() -> createInstallmentPaymentWithClaim(
                        application,
                        loanAccount,
                        actorUsername,
                        normalizedIdempotencyKey,
                        requestFingerprint,
                        targetInstallmentId,
                        amount,
                        postedAt,
                        reference,
                        channel
                ));
    }

    private LoanPaymentTransaction createInstallmentPaymentWithClaim(
            LoanApplication application,
            LoanAccount loanAccount,
            String actorUsername,
            String idempotencyKey,
            String requestFingerprint,
            UUID targetInstallmentId,
            BigDecimal amount,
            LocalDate postedAt,
            String reference,
            LoanPaymentChannel channel
    ) {
        synchronized (idempotencyKey.intern()) {
            return loanPaymentTransactionRepository.findFirstByIdempotencyKeyOrderByCreatedAtAsc(idempotencyKey)
                    .map(existing -> resolveExistingPayment(existing, application.getId(), requestFingerprint))
                    .orElseGet(() -> createInstallmentPayment(
                            application,
                            loanAccount,
                            actorUsername,
                            idempotencyKey,
                            requestFingerprint,
                            targetInstallmentId,
                            amount,
                            postedAt,
                            reference,
                            channel
                    ));
        }
    }

    @Transactional(readOnly = true)
    public LoanPaymentTransaction recoverPaymentAfterConcurrentWrite(
            UUID applicationId,
            String idempotencyKey,
            UUID targetInstallmentId,
            BigDecimal amount,
            LocalDate postedAt,
            String reference,
            LoanPaymentChannel channel
    ) {
        String normalizedIdempotencyKey = loanServicingSupportService.requireIdempotencyKey(idempotencyKey);
        String requestFingerprint = fingerprintPaymentRequest(
                applicationId,
                targetInstallmentId,
                amount,
                postedAt,
                reference,
                channel
        );
        return loanPaymentTransactionRepository.findFirstByIdempotencyKeyOrderByCreatedAtAsc(normalizedIdempotencyKey)
                .map(existing -> resolveExistingPayment(existing, applicationId, requestFingerprint))
                .orElseThrow(() -> new IllegalStateException(
                        "Payment row missing after concurrent write for key " + normalizedIdempotencyKey
                ));
    }

    private LoanPaymentTransaction createInstallmentPayment(
            LoanApplication application,
            LoanAccount loanAccount,
            String actorUsername,
            String idempotencyKey,
            String requestFingerprint,
            UUID targetInstallmentId,
            BigDecimal amount,
            LocalDate postedAt,
            String reference,
            LoanPaymentChannel channel
    ) {
        LoanRepaymentScheduleInstallment installment = loanServicingSupportService.resolveTargetInstallment(
                loanAccount,
                targetInstallmentId
        );
        BigDecimal normalizedAmount = loanServicingSupportService.validateExactInstallmentAmount(installment, amount);

        String normalizedActorUsername = loanServicingSupportService.normalizeActorUsername(actorUsername);
        Optional<LoanPaymentTransaction> claimedPayment = idempotencyClaimService.claimLoanPaymentRow(new LoanPaymentTransaction(
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
                requestFingerprint
        ));
        if (claimedPayment.isEmpty()) {
            return loanPaymentTransactionRepository.findFirstByIdempotencyKeyOrderByCreatedAtAsc(idempotencyKey)
                    .map(existing -> resolveExistingPayment(existing, application.getId(), requestFingerprint))
                    .orElseThrow(() -> new IllegalStateException(
                            "Idempotency row missing after unique violation for key " + idempotencyKey
                    ));
        }

        LoanPaymentTransaction paymentTransaction = loanPaymentTransactionRepository.findById(claimedPayment.get().getId())
                .orElseThrow(() -> new IllegalStateException(
                        "Claimed payment row missing for key " + idempotencyKey
                ));

        LoanApplication applicationForUpdate = loanServicingSupportService.getApplication(application.getId());
        LoanAccount loanAccountForUpdate = loanServicingSupportService.getRequiredLoanAccount(application.getId());
        loanServicingSupportService.applyFullInstallmentPayment(installment, normalizedAmount);
        loanRepaymentScheduleInstallmentRepository.save(installment);
        paymentTransaction.updateAllocation(normalizedAmount, BigDecimal.ZERO.setScale(2));
        LoanPaymentTransaction savedPaymentTransaction = loanPaymentTransactionRepository.save(paymentTransaction);

        boolean wasFullyRepaid = loanAccountForUpdate.getClosureReason() == LoanAccountClosureReason.FULLY_REPAID;
        loanServicingSupportService.synchronizeLoanAccountClosureState(
                applicationForUpdate,
                loanAccountForUpdate,
                normalizedActorUsername,
                LoanAccountClosureReason.FULLY_REPAID
        );
        transitionToUnderRepaymentIfNeeded(
                applicationForUpdate,
                actorUsername,
                "Loan moved under repayment after the first posted payment."
        );
        recordPaymentAudit(applicationForUpdate, savedPaymentTransaction, installment, idempotencyKey);
        enqueueRepaymentWebhook(applicationForUpdate, loanAccountForUpdate, savedPaymentTransaction);
        enqueueFullyRepaidWebhookIfClosed(applicationForUpdate, loanAccountForUpdate, wasFullyRepaid);
        return savedPaymentTransaction;
    }

    private LoanPaymentTransaction resolveExistingPayment(
            LoanPaymentTransaction existing,
            UUID applicationId,
            String requestFingerprint
    ) {
        LoanAccount expectedAccount = loanServicingSupportService.getRequiredLoanAccount(applicationId);
        if (!existing.getLoanAccount().getId().equals(expectedAccount.getId())) {
            throw new ApiConflictException(
                    "IDEMPOTENCY_CONFLICT",
                    "Idempotency-Key has already been used for a different loan application."
            );
        }
        String storedFingerprint = existing.getRequestFingerprint();
        if (storedFingerprint != null && !storedFingerprint.equals(requestFingerprint)) {
            throw new ApiConflictException(
                    "IDEMPOTENCY_CONFLICT",
                    "Idempotency-Key has already been used for a different request."
            );
        }
        return existing;
    }

    private String fingerprintPaymentRequest(
            UUID applicationId,
            UUID targetInstallmentId,
            BigDecimal amount,
            LocalDate postedAt,
            String reference,
            LoanPaymentChannel channel
    ) {
        return IdempotencyFingerprinter.fingerprint(
                objectMapper,
                new PaymentIdempotencyFingerprint(
                        applicationId,
                        targetInstallmentId,
                        loanServicingSupportService.scaleCurrency(amount),
                        postedAt,
                        loanServicingSupportService.normalizeReference(reference),
                        channel
                )
        );
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

        loanApplicationLifecycleService.recordAuditEvent(
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
            loanApplicationLifecycleService.updateApplicationStatus(
                    application,
                    LoanApplicationStatus.UNDER_REPAYMENT,
                    actorUsername,
                    note,
                    null,
                    LoanApplicationAuditAction.STATUS_TRANSITION
            );
        }
    }

    private void enqueueRepaymentWebhook(
            LoanApplication application,
            LoanAccount loanAccount,
            LoanPaymentTransaction paymentTransaction
    ) {
        webhookOutboxService.enqueueIfSubscribed(
                application.getLsp(),
                WebhookEventType.LOAN_REPAYMENT_RECORDED,
                "LOAN_PAYMENT_TRANSACTION",
                paymentTransaction.getId().toString(),
                application.getId(),
                LoanWebhookPayloads.repayment(application, loanAccount, paymentTransaction)
        );
    }

    private void enqueueFullyRepaidWebhookIfClosed(
            LoanApplication application,
            LoanAccount loanAccount,
            boolean wasFullyRepaid
    ) {
        if (wasFullyRepaid || loanAccount.getClosureReason() != LoanAccountClosureReason.FULLY_REPAID) {
            return;
        }

        webhookOutboxService.enqueueIfSubscribed(
                application.getLsp(),
                WebhookEventType.LOAN_FULLY_REPAID,
                "LOAN_ACCOUNT",
                loanAccount.getId().toString(),
                application.getId(),
                LoanWebhookPayloads.loanFullyRepaid(application, loanAccount)
        );
    }
}
