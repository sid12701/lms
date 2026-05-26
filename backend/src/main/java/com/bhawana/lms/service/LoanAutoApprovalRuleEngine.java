package com.bhawana.lms.service;

import com.bhawana.lms.domain.Borrower;
import com.bhawana.lms.domain.LoanAccount;
import com.bhawana.lms.domain.LoanApplication;
import com.bhawana.lms.domain.LoanApplicationDocumentChecklist;
import com.bhawana.lms.domain.LoanApplicationDocumentChecklistStatus;
import com.bhawana.lms.domain.LoanProduct;
import com.bhawana.lms.domain.LoanProductLspMapping;
import com.bhawana.lms.domain.LoanProductStatus;
import com.bhawana.lms.domain.LspStatus;
import com.bhawana.lms.repo.LoanApplicationDocumentChecklistRepository;
import com.bhawana.lms.repo.LoanProductLspMappingRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gap #11 / Follow-up #1 — evaluates the full auto-approval rule set for a
 * loan application. Returns the structured failure list driving the
 * {@code AWAITING_APPROVAL → APPROVED_PENDING_DISBURSAL | REJECTED}
 * transition.
 *
 * <p>The engine is read-only; it never mutates the application. The caller
 * (typically {@link LoanApplicationLifecycleService#autoApproveIfEligibleForLsp})
 * is responsible for translating the result into a state-machine transition.</p>
 */
@Service
public class LoanAutoApprovalRuleEngine {

    private final LoanApplicationDocumentChecklistRepository checklistRepository;
    private final LoanProductLspMappingRepository mappingRepository;
    private final BorrowerActiveLoanChecker activeLoanChecker;

    public LoanAutoApprovalRuleEngine(
            LoanApplicationDocumentChecklistRepository checklistRepository,
            LoanProductLspMappingRepository mappingRepository,
            BorrowerActiveLoanChecker activeLoanChecker
    ) {
        this.checklistRepository = checklistRepository;
        this.mappingRepository = mappingRepository;
        this.activeLoanChecker = activeLoanChecker;
    }

    @Transactional(readOnly = true)
    public Evaluation evaluate(LoanApplication application) {
        List<RuleCode> failures = new ArrayList<>();

        evaluateProductAndLsp(application, failures);
        evaluateAmountTenureRate(application, failures);
        evaluateBorrowerFields(application.getBorrower(), failures);
        evaluateDocumentChecklist(application, failures);
        evaluateOneOpenLoanRule(application, failures);

        return new Evaluation(failures.isEmpty(), List.copyOf(failures));
    }

    private void evaluateProductAndLsp(LoanApplication application, List<RuleCode> failures) {
        LoanProduct product = application.getLoanProduct();
        if (product == null || product.getStatus() != LoanProductStatus.ACTIVE) {
            failures.add(RuleCode.PRODUCT_INACTIVE);
        }
        if (application.getLsp() == null || application.getLsp().getStatus() != LspStatus.ACTIVE) {
            failures.add(RuleCode.LSP_INACTIVE);
        }
        if (application.getLsp() != null && product != null) {
            LoanProductLspMapping mapping = mappingRepository
                    .findByLsp_IdAndLoanProduct_Id(application.getLsp().getId(), product.getId())
                    .orElse(null);
            if (mapping == null || !mapping.isEnabled()) {
                failures.add(RuleCode.LSP_PRODUCT_MAPPING_INACTIVE);
            }
        }
    }

    private void evaluateAmountTenureRate(LoanApplication application, List<RuleCode> failures) {
        LoanProduct product = application.getLoanProduct();
        if (product == null) {
            return; // already failed via PRODUCT_INACTIVE
        }
        BigDecimal amount = application.getRequestedAmount();
        if (amount == null
                || amount.compareTo(product.getMinPrincipal()) < 0
                || amount.compareTo(product.getMaxPrincipal()) > 0) {
            failures.add(RuleCode.LOAN_AMOUNT_OUT_OF_RANGE);
        }
        int tenure = application.getTenureMonths();
        if (tenure < product.getMinTenureMonths() || tenure > product.getMaxTenureMonths()) {
            failures.add(RuleCode.LOAN_TENURE_OUT_OF_RANGE);
        }
    }

    private void evaluateBorrowerFields(Borrower borrower, List<RuleCode> failures) {
        if (borrower == null) {
            failures.add(RuleCode.BORROWER_REQUIRED_FIELDS_MISSING);
            return;
        }
        if (isBlank(borrower.getFullName())
                || isBlank(borrower.getPan())
                || isBlank(borrower.getMobile())
                || isBlank(borrower.getAadharNumber())
                || isBlank(borrower.getAddressLine1())
                || isBlank(borrower.getCity())
                || isBlank(borrower.getState())
                || isBlank(borrower.getAddressZipCode())
                || borrower.getMonthlyIncome() == null
                || borrower.getMonthlyIncome().compareTo(BigDecimal.ZERO) <= 0
                || isBlank(borrower.getReferencePersonName())
                || isBlank(borrower.getReferencePersonNumber())) {
            failures.add(RuleCode.BORROWER_REQUIRED_FIELDS_MISSING);
        }
    }

    private void evaluateDocumentChecklist(LoanApplication application, List<RuleCode> failures) {
        List<LoanApplicationDocumentChecklist> checklist = checklistRepository
                .findByLoanApplication_IdOrderByCreatedAtAsc(application.getId());
        boolean docsComplete = !checklist.isEmpty() && checklist.stream()
                .filter(item -> item.getDocumentType().isRequiredForApproval())
                .allMatch(item -> item.getStatus() == LoanApplicationDocumentChecklistStatus.SUBMITTED
                        || item.getStatus() == LoanApplicationDocumentChecklistStatus.NOT_REQUIRED);
        if (!docsComplete) {
            failures.add(RuleCode.REQUIRED_DOCUMENTS_NOT_UPLOADED);
        }
    }

    private void evaluateOneOpenLoanRule(LoanApplication application, List<RuleCode> failures) {
        Borrower borrower = application.getBorrower();
        if (borrower == null || borrower.getId() == null) {
            return;
        }
        UUID currentApplicationId = application.getId();
        boolean hasOtherOpenLoan = activeLoanChecker.findOpenLoansAcrossAllLsps(borrower.getId())
                .stream()
                .anyMatch(account -> !belongsToCurrentApplication(account, currentApplicationId));
        if (hasOtherOpenLoan) {
            failures.add(RuleCode.BORROWER_HAS_OPEN_LOAN);
        }
    }

    private static boolean belongsToCurrentApplication(LoanAccount account, UUID currentApplicationId) {
        return currentApplicationId != null
                && account != null
                && account.getLoanApplication() != null
                && currentApplicationId.equals(account.getLoanApplication().getId());
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    public enum RuleCode {
        PRODUCT_INACTIVE,
        LSP_INACTIVE,
        LSP_PRODUCT_MAPPING_INACTIVE,
        LOAN_AMOUNT_OUT_OF_RANGE,
        LOAN_TENURE_OUT_OF_RANGE,
        BORROWER_REQUIRED_FIELDS_MISSING,
        REQUIRED_DOCUMENTS_NOT_UPLOADED,
        BORROWER_HAS_OPEN_LOAN
    }

    public record Evaluation(boolean approved, List<RuleCode> failedRules) {
    }
}
