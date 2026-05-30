package com.bhawana.lms.service;

import com.bhawana.lms.common.correlation.CorrelationIdHolder;
import com.bhawana.lms.domain.LoanAccount;
import com.bhawana.lms.domain.LoanAccountClosureReason;
import com.bhawana.lms.domain.LoanAccountStatus;
import com.bhawana.lms.domain.LoanApplication;
import com.bhawana.lms.domain.LoanApplicationAuditAction;
import com.bhawana.lms.domain.LoanApplicationStatus;
import com.bhawana.lms.domain.LoanForeclosureQuote;
import com.bhawana.lms.domain.LoanForeclosureQuoteStatus;
import com.bhawana.lms.domain.LoanPaymentChannel;
import com.bhawana.lms.domain.LoanPaymentStatus;
import com.bhawana.lms.domain.LoanPaymentTransaction;
import com.bhawana.lms.domain.LoanRepaymentScheduleInstallment;
import com.bhawana.lms.domain.WebhookEventType;
import com.bhawana.lms.repo.LoanForeclosureQuoteRepository;
import com.bhawana.lms.repo.LoanPaymentTransactionRepository;
import com.bhawana.lms.repo.LoanRepaymentScheduleInstallmentRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LoanForeclosureCommandService {

    private final LoanForeclosureQuoteRepository loanForeclosureQuoteRepository;
    private final LoanPaymentTransactionRepository loanPaymentTransactionRepository;
    private final LoanRepaymentScheduleInstallmentRepository loanRepaymentScheduleInstallmentRepository;
    private final LoanServicingSupportService loanServicingSupportService;
    private final LoanApplicationLifecycleService loanApplicationLifecycleService;
    private final WebhookOutboxService webhookOutboxService;

    public LoanForeclosureCommandService(
            LoanForeclosureQuoteRepository loanForeclosureQuoteRepository,
            LoanPaymentTransactionRepository loanPaymentTransactionRepository,
            LoanRepaymentScheduleInstallmentRepository loanRepaymentScheduleInstallmentRepository,
            LoanServicingSupportService loanServicingSupportService,
            LoanApplicationLifecycleService loanApplicationLifecycleService,
            WebhookOutboxService webhookOutboxService
    ) {
        this.loanForeclosureQuoteRepository = loanForeclosureQuoteRepository;
        this.loanPaymentTransactionRepository = loanPaymentTransactionRepository;
        this.loanRepaymentScheduleInstallmentRepository = loanRepaymentScheduleInstallmentRepository;
        this.loanServicingSupportService = loanServicingSupportService;
        this.loanApplicationLifecycleService = loanApplicationLifecycleService;
        this.webhookOutboxService = webhookOutboxService;
    }

    @Transactional
    public LoanForeclosureQuote requestForeclosureQuote(UUID applicationId, String actorUsername, LocalDate effectiveDate) {
        LoanApplication application = loanServicingSupportService.getApplication(applicationId);
        if (application.getStatus() != LoanApplicationStatus.DISBURSED
                && application.getStatus() != LoanApplicationStatus.UNDER_REPAYMENT) {
            throw new IllegalArgumentException("Foreclosure is only available after a loan has been disbursed.");
        }
        if (effectiveDate == null) {
            throw new IllegalArgumentException("Foreclosure effective date is required.");
        }

        LoanAccount loanAccount = loanServicingSupportService.getRequiredLoanAccount(applicationId);
        if (loanAccount.getStatus() != LoanAccountStatus.DISBURSED) {
            throw new IllegalArgumentException("Foreclosure quote can only be requested for an active disbursed loan account.");
        }

        List<LoanRepaymentScheduleInstallment> installments = loanRepaymentScheduleInstallmentRepository
                .findByLoanAccount_IdOrderByInstallmentNumberAsc(loanAccount.getId());
        BigDecimal outstandingPrincipal = installments.stream()
                .map(installment -> loanServicingSupportService.scaleCurrency(
                        installment.getPrincipalDue().subtract(installment.getPaidPrincipal()).max(BigDecimal.ZERO)
                ))
                .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
        BigDecimal outstandingInterest = installments.stream()
                .map(installment -> loanServicingSupportService.scaleCurrency(
                        installment.getInterestDue().subtract(installment.getPaidInterest()).max(BigDecimal.ZERO)
                ))
                .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
        BigDecimal settlementAmount = loanServicingSupportService.scaleCurrency(outstandingPrincipal.add(outstandingInterest));
        if (settlementAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Foreclosure quote is not available because the loan is already fully settled.");
        }

        List<LoanForeclosureQuote> existingQuotes = loanForeclosureQuoteRepository.findByLoanAccount_IdOrderByVersionDesc(
                loanAccount.getId()
        );
        existingQuotes.stream()
                .filter(quote -> quote.getStatus() == LoanForeclosureQuoteStatus.ACTIVE)
                .forEach(LoanForeclosureQuote::supersede);
        if (!existingQuotes.isEmpty()) {
            loanForeclosureQuoteRepository.saveAll(existingQuotes);
        }

        int nextVersion = existingQuotes.stream()
                .mapToInt(LoanForeclosureQuote::getVersion)
                .max()
                .orElse(0) + 1;

        LoanForeclosureQuote savedQuote = loanForeclosureQuoteRepository.save(new LoanForeclosureQuote(
                loanAccount,
                nextVersion,
                loanServicingSupportService.normalizeActorUsername(actorUsername),
                effectiveDate,
                loanServicingSupportService.scaleCurrency(outstandingPrincipal),
                loanServicingSupportService.scaleCurrency(outstandingInterest),
                settlementAmount
        ));
        webhookOutboxService.enqueueIfSubscribed(
                application.getLsp(),
                WebhookEventType.FORECLOSURE_QUOTE_REQUESTED,
                "LOAN_FORECLOSURE_QUOTE",
                savedQuote.getId().toString(),
                application.getId(),
                loanApplicationLifecycleService.buildForeclosureQuotePayload(application, loanAccount, savedQuote)
        );
        return savedQuote;
    }

    @Transactional
    public LoanForeclosureQuote requestForeclosureQuoteForLsp(
            UUID lspId,
            UUID loanAccountId,
            String actorUsername,
            LocalDate effectiveDate
    ) {
        LoanAccount loanAccount = loanServicingSupportService.getLoanAccountForLsp(lspId, loanAccountId);
        return requestForeclosureQuote(loanAccount.getLoanApplication().getId(), actorUsername, effectiveDate);
    }

    @Transactional
    public LoanForeclosureQuote executeForeclosureQuote(
            UUID applicationId,
            UUID quoteId,
            String actorUsername,
            LocalDate settlementDate,
            String reference,
            String note
    ) {
        if (settlementDate == null) {
            throw new IllegalArgumentException("Settlement date is required.");
        }

        LoanApplication application = loanServicingSupportService.getApplication(applicationId);
        LoanAccount loanAccount = loanServicingSupportService.getRequiredLoanAccount(applicationId);
        if (loanAccount.getStatus() != LoanAccountStatus.DISBURSED) {
            throw new IllegalArgumentException("Foreclosure can only be executed for an active disbursed loan account.");
        }

        LoanForeclosureQuote quote = loanForeclosureQuoteRepository.findById(quoteId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown foreclosure quote id: " + quoteId));
        if (!quote.getLoanAccount().getId().equals(loanAccount.getId())) {
            throw new IllegalArgumentException("Foreclosure quote does not belong to the selected loan account.");
        }
        if (quote.getStatus() != LoanForeclosureQuoteStatus.ACTIVE) {
            throw new IllegalArgumentException("Only an active foreclosure quote can be executed.");
        }
        if (!settlementDate.equals(quote.getEffectiveDate())) {
            throw new IllegalArgumentException("Settlement date must match the foreclosure quote effective date.");
        }

        String normalizedActorUsername = loanServicingSupportService.normalizeActorUsername(actorUsername);
        String requiredReference = loanServicingSupportService.requireReference(reference);
        String resolvedNote = loanServicingSupportService.normalizeNote(note);
        loanPaymentTransactionRepository.save(new LoanPaymentTransaction(
                loanAccount,
                null,
                normalizedActorUsername,
                quote.getSettlementAmount(),
                settlementDate,
                requiredReference,
                LoanPaymentChannel.FORECLOSURE_SETTLEMENT,
                LoanPaymentStatus.RECEIVED,
                resolvedNote == null ? "Foreclosure settlement for quote v" + quote.getVersion() : resolvedNote,
                CorrelationIdHolder.get(),
                null
        ));
        loanServicingSupportService.recomputePaymentAllocation(loanAccount);

        if (!loanServicingSupportService.allInstallmentsSettled(loanAccount)) {
            throw new IllegalStateException("Foreclosure settlement did not fully settle the repayment schedule.");
        }

        quote.execute(normalizedActorUsername);
        loanForeclosureQuoteRepository.save(quote);
        LoanApplicationStatus statusBeforeForeclosure = application.getStatus();
        loanServicingSupportService.synchronizeLoanAccountClosureState(
                application,
                loanAccount,
                normalizedActorUsername,
                LoanAccountClosureReason.FORECLOSURE
        );
        loanApplicationLifecycleService.recordAuditEvent(
                application,
                LoanApplicationAuditAction.FORECLOSURE_EXECUTED,
                statusBeforeForeclosure,
                application.getStatus(),
                actorUsername,
                "Foreclosure executed using quote v"
                        + quote.getVersion()
                        + " effective "
                        + quote.getEffectiveDate()
                        + " with settlement reference "
                        + requiredReference,
                null
        );

        webhookOutboxService.enqueueIfSubscribed(
                application.getLsp(),
                WebhookEventType.LOAN_FORECLOSURE_COMPLETED,
                "LOAN_ACCOUNT",
                loanAccount.getId().toString(),
                application.getId(),
                loanApplicationLifecycleService.buildForeclosurePayload(application, loanAccount, quote)
        );
        return quote;
    }
}
