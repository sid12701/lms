package com.bhawana.lms.service;

import com.bhawana.lms.common.api.error.ApiConflictException;
import com.bhawana.lms.common.api.error.BusinessRuleViolationException;
import com.bhawana.lms.common.api.error.ResourceNotFoundException;
import com.bhawana.lms.common.money.LoanFeeCalculator;
import com.bhawana.lms.common.money.Money;
import com.bhawana.lms.domain.Borrower;
import com.bhawana.lms.domain.LoanAccount;
import com.bhawana.lms.domain.LoanAccountStatus;
import com.bhawana.lms.domain.LoanApplication;
import com.bhawana.lms.service.BankAccountHolderNameMatcher.HolderNameMatchOutcome;
import com.bhawana.lms.service.DisbursementBankDetailsValidation.BankDetailWarning;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pre-flight validation for disbursement: documents, schedule, net disbursal amount, bank details.
 */
@Service
public class DisbursementPreflightValidator {

    public static final String HOLDER_NAME_SOFT_MISMATCH_CODE = "HOLDER_NAME_SOFT_MISMATCH";

    private final LoanApplicationQueryService loanApplicationQueryService;
    private final LoanApplicationDocumentChecklistService loanApplicationDocumentChecklistService;
    private final LoanRepaymentScheduleService loanRepaymentScheduleService;
    private final BorrowerBankDetailsService borrowerBankDetailsService;
    private final BankAccountHolderNameMatcher holderNameMatcher;

    public DisbursementPreflightValidator(
            LoanApplicationQueryService loanApplicationQueryService,
            LoanApplicationDocumentChecklistService loanApplicationDocumentChecklistService,
            LoanRepaymentScheduleService loanRepaymentScheduleService,
            BorrowerBankDetailsService borrowerBankDetailsService,
            BankAccountHolderNameMatcher holderNameMatcher
    ) {
        this.loanApplicationQueryService = loanApplicationQueryService;
        this.loanApplicationDocumentChecklistService = loanApplicationDocumentChecklistService;
        this.loanRepaymentScheduleService = loanRepaymentScheduleService;
        this.borrowerBankDetailsService = borrowerBankDetailsService;
        this.holderNameMatcher = holderNameMatcher;
    }

    @Transactional(noRollbackFor = BusinessRuleViolationException.class)
    public BankDetailsCheckResult verifyDisbursementBankDetailsForLsp(
            UUID lspId,
            UUID applicationId,
            String requestBankAccountNumber,
            String requestIfscCode,
            String requestAccountHolderName
    ) {
        LoanApplication application = loanApplicationQueryService.getApplicationForLsp(lspId, applicationId);
        DisbursementBankDetailsValidation validation = validateLspDisbursementBankDetails(
                application.getBorrower(),
                requestBankAccountNumber,
                requestIfscCode,
                requestAccountHolderName
        );
        if (validation.violations().containsKey("bankAccount")
                || validation.violations().containsKey("borrowerBank")
                || validation.violations().containsKey("accountHolderName")) {
            borrowerBankDetailsService.recordHardDisbursementBankMismatch(
                    application,
                    lspId,
                    requestBankAccountNumber,
                    requestIfscCode,
                    requestAccountHolderName
            );
        }
        if (!validation.violations().isEmpty()) {
            throw new BusinessRuleViolationException(
                    "DISBURSEMENT_VALIDATION_FAILED",
                    "Disbursement bank details failed compliance checks.",
                    validation.violations()
            );
        }
        if (validation.warnings().isEmpty()) {
            return BankDetailsCheckResult.ok();
        }
        return BankDetailsCheckResult.warn(validation.warnings());
    }

    public void ensureDisbursementRequestAllowed(LoanAccount loanAccount) {
        if (loanAccount == null) {
            throw new ResourceNotFoundException("Loan account is not available for disbursement.");
        }
        if (loanAccount.getStatus() == LoanAccountStatus.DISBURSEMENT_REQUESTED) {
            throw new ApiConflictException(
                    "DISBURSEMENT_ALREADY_REQUESTED",
                    "Disbursement has already been requested for this loan account."
            );
        }
        if (loanAccount.getStatus() != LoanAccountStatus.PENDING_DISBURSEMENT
                && loanAccount.getStatus() != LoanAccountStatus.DISBURSEMENT_FAILED
                && loanAccount.getStatus() != LoanAccountStatus.DISBURSEMENT_PENDING_RECONCILIATION) {
            throw new ApiConflictException(
                    "DISBURSEMENT_NOT_ALLOWED",
                    "Loan account is not eligible for a new disbursement request."
            );
        }
    }

    public Map<String, String> validateAutomatedDisbursement(LoanApplication application, LoanAccount loanAccount) {
        Map<String, String> violations = new LinkedHashMap<>();
        if (!loanApplicationDocumentChecklistService.hasAllRequiredLmsManagedDocuments(application.getId())) {
            violations.put("documents", "All required documents must be uploaded into LMS-managed storage.");
        }
        if (loanAccount == null) {
            violations.put("loanAccount", "Loan account is not available for disbursement.");
            return violations;
        }
        try {
            loanRepaymentScheduleService.validatePersistedScheduleForDisbursement(loanAccount);
        } catch (BusinessRuleViolationException exception) {
            violations.putAll(exception.getFieldErrors());
        }
        BigDecimal rawPrincipal = loanAccount.getPrincipalAmount();
        if (rawPrincipal == null || rawPrincipal.compareTo(BigDecimal.ZERO) <= 0) {
            violations.put("disbursalAmount", "Disbursal amount must be greater than zero.");
            return violations;
        }
        BigDecimal principalAmount = Money.scale(rawPrincipal);
        BigDecimal processingFee = LoanFeeCalculator.computeProcessingFee(
                principalAmount,
                loanAccount.getLoanProductVersion().getProcessingFeeRate()
        );
        BigDecimal netDisbursal = Money.scale(principalAmount.subtract(processingFee));
        if (netDisbursal.compareTo(BigDecimal.ZERO) <= 0) {
            violations.put(
                    "disbursalAmount",
                    "Processing fee leaves no net amount to disburse to the borrower."
            );
            return violations;
        }
        BigDecimal shortfall = Money.scale(principalAmount.subtract(netDisbursal));
        BigDecimal allowedShortfall = processingFee;
        if (shortfall.compareTo(allowedShortfall) > 0) {
            violations.put(
                    "disbursalAmount",
                    "Disbursement shortfall exceeds the configured processing fee cap of " + allowedShortfall + "."
            );
        }
        return violations;
    }

    public DisbursementBankDetailsValidation validateWorkerDisbursementBankDetails(LoanApplication application) {
        Borrower borrower = application != null ? application.getBorrower() : null;
        DisbursementBankDetailsValidation.Builder builder = DisbursementBankDetailsValidation.builder();
        if (borrower == null) {
            return builder.violation("borrowerBank", "Borrower bank account must be on file before disbursement.").build();
        }

        String onFileAccount = normalizeAccountNumber(borrower.getBankAccountNumber());
        String onFileIfsc = normalizeIfsc(borrower.getIfscCode());
        if (onFileAccount == null || onFileIfsc == null) {
            return builder.violation("borrowerBank", "Borrower bank account must be on file before disbursement.").build();
        }

        applyHolderNameOutcome(builder, borrower.getFullName(), borrower.getAccountHolderName());
        return builder.build();
    }

    DisbursementBankDetailsValidation validateLspDisbursementBankDetails(
            Borrower borrower,
            String requestBankAccountNumber,
            String requestIfscCode,
            String requestAccountHolderName
    ) {
        DisbursementBankDetailsValidation.Builder builder = DisbursementBankDetailsValidation.builder();
        if (borrower == null) {
            return builder.violation("borrowerBank", "Borrower bank account must be on file before disbursement.").build();
        }

        String onFileAccount = normalizeAccountNumber(borrower.getBankAccountNumber());
        String onFileIfsc = normalizeIfsc(borrower.getIfscCode());
        if (onFileAccount == null || onFileIfsc == null) {
            return builder.violation("borrowerBank", "Borrower bank account must be on file before disbursement.").build();
        }

        String submittedAccount = normalizeAccountNumber(requestBankAccountNumber);
        String submittedIfsc = normalizeIfsc(requestIfscCode);
        if (submittedAccount == null || !submittedAccount.equals(onFileAccount)
                || submittedIfsc == null || !submittedIfsc.equals(onFileIfsc)) {
            return builder.violation(
                    "bankAccount",
                    "Disbursement bank details do not match the borrower profile on file."
            ).build();
        }

        if (requestAccountHolderName != null && borrower.getAccountHolderName() != null) {
            applyHolderNameOutcome(builder, requestAccountHolderName, borrower.getAccountHolderName());
        }
        return builder.build();
    }

    private void applyHolderNameOutcome(
            DisbursementBankDetailsValidation.Builder builder,
            String submittedName,
            String onFileName
    ) {
        HolderNameMatchOutcome outcome = holderNameMatcher.compare(submittedName, onFileName);
        if (outcome == HolderNameMatchOutcome.SOFT_MISMATCH) {
            builder.warning(holderNameSoftMismatchWarning(submittedName, onFileName));
        } else if (outcome == HolderNameMatchOutcome.HARD_MISMATCH) {
            builder.violation(
                    "accountHolderName",
                    "Account holder name does not match the borrower profile on file."
            );
        }
    }

    private static BankDetailWarning holderNameSoftMismatchWarning(String submittedName, String onFileName) {
        return new BankDetailWarning(
                "accountHolderName",
                HOLDER_NAME_SOFT_MISMATCH_CODE,
                "Account holder name does not match the borrower profile on file after normalisation."
                        + " Submitted: "
                        + submittedName
                        + "; on file: "
                        + onFileName
                        + "."
        );
    }

    static String normalizeAccountNumber(String value) {
        if (value == null) {
            return null;
        }
        String digits = value.replaceAll("\\D", "");
        return digits.isBlank() ? null : digits;
    }

    static String normalizeIfsc(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim().toUpperCase(Locale.ROOT);
        return trimmed.isBlank() ? null : trimmed;
    }
}
