package com.bhawana.lms.service;

import com.bhawana.lms.common.api.error.BusinessRuleViolationException;
import com.bhawana.lms.common.pii.BankAccountMasking;
import com.bhawana.lms.domain.Borrower;
import com.bhawana.lms.domain.DisbursementIntent;
import com.bhawana.lms.domain.LoanAccount;
import com.bhawana.lms.domain.LoanApplication;
import com.bhawana.lms.domain.LoanApplicationStatus;
import com.bhawana.lms.repo.DisbursementIntentRepository;
import com.bhawana.lms.repo.LoanAccountRepository;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DisbursementPreviewService {

    private final LoanApplicationQueryService loanApplicationQueryService;
    private final LoanAccountRepository loanAccountRepository;
    private final DisbursementPaymentModeSelector paymentModeSelector;
    private final DisbursementIntentRepository disbursementIntentRepository;

    public DisbursementPreviewService(
            LoanApplicationQueryService loanApplicationQueryService,
            LoanAccountRepository loanAccountRepository,
            DisbursementPaymentModeSelector paymentModeSelector,
            DisbursementIntentRepository disbursementIntentRepository
    ) {
        this.loanApplicationQueryService = loanApplicationQueryService;
        this.loanAccountRepository = loanAccountRepository;
        this.paymentModeSelector = paymentModeSelector;
        this.disbursementIntentRepository = disbursementIntentRepository;
    }

    @Transactional(readOnly = true)
    public DisbursementPreview buildPreview(UUID applicationId) {
        LoanApplication application = loanApplicationQueryService.getApplication(applicationId);
        ensurePreviewAllowed(application.getStatus());

        LoanAccount loanAccount = loanAccountRepository.findDetailedByLoanApplication_Id(applicationId)
                .orElseThrow(() -> new BusinessRuleViolationException(
                        "LOAN_ACCOUNT_MISSING",
                        "The approved application has no loan account and cannot be previewed for disbursement.",
                        Map.of("applicationId", applicationId.toString())
                ));

        DisbursementAmounts amounts = DisbursementAmounts.fromLoanAccount(loanAccount);
        Borrower borrower = application.getBorrower();

        String holderName = borrower.getAccountHolderName();
        if (holderName == null || holderName.isBlank()) {
            holderName = borrower.getFullName();
        }

        DisbursementIntent liveIntent = disbursementIntentRepository
                .findLiveByLoanAccountId(loanAccount.getId())
                .orElse(null);

        return new DisbursementPreview(
                application.getId(),
                loanAccount.getId(),
                loanAccount.getAccountNumber(),
                application.getExternalLoanId(),
                amounts.principal(),
                amounts.processingFee(),
                amounts.netDisbursalAmount(),
                paymentModeSelector.selectPaymentMode(amounts.netDisbursalAmount()),
                holderName,
                borrower.getBankName(),
                borrower.getIfscCode(),
                BankAccountMasking.mask(borrower.getBankAccountNumber()),
                DisbursementPreview.BENEFICIARY_SOURCE_LIVE_BORROWER,
                liveIntent == null ? null : liveIntent.getId(),
                liveIntent == null ? null : liveIntent.getTranRefNo(),
                liveIntent == null ? null : liveIntent.getState().name()
        );
    }

    private static void ensurePreviewAllowed(LoanApplicationStatus status) {
        if (status != LoanApplicationStatus.APPROVED_PENDING_DISBURSAL
                && status != LoanApplicationStatus.DISBURSEMENT_RETRY) {
            throw new BusinessRuleViolationException(
                    "DISBURSEMENT_NOT_ALLOWED",
                    "Disbursement preview is only available for applications pending disbursal or disbursement retry.",
                    Map.of("status", status.name())
            );
        }
    }
}
