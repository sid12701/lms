package com.bhawana.lms.service;

import com.bhawana.lms.common.web.BusinessRuleViolationException;
import com.bhawana.lms.domain.Borrower;
import com.bhawana.lms.domain.LoanAccount;
import com.bhawana.lms.domain.LoanApplication;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LoanDisbursementService {

    private final LoanApplicationService loanApplicationService;
    private final LoanApprovalService loanApprovalService;
    private final LoanRepaymentScheduleService loanRepaymentScheduleService;

    public LoanDisbursementService(
            LoanApplicationService loanApplicationService,
            LoanApprovalService loanApprovalService,
            LoanRepaymentScheduleService loanRepaymentScheduleService
    ) {
        this.loanApplicationService = loanApplicationService;
        this.loanApprovalService = loanApprovalService;
        this.loanRepaymentScheduleService = loanRepaymentScheduleService;
    }

    @Transactional
    public LoanApplication requestDisbursementForLsp(
            UUID lspId,
            UUID applicationId,
            String actorUsername,
            BigDecimal disbursalAmount,
            String requestBankAccountNumber,
            String requestIfscCode,
            String requestAccountHolderName
    ) {
        loanApprovalService.autoApproveIfEligibleForLsp(applicationId, actorUsername);
        LoanApplication application = loanApplicationService.getApplicationForLsp(lspId, applicationId);
        LoanAccount loanAccount = loanApplicationService.getLoanAccount(applicationId).orElse(null);

        Map<String, String> violations = new LinkedHashMap<>();
        if (!loanApplicationService.hasAllRequiredLmsManagedDocuments(applicationId, false)) {
            violations.put("documents", "All required documents must be uploaded into LMS-managed storage.");
        }
        if (loanAccount == null) {
            violations.put("loanAccount", "Loan account is not available for disbursement.");
        }

        validateDisbursementBankDetails(
                application.getBorrower(),
                requestBankAccountNumber,
                requestIfscCode,
                requestAccountHolderName,
                violations
        );

        if (loanAccount != null) {
            try {
                loanRepaymentScheduleService.validatePersistedScheduleForDisbursement(loanAccount);
            } catch (BusinessRuleViolationException exception) {
                violations.putAll(exception.getFieldErrors());
            }
        }

        BigDecimal scaledDisbursalAmount = scaleCurrency(disbursalAmount);
        if (loanAccount != null) {
            BigDecimal principalAmount = scaleCurrency(loanAccount.getPrincipalAmount());
            if (scaledDisbursalAmount.compareTo(principalAmount) > 0) {
                violations.put("disbursalAmount", "Disbursal amount cannot exceed the approved principal amount.");
            } else {
                BigDecimal shortfall = scaleCurrency(principalAmount.subtract(scaledDisbursalAmount));
                BigDecimal allowedShortfall = scaleCurrency(principalAmount
                        .multiply(loanAccount.getLoanProduct().getProcessingFeeRate())
                        .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP));
                if (shortfall.compareTo(allowedShortfall) > 0) {
                    violations.put(
                            "disbursalAmount",
                            "Disbursal shortfall exceeds the configured processing fee cap of " + allowedShortfall + "."
                    );
                }
            }
        }

        if (!violations.isEmpty()) {
            throw new BusinessRuleViolationException(
                    "DISBURSEMENT_VALIDATION_FAILED",
                    "Disbursement request failed compliance checks.",
                    violations
            );
        }

        return loanApplicationService.initiateDisbursement(applicationId, actorUsername, scaledDisbursalAmount);
    }

    private static BigDecimal scaleCurrency(BigDecimal value) {
        if (value == null) {
            throw new IllegalArgumentException("Disbursal amount is required.");
        }
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private static void validateDisbursementBankDetails(
            Borrower borrower,
            String requestBankAccountNumber,
            String requestIfscCode,
            String requestAccountHolderName,
            Map<String, String> violations
    ) {
        if (borrower == null) {
            violations.put("borrowerBank", "Borrower bank account must be on file before disbursement.");
            return;
        }

        String onFileAccount = normalizeAccountNumber(borrower.getBankAccountNumber());
        String onFileIfsc = normalizeIfsc(borrower.getIfscCode());
        if (onFileAccount == null || onFileIfsc == null) {
            violations.put("borrowerBank", "Borrower bank account must be on file before disbursement.");
            return;
        }

        String submittedAccount = normalizeAccountNumber(requestBankAccountNumber);
        String submittedIfsc = normalizeIfsc(requestIfscCode);
        if (submittedAccount == null || !submittedAccount.equals(onFileAccount)
                || submittedIfsc == null || !submittedIfsc.equals(onFileIfsc)) {
            violations.put("bankAccount", "Disbursement bank details do not match the borrower profile on file.");
            return;
        }

        String submittedHolder = normalizeHolderName(requestAccountHolderName);
        if (submittedHolder != null) {
            String onFileHolder = normalizeHolderName(borrower.getAccountHolderName());
            if (onFileHolder == null || !submittedHolder.equals(onFileHolder)) {
                violations.put(
                        "accountHolderName",
                        "Account holder name does not match the borrower profile on file."
                );
            }
        }
    }

    private static String normalizeAccountNumber(String value) {
        if (value == null) {
            return null;
        }
        String digits = value.replaceAll("\\D", "");
        return digits.isBlank() ? null : digits;
    }

    private static String normalizeIfsc(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim().toUpperCase(Locale.ROOT);
        return trimmed.isBlank() ? null : trimmed;
    }

    private static String normalizeHolderName(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim().toUpperCase(Locale.ROOT);
        return trimmed.isBlank() ? null : trimmed;
    }
}
