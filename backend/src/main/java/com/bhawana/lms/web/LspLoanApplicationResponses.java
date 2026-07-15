package com.bhawana.lms.web;

import com.bhawana.lms.common.pii.AadhaarMasking;
import com.bhawana.lms.common.pii.BankAccountMasking;
import com.bhawana.lms.domain.LoanAccount;
import com.bhawana.lms.domain.LoanApplication;
import com.bhawana.lms.domain.LoanApplicationDocumentChecklist;
import com.bhawana.lms.domain.LoanDisbursementRequestLog;
import com.bhawana.lms.domain.LoanRepaymentScheduleInstallment;
import com.bhawana.lms.service.LoanApplicationDetailAssembler.LoanApplicationDetailView;
import com.bhawana.lms.service.LoanApplicationLastActivity;
import com.bhawana.lms.service.LoanDelinquencySummary;
import com.bhawana.lms.service.LoanDelinquencySupport;
import com.bhawana.lms.service.LoanRepaymentScheduleSummary;
import com.bhawana.lms.service.LspDisbursementSummary;
import com.bhawana.lms.service.LspDisbursementSummarySupport;
import java.time.LocalDate;

public final class LspLoanApplicationResponses {

    private LspLoanApplicationResponses() {
    }

    public static LspLoanApplicationApiController.LspLoanApplicationDetailResponse toDetailResponse(
            LoanApplicationDetailView detail
    ) {
        LoanApplication application = detail.application();
        return new LspLoanApplicationApiController.LspLoanApplicationDetailResponse(
                application.getId(),
                application.getBorrower().getId(),
                application.getBorrower().getFullName(),
                application.getBorrower().getEmail(),
                application.getBorrower().getMobile(),
                application.getBorrower().getDateOfBirth(),
                application.getBorrower().getGender(),
                application.getBorrower().getMaritalStatus(),
                application.getBorrower().getFatherName(),
                AadhaarMasking.mask(application.getBorrower().getAadharNumber()),
                application.getBorrower().getPan(),
                application.getLoanProduct().getId(),
                application.getLoanProduct().getCode(),
                application.getLoanProduct().getName(),
                application.getRequestedAmount(),
                application.getLoanProductVersion().getInterestRate(),
                application.getRequestedTenureMonths(),
                application.getExternalLoanId(),
                application.getLsp().getId(),
                application.getLsp().getCode(),
                application.getLsp().getName(),
                application.getBorrower().getAddressLine1(),
                application.getBorrower().getAddressLine2(),
                application.getBorrower().getCity(),
                application.getBorrower().getState(),
                application.getBorrower().getAddressZipCode(),
                application.getBorrower().getSpouseName(),
                application.getBorrower().getEmploymentType(),
                application.getBorrower().getOrganizationName(),
                application.getBorrower().getEmployeeId(),
                application.getBorrower().getEmploymentCity(),
                application.getBorrower().getEmploymentState(),
                application.getBorrower().getEmploymentZip(),
                application.getBorrower().getMonthlyIncome(),
                application.getBorrower().getAnnualIncome(),
                BankAccountMasking.mask(application.getBorrower().getBankAccountNumber()),
                application.getBorrower().getBankName(),
                application.getBorrower().getIfscCode(),
                application.getBorrower().getAccountHolderName(),
                application.getBorrower().getReferencePersonName(),
                application.getBorrower().getReferencePersonNumber(),
                application.getSourceChannel(),
                application.getStatus().name(),
                application.getInvalidReasonCode() == null ? null : application.getInvalidReasonCode().name(),
                application.getInvalidReasonText(),
                application.getInvalidatedByUsername(),
                application.getInvalidatedAt(),
                application.getCreatedAt(),
                application.getUpdatedAt(),
                toLoanAccountSummary(
                        detail.loanAccount().orElse(null),
                        detail.repaymentScheduleSummary().orElse(null),
                        detail.delinquencySummary().orElse(null),
                        detail.latestDisbursementRequest().orElse(null)
                ),
                detail.lastActivity()
                        .map(LspLoanApplicationResponses::toLastActivityResponse)
                        .orElse(null)
        );
    }

    public static LspLoanApplicationApiController.LspLoanApplicationResponse toResponse(LoanApplication application) {
        return new LspLoanApplicationApiController.LspLoanApplicationResponse(
                application.getId(),
                application.getBorrower().getId(),
                application.getBorrower().getFullName(),
                application.getBorrower().getEmail(),
                application.getBorrower().getMobile(),
                application.getBorrower().getDateOfBirth(),
                application.getBorrower().getGender(),
                application.getBorrower().getMaritalStatus(),
                application.getBorrower().getFatherName(),
                AadhaarMasking.mask(application.getBorrower().getAadharNumber()),
                application.getBorrower().getPan(),
                application.getLoanProduct().getId(),
                application.getLoanProduct().getCode(),
                application.getLoanProduct().getName(),
                application.getRequestedAmount(),
                application.getLoanProductVersion().getInterestRate(),
                application.getRequestedTenureMonths(),
                application.getExternalLoanId(),
                application.getLsp().getId(),
                application.getLsp().getCode(),
                application.getLsp().getName(),
                application.getBorrower().getAddressLine1(),
                application.getBorrower().getAddressLine2(),
                application.getBorrower().getCity(),
                application.getBorrower().getState(),
                application.getBorrower().getAddressZipCode(),
                application.getBorrower().getSpouseName(),
                application.getBorrower().getEmploymentType(),
                application.getBorrower().getOrganizationName(),
                application.getBorrower().getEmployeeId(),
                application.getBorrower().getEmploymentCity(),
                application.getBorrower().getEmploymentState(),
                application.getBorrower().getEmploymentZip(),
                application.getBorrower().getMonthlyIncome(),
                application.getBorrower().getAnnualIncome(),
                BankAccountMasking.mask(application.getBorrower().getBankAccountNumber()),
                application.getBorrower().getBankName(),
                application.getBorrower().getIfscCode(),
                application.getBorrower().getAccountHolderName(),
                application.getBorrower().getReferencePersonName(),
                application.getBorrower().getReferencePersonNumber(),
                application.getSourceChannel(),
                application.getStatus().name(),
                application.getCreatedAt()
        );
    }

    private static LspLoanApplicationApiController.LspLoanAccountSummaryResponse toLoanAccountSummary(
            LoanAccount loanAccount,
            LoanRepaymentScheduleSummary repaymentScheduleSummary,
            LoanDelinquencySummary delinquencySummary,
            LoanDisbursementRequestLog latestDisbursementRequest
    ) {
        if (loanAccount == null) {
            return null;
        }
        return new LspLoanApplicationApiController.LspLoanAccountSummaryResponse(
                loanAccount.getId(),
                loanAccount.getAccountNumber(),
                loanAccount.getStatus(),
                loanAccount.getPrincipalAmount(),
                loanAccount.getTenureMonths(),
                loanAccount.getApprovedAt(),
                loanAccount.getCreatedAt(),
                loanAccount.getClosureReason() == null ? null : loanAccount.getClosureReason().name(),
                loanAccount.getClosedAt(),
                loanAccount.getClosedByUsername(),
                delinquencySummary == null ? null : new LspLoanApplicationApiController.LspLoanDelinquencySummaryResponse(
                        delinquencySummary.maxDaysPastDue(),
                        delinquencySummary.bucket().name(),
                        delinquencySummary.overdueInstallmentCount(),
                        delinquencySummary.overdueAmount()
                ),
                repaymentScheduleSummary == null ? null : new LspLoanApplicationApiController.LspLoanRepaymentScheduleSummaryResponse(
                        repaymentScheduleSummary.installmentCount(),
                        repaymentScheduleSummary.installmentAmount(),
                        repaymentScheduleSummary.firstDueDate(),
                        repaymentScheduleSummary.finalDueDate()
                ),
                toDisbursementSummaryResponse(
                        LspDisbursementSummarySupport.toSummary(loanAccount, latestDisbursementRequest)
                )
        );
    }

    private static LspLoanApplicationApiController.LspDisbursementSummaryResponse toDisbursementSummaryResponse(
            LspDisbursementSummary summary
    ) {
        if (summary == null) {
            return null;
        }
        return new LspLoanApplicationApiController.LspDisbursementSummaryResponse(
                summary.status(),
                summary.failureReasonCode(),
                summary.failureReason(),
                summary.disbursedAt(),
                summary.grossAmount(),
                summary.netDisbursedAmount()
        );
    }

    private static LspLoanApplicationApiController.LoanApplicationLastActivityResponse toLastActivityResponse(
            LoanApplicationLastActivity activity
    ) {
        return new LspLoanApplicationApiController.LoanApplicationLastActivityResponse(
                activity.activityType(),
                activity.actorUsername(),
                activity.summary(),
                activity.detail(),
                activity.correlationId(),
                activity.occurredAt()
        );
    }

    public static LspLoanApplicationApiController.LspDocumentChecklistDetailResponse toDocumentChecklistDetailResponse(
            LoanApplicationDocumentChecklist checklistItem
    ) {
        return new LspLoanApplicationApiController.LspDocumentChecklistDetailResponse(
                checklistItem.getId(),
                checklistItem.getLoanApplication().getId(),
                checklistItem.getDocumentType().name(),
                checklistItem.getDocumentType().getDisplayName(),
                checklistItem.isRequired(),
                checklistItem.getStatus().name(),
                checklistItem.getNote(),
                checklistItem.getFileName(),
                checklistItem.getFileReference(),
                checklistItem.getContentType(),
                checklistItem.getSourceReference(),
                checklistItem.isLmsManagedContent(),
                checklistItem.getFileSizeBytes(),
                checklistItem.getUploadedAt(),
                checklistItem.getUploadedByUsername(),
                checklistItem.getUpdatedByUsername(),
                checklistItem.getCreatedAt(),
                checklistItem.getUpdatedAt()
        );
    }

    public static LspLoanApplicationApiController.LspRepaymentScheduleInstallmentResponse toRepaymentScheduleInstallmentResponse(
            LoanRepaymentScheduleInstallment installment,
            LocalDate businessDate
    ) {
        int daysPastDue = LoanDelinquencySupport.calculateDaysPastDue(
                installment,
                businessDate
        );
        return new LspLoanApplicationApiController.LspRepaymentScheduleInstallmentResponse(
                installment.getId(),
                installment.getLoanAccount().getId(),
                installment.getInstallmentNumber(),
                installment.getDueDate(),
                installment.getOpeningPrincipal(),
                installment.getPrincipalDue(),
                installment.getInterestDue(),
                installment.getInstallmentAmount(),
                installment.getClosingPrincipal(),
                installment.getStatus().name(),
                installment.getPaidPrincipal(),
                installment.getPaidInterest(),
                installment.getPaidAmount(),
                installment.getOutstandingAmount(),
                daysPastDue,
                LoanDelinquencySupport.resolveDelinquencyBucket(daysPastDue).name(),
                installment.getCreatedAt()
        );
    }

}
