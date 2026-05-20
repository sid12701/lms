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
import com.bhawana.lms.domain.WebhookEventType;
import com.bhawana.lms.repo.LoanPaymentTransactionRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LoanRepaymentCommandService {

    private final LoanPaymentTransactionRepository loanPaymentTransactionRepository;
    private final LoanServicingSupportService loanServicingSupportService;
    private final LoanApplicationLifecycleService loanApplicationLifecycleService;
    private final WebhookOutboxService webhookOutboxService;

    public LoanRepaymentCommandService(
            LoanPaymentTransactionRepository loanPaymentTransactionRepository,
            LoanServicingSupportService loanServicingSupportService,
            LoanApplicationLifecycleService loanApplicationLifecycleService,
            WebhookOutboxService webhookOutboxService
    ) {
        this.loanPaymentTransactionRepository = loanPaymentTransactionRepository;
        this.loanServicingSupportService = loanServicingSupportService;
        this.loanApplicationLifecycleService = loanApplicationLifecycleService;
        this.webhookOutboxService = webhookOutboxService;
    }

    @Transactional
    public LoanPaymentTransaction recordPaymentTransaction(
            UUID applicationId,
            String actorUsername,
            BigDecimal amount,
            LocalDate paymentDate,
            String reference,
            LoanPaymentChannel channel,
            LoanPaymentStatus status,
            String note
    ) {
        loanServicingSupportService.validatePaymentInputs(amount, paymentDate, channel, status);

        LoanApplication application = loanServicingSupportService.getApplication(applicationId);
        LoanAccount loanAccount = loanServicingSupportService.getRequiredLoanAccount(applicationId);
        loanServicingSupportService.validateRepaymentEligibility(application, loanAccount);
        BigDecimal normalizedAmount = loanServicingSupportService.validateAndResolveInstallmentPaymentAmount(
                loanAccount,
                amount
        );

        String normalizedActorUsername = loanServicingSupportService.normalizeActorUsername(actorUsername);
        LoanPaymentTransaction paymentTransaction = loanPaymentTransactionRepository.save(new LoanPaymentTransaction(
                loanAccount,
                normalizedActorUsername,
                normalizedAmount,
                paymentDate,
                loanServicingSupportService.requireReference(reference),
                channel,
                status,
                loanServicingSupportService.normalizeNote(note),
                CorrelationIdHolder.get()
        ));

        loanServicingSupportService.recomputePaymentAllocation(loanAccount);
        LoanPaymentTransaction savedPaymentTransaction = loanPaymentTransactionRepository.findById(paymentTransaction.getId())
                .orElse(paymentTransaction);
        loanServicingSupportService.synchronizeLoanAccountClosureState(
                application,
                loanAccount,
                normalizedActorUsername,
                LoanAccountClosureReason.FULLY_REPAID
        );
        transitionToUnderRepaymentIfNeeded(application, actorUsername, "Loan moved under repayment after the first posted payment.");
        enqueueRepaymentWebhook(application, loanAccount, savedPaymentTransaction);
        return savedPaymentTransaction;
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
                loanApplicationLifecycleService.buildRepaymentPayload(application, loanAccount, paymentTransaction)
        );
    }
}
