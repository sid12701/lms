package com.bhawana.lms.service;

import com.bhawana.lms.common.correlation.CorrelationIdHolder;
import com.bhawana.lms.config.BusinessCalendar;
import com.bhawana.lms.domain.LoanAccount;
import com.bhawana.lms.domain.LoanAccountClosureReason;
import com.bhawana.lms.domain.LoanAccountStatus;
import com.bhawana.lms.common.api.error.ApiConflictException;
import com.bhawana.lms.common.api.error.BusinessRuleViolationException;
import com.bhawana.lms.common.api.error.ResourceNotFoundException;
import com.bhawana.lms.domain.LoanApplication;
import com.bhawana.lms.domain.LoanApplicationAuditAction;
import com.bhawana.lms.domain.LoanApplicationStatus;
import com.bhawana.lms.domain.LoanForeclosureQuote;
import com.bhawana.lms.domain.LoanForeclosureQuoteStatus;
import com.bhawana.lms.domain.LoanPaymentChannel;
import com.bhawana.lms.domain.LoanPaymentTransaction;
import com.bhawana.lms.domain.LoanRepaymentScheduleInstallment;
import com.bhawana.lms.domain.LoanEventType;
import com.bhawana.lms.repo.LoanAccountRepository;
import com.bhawana.lms.repo.LoanApplicationRepository;
import com.bhawana.lms.repo.LoanForeclosureQuoteRepository;
import com.bhawana.lms.repo.LoanPaymentTransactionRepository;
import com.bhawana.lms.repo.LoanRepaymentScheduleInstallmentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LoanForeclosureCommandService {

    private final LoanApplicationRepository loanApplicationRepository;
    private final LoanAccountRepository loanAccountRepository;
    private final LoanForeclosureQuoteRepository loanForeclosureQuoteRepository;
    private final LoanPaymentTransactionRepository loanPaymentTransactionRepository;
    private final LoanRepaymentScheduleInstallmentRepository loanRepaymentScheduleInstallmentRepository;
    private final LoanServicingSupportService loanServicingSupportService;
    private final LoanApplicationStatusWriter loanApplicationStatusWriter;
    private final LoanEventLog loanEventLog;
    private final OpsAlertEmitters opsAlertEmitters;
    private final BusinessCalendar businessCalendar;
    private final ObjectMapper objectMapper;

    public LoanForeclosureCommandService(
            LoanApplicationRepository loanApplicationRepository,
            LoanAccountRepository loanAccountRepository,
            LoanForeclosureQuoteRepository loanForeclosureQuoteRepository,
            LoanPaymentTransactionRepository loanPaymentTransactionRepository,
            LoanRepaymentScheduleInstallmentRepository loanRepaymentScheduleInstallmentRepository,
            LoanServicingSupportService loanServicingSupportService,
            LoanApplicationStatusWriter loanApplicationStatusWriter,
            LoanEventLog loanEventLog,
            OpsAlertEmitters opsAlertEmitters,
            BusinessCalendar businessCalendar,
            ObjectMapper objectMapper
    ) {
        this.loanApplicationRepository = loanApplicationRepository;
        this.loanAccountRepository = loanAccountRepository;
        this.loanForeclosureQuoteRepository = loanForeclosureQuoteRepository;
        this.loanPaymentTransactionRepository = loanPaymentTransactionRepository;
        this.loanRepaymentScheduleInstallmentRepository = loanRepaymentScheduleInstallmentRepository;
        this.loanServicingSupportService = loanServicingSupportService;
        this.loanApplicationStatusWriter = loanApplicationStatusWriter;
        this.loanEventLog = loanEventLog;
        this.opsAlertEmitters = opsAlertEmitters;
        this.businessCalendar = businessCalendar;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public LoanForeclosureQuote requestForeclosureQuote(UUID applicationId, String actorUsername, LocalDate effectiveDate) {
        if (effectiveDate == null) {
            throw new IllegalArgumentException("Foreclosure effective date is required.");
        }
        requireCurrentBusinessDate(effectiveDate);

        LoanApplication application = lockApplication(applicationId);
        if (application.getStatus() != LoanApplicationStatus.DISBURSED
                && application.getStatus() != LoanApplicationStatus.UNDER_REPAYMENT) {
            throw new BusinessRuleViolationException(
                    "FORECLOSURE_NOT_ALLOWED",
                    "Foreclosure is only available after a loan has been disbursed.",
                    Map.of("status", application.getStatus().name())
            );
        }

        LockedLoan loan = lockAccountAndSchedule(applicationId);
        LoanAccount loanAccount = loan.loanAccount();
        if (loanAccount.getStatus() != LoanAccountStatus.DISBURSED) {
            throw new BusinessRuleViolationException(
                    "LOAN_ACCOUNT_NOT_DISBURSED",
                    "Foreclosure quote can only be requested for an active disbursed loan account.",
                    Map.of("loanAccountStatus", loanAccount.getStatus().name())
            );
        }

        SettlementBalance balance = settlementBalance(loan.installments());
        if (balance.settlementAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new ApiConflictException(
                    "LOAN_ALREADY_SETTLED",
                    "Foreclosure quote is not available because the loan is already fully settled."
            );
        }

        // Under the account lock, so concurrent requests cannot leave two ACTIVE quotes behind.
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
                balance.outstandingPrincipal(),
                balance.outstandingInterest(),
                balance.settlementAmount()
        ));
        loanEventLog.append(
                application.getLsp(),
                LoanEventType.FORECLOSURE_QUOTE_REQUESTED,
                "LOAN_FORECLOSURE_QUOTE",
                savedQuote.getId().toString(),
                application.getId(),
                LoanEventPayloads.foreclosureQuote(application, loanAccount, savedQuote)
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
        return requestForeclosureQuote(applicationIdForLsp(lspId, loanAccountId), actorUsername, effectiveDate);
    }

    @Transactional
    public LoanForeclosureQuote executeForeclosureQuoteForLsp(
            UUID lspId,
            UUID loanAccountId,
            UUID quoteId,
            String actorUsername,
            LocalDate settlementDate,
            String reference,
            String note
    ) {
        UUID applicationId = applicationIdForLsp(lspId, loanAccountId);
        try {
            return executeForeclosureQuote(
                    applicationId,
                    quoteId,
                    actorUsername,
                    settlementDate,
                    reference,
                    note
            );
        } catch (ResourceNotFoundException exception) {
            throw exception;
        } catch (BusinessRuleViolationException | ApiConflictException | IllegalArgumentException | IllegalStateException exception) {
            ForeclosureViolationType violationType = resolveForeclosureViolationType(exception);
            Map<String, String> details = new LinkedHashMap<>();
            details.put("quoteId", quoteId.toString());
            if (settlementDate != null) {
                details.put("settlementDate", settlementDate.toString());
            }
            if (reference != null) {
                details.put("reference", reference);
            }
            opsAlertEmitters.emitLspForeclosureViolation(
                    loanServicingSupportService.getApplication(applicationId),
                    violationType,
                    exception.getMessage(),
                    details
            );
            Map<String, String> fieldErrors = new LinkedHashMap<>();
            fieldErrors.put("violationType", violationType.name());
            throw new BusinessRuleViolationException(
                    "LSP_BOUND_VIOLATION",
                    exception.getMessage(),
                    fieldErrors
            );
        }
    }

    /**
     * Ownership, then replay resolution, then new-execution eligibility and balance freshness,
     * then the write — all under the loan lock taken in the shared application → account →
     * installments → quote order.
     *
     * <p>An already-executed quote resolves to its committed settlement when the request matches
     * the one that settled it, and to {@code IDEMPOTENCY_CONFLICT} otherwise. This is decided from
     * the settlement receipt itself, so it holds for direct callers and outlives any HTTP
     * idempotency-cache retention.
     *
     * <p>Freshness is the quote's own stored snapshot used as a balance fingerprint: the quote is
     * only redeemable while the schedule still owes exactly what it quoted. Any receipt, reversal
     * or schedule change in between makes it stale, and the caller must request a new quote. The
     * quote and the schedule are read for the first time inside the lock, so the comparison never
     * runs against balances another transaction has already moved.
     */
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

        LoanApplication application = lockApplication(applicationId);
        LockedLoan loan = lockAccountAndSchedule(applicationId);
        LoanAccount loanAccount = loan.loanAccount();

        LoanForeclosureQuote quote = loanForeclosureQuoteRepository.findByIdForUpdate(quoteId)
                .orElseThrow(() -> new ResourceNotFoundException("Unknown foreclosure quote id: " + quoteId));
        if (!quote.getLoanAccount().getId().equals(loanAccount.getId())) {
            throw new ResourceNotFoundException("Foreclosure quote does not belong to the selected loan account.");
        }
        String requiredReference = loanServicingSupportService.requireReference(reference);
        String requestFingerprint = fingerprintSettlementRequest(quoteId, settlementDate, requiredReference);
        if (quote.getStatus() == LoanForeclosureQuoteStatus.EXECUTED) {
            Optional<LoanPaymentTransaction> settlement = loanPaymentTransactionRepository.findByForeclosureQuote_Id(quoteId);
            if (settlement.isPresent()) {
                return replayExecutedQuote(quote, settlement.get(), requestFingerprint);
            }
        }
        // A quote leaves ACTIVE exactly once. The partial unique index on the settlement receipt's
        // quote link is the backstop: one quote can never back two settlements.
        if (quote.getStatus() != LoanForeclosureQuoteStatus.ACTIVE) {
            throw new BusinessRuleViolationException(
                    "QUOTE_NOT_ACTIVE",
                    "Only an active foreclosure quote can be executed.",
                    Map.of("quoteId", quoteId.toString())
            );
        }
        if (loanAccount.getStatus() != LoanAccountStatus.DISBURSED) {
            throw new BusinessRuleViolationException(
                    "LOAN_ACCOUNT_NOT_DISBURSED",
                    "Foreclosure can only be executed for an active disbursed loan account.",
                    Map.of("loanAccountStatus", loanAccount.getStatus().name())
            );
        }
        if (!settlementDate.equals(quote.getEffectiveDate())) {
            throw new BusinessRuleViolationException(
                    "SETTLEMENT_DATE_MISMATCH",
                    "Settlement date must match the foreclosure quote effective date.",
                    Map.of(
                            "settlementDate", settlementDate.toString(),
                            "effectiveDate", quote.getEffectiveDate().toString()
                    )
            );
        }
        requireCurrentBusinessDate(quote.getEffectiveDate());
        requireFreshQuote(quote, settlementBalance(loan.installments()));

        String normalizedActorUsername = loanServicingSupportService.normalizeActorUsername(actorUsername);
        String resolvedNote = loanServicingSupportService.normalizeNote(note);
        LoanPaymentTransaction settlement = loanPaymentTransactionRepository.save(
                LoanPaymentTransaction.foreclosureSettlement(
                        loanAccount,
                        quote,
                        normalizedActorUsername,
                        settlementDate,
                        requiredReference,
                        resolvedNote == null ? "Foreclosure settlement for quote v" + quote.getVersion() : resolvedNote,
                        CorrelationIdHolder.get(),
                        requestFingerprint
                )
        );
        loanServicingSupportService.allocateReceiptAcrossOutstanding(loan.installments(), settlement);

        // Invariant, not a control path: the freshness check above already proved the quote pays
        // out exactly what the schedule owes.
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
        loanApplicationStatusWriter.recordAuditEvent(
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

        loanEventLog.append(
                application.getLsp(),
                LoanEventType.LOAN_FORECLOSURE_COMPLETED,
                "LOAN_ACCOUNT",
                loanAccount.getId().toString(),
                application.getId(),
                LoanEventPayloads.foreclosure(application, loanAccount, quote)
        );
        return quote;
    }

    // LSP scope without loading the account: the command's locked reads must be its first sight
    // of the loan, or a concurrent commit leaves a stale versioned entity in this transaction.
    private UUID applicationIdForLsp(UUID lspId, UUID loanAccountId) {
        return loanAccountRepository.findLoanApplicationIdForLsp(loanAccountId, lspId)
                .orElseThrow(() -> new ResourceNotFoundException("Unknown loan id: " + loanAccountId));
    }

    // Shared foreclosure lock order: application → account → installments in installment-number
    // order → quote (execution only). Every foreclosure command takes the same rows in the same
    // sequence, and each locked read is the command's first sight of that state.
    private LoanApplication lockApplication(UUID applicationId) {
        loanApplicationRepository.findByIdForUpdate(applicationId)
                .orElseThrow(() -> new ResourceNotFoundException("Unknown loan application id: " + applicationId));
        return loanServicingSupportService.getApplication(applicationId);
    }

    private LockedLoan lockAccountAndSchedule(UUID applicationId) {
        loanAccountRepository.findByLoanApplication_IdForUpdate(applicationId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Loan account is not available for application id: " + applicationId
                ));
        LoanAccount loanAccount = loanServicingSupportService.getRequiredLoanAccount(applicationId);
        return new LockedLoan(
                loanAccount,
                loanRepaymentScheduleInstallmentRepository.findByLoanAccountIdForUpdateOrderByInstallmentNumberAsc(
                        loanAccount.getId()
                )
        );
    }

    /**
     * Interim validity policy until the H08 pricing policy lands: a quote is issued for, and
     * redeemable on, the current business date only. It is not an expiry window — there is none
     * yet — so anything else fails closed.
     */
    private void requireCurrentBusinessDate(LocalDate effectiveDate) {
        LocalDate businessDate = businessCalendar.today();
        if (!effectiveDate.equals(businessDate)) {
            throw new BusinessRuleViolationException(
                    "FORECLOSURE_QUOTE_DATE_INVALID",
                    "Foreclosure quotes are only valid on the current business date. Request a new quote.",
                    Map.of(
                            "effectiveDate", effectiveDate.toString(),
                            "businessDate", businessDate.toString()
                    )
            );
        }
    }

    private LoanForeclosureQuote replayExecutedQuote(
            LoanForeclosureQuote quote,
            LoanPaymentTransaction settlement,
            String requestFingerprint
    ) {
        if (!requestFingerprint.equals(settlement.getRequestFingerprint())) {
            throw new ApiConflictException(
                    "IDEMPOTENCY_CONFLICT",
                    "This foreclosure quote was already executed by a different request."
            );
        }
        return quote;
    }

    private String fingerprintSettlementRequest(UUID quoteId, LocalDate settlementDate, String reference) {
        return IdempotencyFingerprinter.fingerprint(
                objectMapper,
                new SettlementRequestFingerprint(
                        quoteId,
                        settlementDate,
                        reference,
                        LoanPaymentChannel.FORECLOSURE_SETTLEMENT
                )
        );
    }

    private SettlementBalance settlementBalance(List<LoanRepaymentScheduleInstallment> installments) {
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
        return new SettlementBalance(
                outstandingPrincipal,
                outstandingInterest,
                loanServicingSupportService.scaleCurrency(outstandingPrincipal.add(outstandingInterest))
        );
    }

    private static void requireFreshQuote(LoanForeclosureQuote quote, SettlementBalance current) {
        if (current.settlementAmount().compareTo(quote.getSettlementAmount()) == 0
                && current.outstandingPrincipal().compareTo(quote.getOutstandingPrincipal()) == 0
                && current.outstandingInterest().compareTo(quote.getOutstandingInterest()) == 0) {
            return;
        }
        Map<String, String> details = new LinkedHashMap<>();
        details.put("quoteId", quote.getId().toString());
        details.put("quotedSettlementAmount", quote.getSettlementAmount().toPlainString());
        details.put("currentSettlementAmount", current.settlementAmount().toPlainString());
        throw new BusinessRuleViolationException(
                "FORECLOSURE_QUOTE_STALE",
                "The loan balance changed after this foreclosure quote was issued. Request a new quote.",
                details
        );
    }

    private record LockedLoan(
            LoanAccount loanAccount,
            List<LoanRepaymentScheduleInstallment> installments
    ) {
    }

    private record SettlementRequestFingerprint(
            UUID quoteId,
            LocalDate settlementDate,
            String reference,
            LoanPaymentChannel channel
    ) {
    }

    private record SettlementBalance(
            BigDecimal outstandingPrincipal,
            BigDecimal outstandingInterest,
            BigDecimal settlementAmount
    ) {
    }

    private static ForeclosureViolationType resolveForeclosureViolationType(RuntimeException exception) {
        if (exception instanceof BusinessRuleViolationException businessRuleViolation) {
            try {
                return ForeclosureViolationType.valueOf(businessRuleViolation.getErrorCode());
            } catch (IllegalArgumentException ignored) {
                return ForeclosureViolationType.FORECLOSURE_GENERIC;
            }
        }
        if (exception instanceof ApiConflictException conflict) {
            return "IDEMPOTENCY_CONFLICT".equals(conflict.getErrorCode())
                    ? ForeclosureViolationType.IDEMPOTENCY_CONFLICT
                    : ForeclosureViolationType.FORECLOSURE_GENERIC;
        }
        String message = exception.getMessage();
        if (message == null) {
            return ForeclosureViolationType.FORECLOSURE_GENERIC;
        }
        if (message.startsWith("Only an active foreclosure quote")) {
            return ForeclosureViolationType.QUOTE_NOT_ACTIVE;
        }
        if (message.startsWith("Foreclosure quote does not belong")
                || message.startsWith("Unknown foreclosure quote id")) {
            return ForeclosureViolationType.QUOTE_OWNERSHIP_MISMATCH;
        }
        if (message.startsWith("Settlement date must match")) {
            return ForeclosureViolationType.SETTLEMENT_DATE_MISMATCH;
        }
        if (message.startsWith("Foreclosure can only be executed for an active disbursed loan account")) {
            return ForeclosureViolationType.LOAN_ACCOUNT_NOT_DISBURSED;
        }
        if (message.startsWith("Payment reference is required")) {
            return ForeclosureViolationType.REFERENCE_MISSING;
        }
        if (message.startsWith("Foreclosure settlement did not fully settle")) {
            return ForeclosureViolationType.SETTLEMENT_INCOMPLETE;
        }
        return ForeclosureViolationType.FORECLOSURE_GENERIC;
    }
}
