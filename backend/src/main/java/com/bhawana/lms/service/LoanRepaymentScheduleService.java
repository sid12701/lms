package com.bhawana.lms.service;

import com.bhawana.lms.common.web.BusinessRuleViolationException;
import com.bhawana.lms.domain.LoanAccount;
import com.bhawana.lms.domain.LoanAccountStatus;
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

    public LoanRepaymentScheduleService(
            LoanApplicationService loanApplicationService,
            LoanRepaymentScheduleInstallmentRepository loanRepaymentScheduleInstallmentRepository,
            LoanPaymentTransactionRepository loanPaymentTransactionRepository
    ) {
        this.loanApplicationService = loanApplicationService;
        this.loanRepaymentScheduleInstallmentRepository = loanRepaymentScheduleInstallmentRepository;
        this.loanPaymentTransactionRepository = loanPaymentTransactionRepository;
    }

    @Transactional
    public List<LoanRepaymentScheduleInstallment> replaceWithGeneratedScheduleForLsp(UUID lspId, UUID applicationId) {
        LoanAccount loanAccount = getMutableLoanAccountForLsp(lspId, applicationId);
        List<LoanRepaymentScheduleInstallment> generated = buildGeneratedInstallments(loanAccount);
        loanRepaymentScheduleInstallmentRepository.deleteByLoanAccount_Id(loanAccount.getId());
        loanRepaymentScheduleInstallmentRepository.flush();
        return loanRepaymentScheduleInstallmentRepository.saveAll(generated);
    }

    @Transactional
    public List<LoanRepaymentScheduleInstallment> replaceWithProvidedScheduleForLsp(
            UUID lspId,
            UUID applicationId,
            List<InstallmentDraft> installments
    ) {
        LoanAccount loanAccount = getMutableLoanAccountForLsp(lspId, applicationId);
        validateProvidedInstallments(loanAccount, installments);
        loanRepaymentScheduleInstallmentRepository.deleteByLoanAccount_Id(loanAccount.getId());
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

    @Transactional(readOnly = true)
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
            if (closingPrincipal.compareTo(BigDecimal.ZERO) < 0) {
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
        if (installments == null || installments.isEmpty()) {
            violations.put("installments", "At least one installment is required.");
            throw new BusinessRuleViolationException(errorCode, message, violations);
        }
        if (installments.size() != loanAccount.getTenureMonths()) {
            violations.put("installments", "Installment count must match the approved tenure.");
        }

        BigDecimal totalPrincipal = BigDecimal.ZERO.setScale(2);
        LocalDate previousDueDate = null;
        for (int index = 0; index < installments.size(); index++) {
            InstallmentDraft installment = installments.get(index);
            String prefix = "installments[" + index + "]";
            if (installment.installmentNumber() != index + 1) {
                violations.put(prefix + ".installmentNumber", "Installment numbers must be contiguous starting from 1.");
            }
            if (previousDueDate != null && !installment.dueDate().isAfter(previousDueDate)) {
                violations.put(prefix + ".dueDate", "Due dates must be strictly increasing.");
            }
            previousDueDate = installment.dueDate();

            if (hasNegativeValue(installment.openingPrincipal())
                    || hasNegativeValue(installment.principalDue())
                    || hasNegativeValue(installment.interestDue())
                    || hasNegativeValue(installment.installmentAmount())
                    || hasNegativeValue(installment.closingPrincipal())) {
                violations.put(prefix, "Installment amounts cannot be negative.");
            }

            BigDecimal expectedInstallmentAmount = scaleCurrency(installment.principalDue().add(installment.interestDue()));
            if (scaleCurrency(installment.installmentAmount()).compareTo(expectedInstallmentAmount) != 0) {
                violations.put(prefix + ".installmentAmount", "Principal due plus interest due must equal installment amount.");
            }

            totalPrincipal = scaleCurrency(totalPrincipal.add(installment.principalDue()));
        }

        if (totalPrincipal.compareTo(scaleCurrency(loanAccount.getPrincipalAmount())) != 0) {
            violations.put("principalDueTotal", "Total principal due must equal the approved principal amount.");
        }

        if (!violations.isEmpty()) {
            throw new BusinessRuleViolationException(errorCode, message, violations);
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
