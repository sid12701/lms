package com.bhawana.lms.service;

import com.bhawana.lms.common.web.BusinessRuleViolationException;
import com.bhawana.lms.domain.LoanAccount;
import com.bhawana.lms.domain.LoanAccountStatus;
import com.bhawana.lms.domain.LoanApplication;
import com.bhawana.lms.domain.LoanRepaymentScheduleInstallment;
import com.bhawana.lms.repo.LoanPaymentTransactionRepository;
import com.bhawana.lms.repo.LoanRepaymentScheduleInstallmentRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LoanRepaymentScheduleService {

    private final LoanApplicationService loanApplicationService;
    private final LoanRepaymentScheduleInstallmentRepository loanRepaymentScheduleInstallmentRepository;
    private final LoanPaymentTransactionRepository loanPaymentTransactionRepository;
    private final AlertRuleEvaluationService alertRuleEvaluationService;

    public LoanRepaymentScheduleService(
            LoanApplicationService loanApplicationService,
            LoanRepaymentScheduleInstallmentRepository loanRepaymentScheduleInstallmentRepository,
            LoanPaymentTransactionRepository loanPaymentTransactionRepository,
            AlertRuleEvaluationService alertRuleEvaluationService
    ) {
        this.loanApplicationService = loanApplicationService;
        this.loanRepaymentScheduleInstallmentRepository = loanRepaymentScheduleInstallmentRepository;
        this.loanPaymentTransactionRepository = loanPaymentTransactionRepository;
        this.alertRuleEvaluationService = alertRuleEvaluationService;
    }

    @Transactional
    public List<LoanRepaymentScheduleInstallment> replaceWithGeneratedScheduleForLsp(UUID lspId, UUID applicationId) {
        LoanAccount loanAccount = getMutableLoanAccountForLsp(lspId, applicationId);
        List<LoanRepaymentScheduleInstallment> generated = buildGeneratedInstallments(loanAccount);
        loanRepaymentScheduleInstallmentRepository.deleteByLoanAccountId(loanAccount.getId());
        loanRepaymentScheduleInstallmentRepository.flush();
        return loanRepaymentScheduleInstallmentRepository.saveAll(generated);
    }

    @Transactional
    public List<LoanRepaymentScheduleInstallment> replaceWithProvidedScheduleForLsp(
            UUID lspId,
            UUID applicationId,
            List<InstallmentDraft> installments
    ) {
        LoanApplication application = loanApplicationService.getApplicationForLsp(lspId, applicationId);
        LoanAccount loanAccount = getMutableLoanAccountForLsp(lspId, applicationId);
        try {
            validateProvidedInstallments(loanAccount, installments);
        } catch (BusinessRuleViolationException exception) {
            if ("REPAYMENT_SCHEDULE_INVALID".equals(exception.getErrorCode())) {
                alertRuleEvaluationService.emitLspProvidedScheduleViolation(
                        application,
                        resolveScheduleViolationType(exception),
                        exception.getMessage(),
                        exception.getFieldErrors()
                );
            }
            throw exception;
        }
        loanRepaymentScheduleInstallmentRepository.deleteByLoanAccountId(loanAccount.getId());
        loanRepaymentScheduleInstallmentRepository.flush();
        return loanRepaymentScheduleInstallmentRepository.saveAll(installments.stream()
                .map(draft -> new LoanRepaymentScheduleInstallment(
                        loanAccount,
                        draft.installmentNumber(),
                        draft.dueDate(),
                        scaleCurrency(draft.openingPrincipal()),
                        scaleCurrency(draft.principalDue()),
                        scaleCurrency(draft.interestDue()),
                        scaleCurrency(draft.installmentAmount()),
                        scaleCurrency(draft.closingPrincipal())
                ))
                .toList());
    }

    @Transactional(readOnly = true, noRollbackFor = BusinessRuleViolationException.class)
    public void validatePersistedScheduleForDisbursement(LoanAccount loanAccount) {
        List<LoanRepaymentScheduleInstallment> installments = loanRepaymentScheduleInstallmentRepository
                .findByLoanAccount_IdOrderByInstallmentNumberAsc(loanAccount.getId());
        if (installments.isEmpty()) {
            throw new BusinessRuleViolationException(
                    "DISBURSEMENT_VALIDATION_FAILED",
                    "Disbursement cannot proceed until a repayment schedule exists.",
                    Map.of("repaymentSchedule", "Repayment schedule is required before disbursement.")
            );
        }

        List<InstallmentDraft> drafts = installments.stream()
                .map(installment -> new InstallmentDraft(
                        installment.getInstallmentNumber(),
                        installment.getDueDate(),
                        installment.getOpeningPrincipal(),
                        installment.getPrincipalDue(),
                        installment.getInterestDue(),
                        installment.getInstallmentAmount(),
                        installment.getClosingPrincipal()
                ))
                .toList();
        validateProvidedInstallments(loanAccount, drafts, "DISBURSEMENT_VALIDATION_FAILED", "Stored repayment schedule is invalid for the current loan terms.");
    }

    private LoanAccount getMutableLoanAccountForLsp(UUID lspId, UUID applicationId) {
        loanApplicationService.getApplicationForLsp(lspId, applicationId);
        LoanAccount loanAccount = loanApplicationService.getLoanAccount(applicationId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Repayment schedule can only be set after the loan has been auto-approved."
                ));
        if (loanAccount.getStatus() != LoanAccountStatus.PENDING_DISBURSEMENT) {
            throw new IllegalArgumentException("Repayment schedule can only be replaced before disbursement is requested.");
        }
        if (loanPaymentTransactionRepository.existsByLoanAccount_Id(loanAccount.getId())) {
            throw new IllegalArgumentException("Repayment schedule cannot be replaced after repayments have started.");
        }
        return loanAccount;
    }

    private List<LoanRepaymentScheduleInstallment> buildGeneratedInstallments(LoanAccount loanAccount) {
        BigDecimal principal = scaleCurrency(loanAccount.getPrincipalAmount());
        int tenureMonths = loanAccount.getTenureMonths();
        BigDecimal annualRate = loanAccount.getLoanProduct().getInterestRate();
        BigDecimal monthlyRate = annualRate.divide(BigDecimal.valueOf(1200), 10, RoundingMode.HALF_UP);
        BigDecimal emiAmount = calculateMonthlyEmi(principal, monthlyRate, tenureMonths);
        LocalDate firstDueDate = loanAccount.getApprovedAt().atZone(ZoneOffset.UTC).toLocalDate().plusMonths(1);

        BigDecimal remainingPrincipal = principal;
        List<LoanRepaymentScheduleInstallment> installments = new ArrayList<>();
        for (int installmentNumber = 1; installmentNumber <= tenureMonths; installmentNumber++) {
            BigDecimal openingPrincipal = scaleCurrency(remainingPrincipal);
            BigDecimal interestDue = scaleCurrency(openingPrincipal.multiply(monthlyRate));
            BigDecimal installmentAmount = emiAmount;
            BigDecimal principalDue = scaleCurrency(installmentAmount.subtract(interestDue));
            if (installmentNumber == tenureMonths) {
                principalDue = openingPrincipal;
                installmentAmount = scaleCurrency(principalDue.add(interestDue));
            }
            BigDecimal closingPrincipal = scaleCurrency(openingPrincipal.subtract(principalDue));
            if (installmentNumber == tenureMonths && closingPrincipal.compareTo(BigDecimal.ZERO) != 0) {
                principalDue = scaleCurrency(principalDue.add(closingPrincipal));
                installmentAmount = scaleCurrency(principalDue.add(interestDue));
                closingPrincipal = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            } else if (closingPrincipal.compareTo(BigDecimal.ZERO) < 0) {
                closingPrincipal = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            }
            installments.add(new LoanRepaymentScheduleInstallment(
                    loanAccount,
                    installmentNumber,
                    firstDueDate.plusMonths(installmentNumber - 1L),
                    openingPrincipal,
                    principalDue,
                    interestDue,
                    installmentAmount,
                    closingPrincipal
            ));
            remainingPrincipal = closingPrincipal;
        }
        return installments;
    }

    private void validateProvidedInstallments(LoanAccount loanAccount, List<InstallmentDraft> installments) {
        validateProvidedInstallments(
                loanAccount,
                installments,
                "REPAYMENT_SCHEDULE_INVALID",
                "Provided repayment schedule does not reconcile with the approved loan terms."
        );
    }

    private void validateProvidedInstallments(
            LoanAccount loanAccount,
            List<InstallmentDraft> installments,
            String errorCode,
            String message
    ) {
        Map<String, String> violations = new LinkedHashMap<>();
        ScheduleViolationType primaryViolationType = ScheduleViolationType.SCHEDULE_GENERIC;
        if (installments == null || installments.isEmpty()) {
            primaryViolationType = registerScheduleViolation(
                    violations,
                    primaryViolationType,
                    ScheduleViolationType.SCHEDULE_GENERIC,
                    "installments",
                    "At least one installment is required."
            );
            throw scheduleViolation(errorCode, message, violations, primaryViolationType);
        }
        if (installments.size() != loanAccount.getTenureMonths()) {
            primaryViolationType = registerScheduleViolation(
                    violations,
                    primaryViolationType,
                    ScheduleViolationType.SCHEDULE_INSTALLMENT_COUNT_MISMATCH,
                    "installments",
                    "Installment count must match the approved tenure."
            );
        }

        BigDecimal approvedPrincipal = scaleCurrency(loanAccount.getPrincipalAmount());
        InstallmentDraft firstInstallment = installments.get(0);
        if (scaleCurrency(firstInstallment.openingPrincipal()).compareTo(approvedPrincipal) != 0) {
            primaryViolationType = registerScheduleViolation(
                    violations,
                    primaryViolationType,
                    ScheduleViolationType.SCHEDULE_OPENING_MISMATCH,
                    "installments[0].openingPrincipal",
                    "Opening principal on the first installment must equal the approved principal amount."
            );
        }

        BigDecimal totalPrincipal = BigDecimal.ZERO.setScale(2);
        LocalDate previousDueDate = null;
        BigDecimal previousClosingPrincipal = null;
        for (int index = 0; index < installments.size(); index++) {
            InstallmentDraft installment = installments.get(index);
            String prefix = "installments[" + index + "]";
            if (installment.installmentNumber() != index + 1) {
                primaryViolationType = registerScheduleViolation(
                        violations,
                        primaryViolationType,
                        ScheduleViolationType.SCHEDULE_GENERIC,
                        prefix + ".installmentNumber",
                        "Installment numbers must be contiguous starting from 1."
                );
            }
            if (previousDueDate != null && !installment.dueDate().isAfter(previousDueDate)) {
                primaryViolationType = registerScheduleViolation(
                        violations,
                        primaryViolationType,
                        ScheduleViolationType.SCHEDULE_GENERIC,
                        prefix + ".dueDate",
                        "Due dates must be strictly increasing."
                );
            }
            previousDueDate = installment.dueDate();

            if (index > 0 && previousClosingPrincipal != null
                    && scaleCurrency(installment.openingPrincipal()).compareTo(previousClosingPrincipal) != 0) {
                primaryViolationType = registerScheduleViolation(
                        violations,
                        primaryViolationType,
                        ScheduleViolationType.SCHEDULE_CHAIN_BROKEN,
                        prefix + ".openingPrincipal",
                        "Opening principal must equal the previous installment closing principal."
                );
            }

            if (hasNegativeValue(installment.openingPrincipal())
                    || hasNegativeValue(installment.principalDue())
                    || hasNegativeValue(installment.interestDue())
                    || hasNegativeValue(installment.installmentAmount())
                    || hasNegativeValue(installment.closingPrincipal())) {
                primaryViolationType = registerScheduleViolation(
                        violations,
                        primaryViolationType,
                        ScheduleViolationType.SCHEDULE_GENERIC,
                        prefix,
                        "Installment amounts cannot be negative."
                );
            }

            BigDecimal expectedInstallmentAmount = scaleCurrency(installment.principalDue().add(installment.interestDue()));
            if (scaleCurrency(installment.installmentAmount()).compareTo(expectedInstallmentAmount) != 0) {
                primaryViolationType = registerScheduleViolation(
                        violations,
                        primaryViolationType,
                        ScheduleViolationType.SCHEDULE_GENERIC,
                        prefix + ".installmentAmount",
                        "Principal due plus interest due must equal installment amount."
                );
            }

            BigDecimal expectedPrincipalFromRow = scaleCurrency(
                    installment.openingPrincipal().subtract(installment.closingPrincipal())
            );
            if (scaleCurrency(installment.principalDue()).compareTo(expectedPrincipalFromRow) != 0) {
                primaryViolationType = registerScheduleViolation(
                        violations,
                        primaryViolationType,
                        ScheduleViolationType.SCHEDULE_ROW_RECONCILE_FAILED,
                        prefix + ".principalDue",
                        "Principal due must equal opening principal minus closing principal for the installment."
                );
            }

            totalPrincipal = scaleCurrency(totalPrincipal.add(installment.principalDue()));
            previousClosingPrincipal = scaleCurrency(installment.closingPrincipal());
        }

        if (totalPrincipal.compareTo(approvedPrincipal) != 0) {
            primaryViolationType = registerScheduleViolation(
                    violations,
                    primaryViolationType,
                    ScheduleViolationType.SCHEDULE_PRINCIPAL_NOT_CLOSED,
                    "principalDueTotal",
                    "Total principal due must equal the approved principal amount."
            );
        }

        InstallmentDraft lastInstallment = installments.get(installments.size() - 1);
        if (scaleCurrency(lastInstallment.closingPrincipal()).compareTo(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)) != 0) {
            primaryViolationType = registerScheduleViolation(
                    violations,
                    primaryViolationType,
                    ScheduleViolationType.SCHEDULE_FINAL_NONZERO,
                    "installments[" + (installments.size() - 1) + "].closingPrincipal",
                    "Final installment closing principal must be zero."
            );
        }

        if (!violations.isEmpty()) {
            throw scheduleViolation(errorCode, message, violations, primaryViolationType);
        }
    }

    private static ScheduleViolationType registerScheduleViolation(
            Map<String, String> violations,
            ScheduleViolationType currentPrimary,
            ScheduleViolationType candidateType,
            String field,
            String message
    ) {
        violations.put(field, message);
        return higherPriorityScheduleViolation(currentPrimary, candidateType);
    }

    private static ScheduleViolationType higherPriorityScheduleViolation(
            ScheduleViolationType current,
            ScheduleViolationType candidate
    ) {
        return scheduleViolationPriority(candidate) < scheduleViolationPriority(current) ? candidate : current;
    }

    private static int scheduleViolationPriority(ScheduleViolationType type) {
        return switch (type) {
            case SCHEDULE_OPENING_MISMATCH -> 1;
            case SCHEDULE_CHAIN_BROKEN -> 2;
            case SCHEDULE_ROW_RECONCILE_FAILED -> 3;
            case SCHEDULE_FINAL_NONZERO -> 4;
            case SCHEDULE_PRINCIPAL_NOT_CLOSED -> 5;
            case SCHEDULE_INSTALLMENT_COUNT_MISMATCH -> 6;
            case SCHEDULE_GENERIC -> 7;
        };
    }

    private static BusinessRuleViolationException scheduleViolation(
            String errorCode,
            String message,
            Map<String, String> violations,
            ScheduleViolationType primaryViolationType
    ) {
        violations.put("violationType", primaryViolationType.name());
        return new BusinessRuleViolationException(errorCode, message, violations);
    }

    private static ScheduleViolationType resolveScheduleViolationType(BusinessRuleViolationException exception) {
        String violationType = exception.getFieldErrors().get("violationType");
        if (violationType == null) {
            return ScheduleViolationType.SCHEDULE_GENERIC;
        }
        try {
            return ScheduleViolationType.valueOf(violationType);
        } catch (IllegalArgumentException ignored) {
            return ScheduleViolationType.SCHEDULE_GENERIC;
        }
    }

    private static boolean hasNegativeValue(BigDecimal value) {
        return value == null || value.compareTo(BigDecimal.ZERO) < 0;
    }

    private static BigDecimal calculateMonthlyEmi(BigDecimal principal, BigDecimal monthlyRate, int tenureMonths) {
        if (monthlyRate.compareTo(BigDecimal.ZERO) == 0) {
            return principal.divide(BigDecimal.valueOf(tenureMonths), 2, RoundingMode.HALF_UP);
        }

        BigDecimal onePlusRate = BigDecimal.ONE.add(monthlyRate);
        BigDecimal compounded = onePlusRate.pow(tenureMonths, java.math.MathContext.DECIMAL64);
        BigDecimal numerator = principal.multiply(monthlyRate).multiply(compounded);
        BigDecimal denominator = compounded.subtract(BigDecimal.ONE);
        return numerator.divide(denominator, 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal scaleCurrency(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    public record InstallmentDraft(
            int installmentNumber,
            LocalDate dueDate,
            BigDecimal openingPrincipal,
            BigDecimal principalDue,
            BigDecimal interestDue,
            BigDecimal installmentAmount,
            BigDecimal closingPrincipal
    ) {
    }
}
