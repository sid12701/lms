package com.bhawana.lms.web;

import com.bhawana.lms.domain.LoanAccount;
import com.bhawana.lms.domain.LoanApplication;
import com.bhawana.lms.service.LoanApplicationService;
import java.util.UUID;

public final class LspLoanApplicationResponses {

    private LspLoanApplicationResponses() {
    }

    public static LspLoanApplicationApiController.LspLoanApplicationDetailResponse toDetailResponse(
            LoanApplication application,
            LoanApplicationService loanApplicationService
    ) {
        UUID applicationId = application.getId();
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
                        loanApplicationService.getLoanAccount(applicationId).orElse(null),
                        loanApplicationService.getLoanRepaymentScheduleSummary(applicationId).orElse(null),
                        loanApplicationService.getLoanDelinquencySummary(applicationId).orElse(null)
                ),
                loanApplicationService.getLatestActivity(applicationId)
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
            LoanApplicationService.LoanRepaymentScheduleSummary repaymentScheduleSummary,
            LoanApplicationService.LoanDelinquencySummary delinquencySummary
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
            LoanApplicationService.LoanApplicationLastActivity activity
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

    private static String maskAadharNumber(String value) {
        if (value == null || value.length() < 4) {
            return value;
        }
        return "XXXXXXXX" + value.substring(value.length() - 4);
    }

}
