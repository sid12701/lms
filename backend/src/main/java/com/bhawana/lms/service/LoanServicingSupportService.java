package com.bhawana.lms.service;

import com.bhawana.lms.domain.LoanAccount;
import com.bhawana.lms.domain.LoanAccountClosureReason;
import com.bhawana.lms.domain.LoanAccountStatus;
import com.bhawana.lms.common.web.ApiConflictException;
import com.bhawana.lms.common.web.BusinessRuleViolationException;
import com.bhawana.lms.common.web.ResourceNotFoundException;
import com.bhawana.lms.domain.LoanApplication;
import com.bhawana.lms.domain.LoanApplicationAuditAction;
import com.bhawana.lms.domain.LoanApplicationStatus;
import com.bhawana.lms.domain.LoanPaymentChannel;
import com.bhawana.lms.domain.LoanPaymentStatus;
import com.bhawana.lms.domain.LoanPaymentTransaction;
import com.bhawana.lms.domain.LoanRepaymentScheduleInstallment;
import com.bhawana.lms.domain.LoanRepaymentScheduleInstallmentStatus;
import com.bhawana.lms.repo.LoanAccountRepository;
import com.bhawana.lms.repo.LoanApplicationRepository;
import com.bhawana.lms.repo.LoanPaymentTransactionRepository;
import com.bhawana.lms.repo.LoanRepaymentScheduleInstallmentRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LoanServicingSupportService {

    private final LoanApplicationRepository loanApplicationRepository;
    private final LoanAccountRepository loanAccountRepository;
    private final LoanRepaymentScheduleInstallmentRepository loanRepaymentScheduleInstallmentRepository;
    private final LoanPaymentTransactionRepository loanPaymentTransactionRepository;
    private final LoanApplicationLifecycleService loanApplicationLifecycleService;

    public LoanServicingSupportService(
            LoanApplicationRepository loanApplicationRepository,
            LoanAccountRepository loanAccountRepository,
            LoanRepaymentScheduleInstallmentRepository loanRepaymentScheduleInstallmentRepository,
            LoanPaymentTransactionRepository loanPaymentTransactionRepository,
            LoanApplicationLifecycleService loanApplicationLifecycleService
    ) {
        this.loanApplicationRepository = loanApplicationRepository;
        this.loanAccountRepository = loanAccountRepository;
        this.loanRepaymentScheduleInstallmentRepository = loanRepaymentScheduleInstallmentRepository;
        this.loanPaymentTransactionRepository = loanPaymentTransactionRepository;
        this.loanApplicationLifecycleService = loanApplicationLifecycleService;
    }

    @Transactional(readOnly = true)
    public LoanApplication getApplication(UUID applicationId) {
        return loanApplicationRepository.findDetailedById(applicationId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown loan application id: " + applicationId));
    }

    @Transactional(readOnly = true)
    public LoanAccount getRequiredLoanAccount(UUID applicationId) {
        return loanAccountRepository.findDetailedByLoanApplication_Id(applicationId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Loan account is not available for application id: " + applicationId
                ));
    }

    @Transactional(readOnly = true)
    public LoanAccount getLoanAccountForLsp(UUID lspId, UUID loanAccountId) {
        LoanAccount loanAccount = loanAccountRepository.findDetailedById(loanAccountId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown loan id: " + loanAccountId));
        if (!loanAccount.getLsp().getId().equals(lspId)) {
            throw new IllegalArgumentException("Unknown loan id: " + loanAccountId);
        }
        return loanAccount;
    }

    public void validateInstallmentPaymentInputs(
            BigDecimal amount,
            LocalDate postedAt,
            LoanPaymentChannel channel
    ) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Payment amount must be greater than zero.");
        }
        if (postedAt == null) {
            throw new IllegalArgumentException("postedAt is required.");
        }
        if (channel == null) {
            throw new IllegalArgumentException("Payment channel is required.");
        }
    }

    public String requireIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.trim().isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key header is required.");
        }
        try {
            UUID uuid = UUID.fromString(idempotencyKey.trim());
            if (uuid.version() != 4) {
                throw new IllegalArgumentException("Idempotency-Key must be a UUID v4 value.");
            }
            return uuid.toString();
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Idempotency-Key must be a UUID v4 value.", exception);
        }
    }

    public void validateInstallmentNumber(int installmentNumber) {
        if (installmentNumber <= 0) {
            throw new IllegalArgumentException("Installment number must be greater than zero.");
        }
    }

    public void validateRepaymentEligibility(LoanApplication application, LoanAccount loanAccount) {
        if (application.getStatus() != LoanApplicationStatus.DISBURSED
                && application.getStatus() != LoanApplicationStatus.UNDER_REPAYMENT) {
            throw new IllegalArgumentException("Payments can only be recorded after a loan has been disbursed.");
        }
        if (loanAccount.getStatus() != LoanAccountStatus.DISBURSED) {
            throw new IllegalArgumentException("Payments can only be recorded after the loan account is disbursed.");
        }
    }

    @Transactional(readOnly = true)
    public LoanRepaymentScheduleInstallment resolveTargetInstallment(
            LoanAccount loanAccount,
            UUID targetInstallmentId
    ) {
        LoanRepaymentScheduleInstallment installment = loanRepaymentScheduleInstallmentRepository.findById(targetInstallmentId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Repayment installment not found: " + targetInstallmentId
                ));

        if (!installment.getLoanAccount().getId().equals(loanAccount.getId())) {
            throw new AccessDeniedException("Repayment installment does not belong to this loan application.");
        }

        if (installment.getStatus() == LoanRepaymentScheduleInstallmentStatus.PAID
                || installment.getOutstandingAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new ApiConflictException(
                    "INSTALLMENT_ALREADY_PAID",
                    "Installment " + installment.getInstallmentNumber() + " is already paid."
            );
        }

        return installment;
    }

    public BigDecimal validateExactInstallmentAmount(
            LoanRepaymentScheduleInstallment installment,
            BigDecimal amount
    ) {
        BigDecimal normalizedAmount = scaleCurrency(amount);
        BigDecimal dueAmount = scaleCurrency(installment.getOutstandingAmount());
        if (normalizedAmount.compareTo(dueAmount) != 0) {
            Map<String, String> fieldErrors = new LinkedHashMap<>();
            fieldErrors.put("amount", "Amount must exactly match installment due amount of " + dueAmount + ".");
            throw new BusinessRuleViolationException(
                    "PAYMENT_AMOUNT_MISMATCH",
                    "Payment amount must exactly match the installment due amount.",
                    fieldErrors
            );
        }
        return normalizedAmount;
    }

    public void applyFullInstallmentPayment(LoanRepaymentScheduleInstallment installment, BigDecimal amount) {
        BigDecimal applied = installment.applyPayment(amount);
        if (installment.getStatus() != LoanRepaymentScheduleInstallmentStatus.PAID
                || installment.getOutstandingAmount().compareTo(BigDecimal.ZERO) != 0) {
            throw new IllegalStateException("Installment payment did not fully settle the targeted installment.");
        }
        if (applied.compareTo(amount) != 0) {
            throw new IllegalStateException("Installment payment allocation mismatch.");
        }
    }

    @Transactional(readOnly = true)
    public LoanPaymentTransaction ensurePaymentBelongsToApplication(
            LoanPaymentTransaction payment,
            UUID applicationId
    ) {
        LoanAccount expectedAccount = getRequiredLoanAccount(applicationId);
        if (!payment.getLoanAccount().getId().equals(expectedAccount.getId())) {
            throw new ApiConflictException(
                    "IDEMPOTENCY_CONFLICT",
                    "Idempotency-Key has already been used for a different loan application."
            );
        }
        return payment;
    }

    public void recomputePaymentAllocation(LoanAccount loanAccount) {
        List<LoanRepaymentScheduleInstallment> installments = loanRepaymentScheduleInstallmentRepository
                .findByLoanAccount_IdOrderByInstallmentNumberAsc(loanAccount.getId());
        installments.forEach(LoanRepaymentScheduleInstallment::resetAllocation);

        List<LoanPaymentTransaction> payments = loanPaymentTransactionRepository
                .findByLoanAccount_IdOrderByPaymentDateAscCreatedAtAsc(loanAccount.getId());

        for (LoanPaymentTransaction payment : payments) {
            if (payment.getStatus() != LoanPaymentStatus.RECEIVED) {
                payment.updateAllocation(BigDecimal.ZERO.setScale(2), scaleCurrency(payment.getAmount()));
                continue;
            }

            BigDecimal remainingAmount = scaleCurrency(payment.getAmount());
            BigDecimal allocatedAmount = BigDecimal.ZERO.setScale(2);

            for (LoanRepaymentScheduleInstallment installment : installments) {
                if (remainingAmount.compareTo(BigDecimal.ZERO) <= 0) {
                    break;
                }
                if (installment.getStatus() == LoanRepaymentScheduleInstallmentStatus.PAID) {
                    continue;
                }

                BigDecimal appliedAmount = installment.applyPayment(remainingAmount);
                remainingAmount = scaleCurrency(remainingAmount.subtract(appliedAmount));
                allocatedAmount = scaleCurrency(allocatedAmount.add(appliedAmount));
            }

            payment.updateAllocation(allocatedAmount, remainingAmount);
        }

        loanRepaymentScheduleInstallmentRepository.saveAll(installments);
        loanPaymentTransactionRepository.saveAll(payments);
    }

    public void synchronizeLoanAccountClosureState(
            LoanApplication application,
            LoanAccount loanAccount,
            String actorUsername,
            LoanAccountClosureReason closureReason
    ) {
        if (!allInstallmentsSettled(loanAccount)) {
            return;
        }

        if (closureReason == LoanAccountClosureReason.FORECLOSURE) {
            loanAccount.close(LoanAccountClosureReason.FORECLOSURE, actorUsername, Instant.now());
            loanAccountRepository.save(loanAccount);
            loanApplicationLifecycleService.updateApplicationStatus(
                    application,
                    LoanApplicationStatus.FORECLOSED,
                    actorUsername,
                    "Loan foreclosed after settlement.",
                    null,
                    LoanApplicationAuditAction.STATUS_TRANSITION
            );
            return;
        }

        if (loanAccount.getStatus() == LoanAccountStatus.DISBURSED) {
            loanAccount.close(LoanAccountClosureReason.FULLY_REPAID, actorUsername, Instant.now());
            loanAccountRepository.save(loanAccount);
            loanApplicationLifecycleService.updateApplicationStatus(
                    application,
                    LoanApplicationStatus.CLOSED,
                    actorUsername,
                    "Loan closed after all installments were settled.",
                    null,
                    LoanApplicationAuditAction.STATUS_TRANSITION
            );
        }
    }

    public boolean allInstallmentsSettled(LoanAccount loanAccount) {
        return loanRepaymentScheduleInstallmentRepository.findByLoanAccount_IdOrderByInstallmentNumberAsc(loanAccount.getId())
                .stream()
                .allMatch(installment -> installment.getOutstandingAmount().compareTo(BigDecimal.ZERO) <= 0);
    }

    public BigDecimal scaleCurrency(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    public String normalizeActorUsername(String actorUsername) {
        if (actorUsername == null) {
            return "system";
        }

        String normalized = actorUsername.trim();
        return normalized.isBlank() ? "system" : normalized;
    }

    public String requireReference(String reference) {
        String normalized = normalizeReference(reference);
        if (normalized == null) {
            throw new IllegalArgumentException("Payment reference is required.");
        }
        return normalized;
    }

    public String normalizeReference(String reference) {
        String normalized = normalizeOptional(reference);
        if (normalized != null && normalized.length() > 128) {
            throw new IllegalArgumentException("Payment reference must be 128 characters or fewer.");
        }
        return normalized;
    }

    public String normalizeNote(String note) {
        return normalizeOptional(note);
    }

    private static String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isBlank() ? null : normalized;
    }
}
