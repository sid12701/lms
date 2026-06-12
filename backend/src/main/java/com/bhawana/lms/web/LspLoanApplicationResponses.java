package com.bhawana.lms.web;

import com.bhawana.lms.domain.LoanAccount;
import com.bhawana.lms.domain.LoanApplication;
import com.bhawana.lms.domain.LoanApplicationDocumentChecklist;
import com.bhawana.lms.domain.LoanRepaymentScheduleInstallment;
import com.bhawana.lms.service.LoanApplicationDetailAssembler.LoanApplicationDetailView;
import com.bhawana.lms.service.LoanApplicationLastActivity;
import com.bhawana.lms.service.LoanDelinquencySummary;
import com.bhawana.lms.service.LoanDelinquencySupport;
import com.bhawana.lms.service.LoanRepaymentScheduleSummary;
import java.time.LocalDate;

public final class LspLoanApplicationResponses {

    private LspLoanApplicationResponses() {
    }

    public static LspLoanApplicationApiController.LspLoanApplicationDetailResponse toDetailResponse(
            LoanApplicationDetailView detail
    ) {
        LoanApplication application = detail.application();
        return new LspLoanApplicationApiController.LspLoanApplicationDetailResponse(
                application.getId().toString(),
                application.getBorrower().getId().toString(),
                application.getBorrower().getFullName(),
                application.getBorrower().getEmail(),
                application.getBorrower().getMobile(),
                application.getBorrower().getDateOfBirth(),
                application.getBorrower().getGender(),
                application.getBorrower().getMaritalStatus(),
                application.getBorrower().getFatherName(),
                maskAadharNumber(application.getBorrower().getAadharNumber()),
                application.getBorrower().getPan(),
                application.getLoanProduct().getId().toString(),
                application.getLoanProduct().getCode(),
                application.getLoanProduct().getName(),
                application.getRequestedAmount(),
                application.getLoanProduct().getInterestRate(),
                application.getRequestedTenureMonths(),
                application.getExternalLoanId(),
                application.getLsp().getId().toString(),
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
                application.getBorrower().getBankAccountNumber(),
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
                application.getInvalidatedAt() == null ? null : application.getInvalidatedAt().toString(),
                application.getCreatedAt().toString(),
                application.getUpdatedAt().toString(),
                toLoanAccountSummary(
                        detail.loanAccount().orElse(null),
                        detail.repaymentScheduleSummary().orElse(null),
                        detail.delinquencySummary().orElse(null)
                ),
                detail.lastActivity()
                        .map(LspLoanApplicationResponses::toLastActivityResponse)
                        .orElse(null)
        );
    }

    public static LspLoanApplicationApiController.LspLoanApplicationResponse toResponse(LoanApplication application) {
        return new LspLoanApplicationApiController.LspLoanApplicationResponse(
                application.getId().toString(),
                application.getBorrower().getId().toString(),
                application.getBorrower().getFullName(),
                application.getBorrower().getEmail(),
                application.getBorrower().getMobile(),
                application.getBorrower().getDateOfBirth(),
                application.getBorrower().getGender(),
                application.getBorrower().getMaritalStatus(),
                application.getBorrower().getFatherName(),
                maskAadharNumber(application.getBorrower().getAadharNumber()),
                application.getBorrower().getPan(),
                application.getLoanProduct().getId().toString(),
                application.getLoanProduct().getCode(),
                application.getLoanProduct().getName(),
                application.getRequestedAmount(),
                application.getLoanProduct().getInterestRate(),
                application.getRequestedTenureMonths(),
                application.getExternalLoanId(),
                application.getLsp().getId().toString(),
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
                application.getBorrower().getBankAccountNumber(),
                application.getBorrower().getBankName(),
                application.getBorrower().getIfscCode(),
                application.getBorrower().getAccountHolderName(),
                application.getBorrower().getReferencePersonName(),
                application.getBorrower().getReferencePersonNumber(),
                application.getSourceChannel(),
                application.getStatus().name(),
                application.getCreatedAt().toString()
        );
    }

    private static LspLoanApplicationApiController.LspLoanAccountSummaryResponse toLoanAccountSummary(
            LoanAccount loanAccount,
            LoanRepaymentScheduleSummary repaymentScheduleSummary,
            LoanDelinquencySummary delinquencySummary
    ) {
        if (loanAccount == null) {
            return null;
        }
        return new LspLoanApplicationApiController.LspLoanAccountSummaryResponse(
                loanAccount.getId().toString(),
                loanAccount.getAccountNumber(),
                loanAccount.getStatus().name(),
                loanAccount.getPrincipalAmount(),
                loanAccount.getTenureMonths(),
                loanAccount.getApprovedAt().toString(),
                loanAccount.getCreatedAt().toString(),
                loanAccount.getClosureReason() == null ? null : loanAccount.getClosureReason().name(),
                loanAccount.getClosedAt() == null ? null : loanAccount.getClosedAt().toString(),
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
                )
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
                activity.occurredAt().toString()
        );
    }

    public static LspLoanApplicationApiController.LspDocumentChecklistDetailResponse toDocumentChecklistDetailResponse(
            LoanApplicationDocumentChecklist checklistItem
    ) {
        return new LspLoanApplicationApiController.LspDocumentChecklistDetailResponse(
                checklistItem.getId().toString(),
                checklistItem.getLoanApplication().getId().toString(),
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
                checklistItem.getCreatedAt().toString(),
                checklistItem.getUpdatedAt().toString()
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
                installment.getId().toString(),
                installment.getLoanAccount().getId().toString(),
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
                installment.getCreatedAt().toString()
        );
    }

    private static String maskAadharNumber(String value) {
        if (value == null || value.length() < 4) {
            return value;
        }
        return "XXXXXXXX" + value.substring(value.length() - 4);
    }

}
