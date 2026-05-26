package com.bhawana.lms.service;

import com.bhawana.lms.common.correlation.CorrelationIdHolder;
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
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
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

    public LoanRepaymentCommandService(
            LoanPaymentTransactionRepository loanPaymentTransactionRepository,
            LoanRepaymentScheduleInstallmentRepository loanRepaymentScheduleInstallmentRepository,
            LoanServicingSupportService loanServicingSupportService,
            LoanApplicationLifecycleService loanApplicationLifecycleService,
            WebhookOutboxService webhookOutboxService
    ) {
        this.loanPaymentTransactionRepository = loanPaymentTransactionRepository;
        this.loanRepaymentScheduleInstallmentRepository = loanRepaymentScheduleInstallmentRepository;
        this.loanServicingSupportService = loanServicingSupportService;
        this.loanApplicationLifecycleService = loanApplicationLifecycleService;
        this.webhookOutboxService = webhookOutboxService;
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

        return loanPaymentTransactionRepository.findByIdempotencyKey(normalizedIdempotencyKey)
                .map(existing -> loanServicingSupportService.ensurePaymentBelongsToApplication(existing, applicationId))
                .orElseGet(() -> createInstallmentPayment(
                        application,
                        loanAccount,
                        actorUsername,
                        normalizedIdempotencyKey,
                        targetInstallmentId,
                        amount,
                        postedAt,
                        reference,
                        channel
                ));
    }

    private LoanPaymentTransaction createInstallmentPayment(
            LoanApplication application,
            LoanAccount loanAccount,
            String actorUsername,
            String idempotencyKey,
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
        LoanPaymentTransaction paymentTransaction = loanPaymentTransactionRepository.save(new LoanPaymentTransaction(
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
                idempotencyKey
        ));

        loanServicingSupportService.applyFullInstallmentPayment(installment, normalizedAmount);
        loanRepaymentScheduleInstallmentRepository.save(installment);
        paymentTransaction.updateAllocation(normalizedAmount, BigDecimal.ZERO.setScale(2));
        LoanPaymentTransaction savedPaymentTransaction = loanPaymentTransactionRepository.save(paymentTransaction);

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
        enqueueRepaymentWebhook(application, loanAccount, savedPaymentTransaction);
        return savedPaymentTransaction;
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
                loanApplicationLifecycleService.buildRepaymentPayload(application, loanAccount, paymentTransaction)
        );
    }
}
