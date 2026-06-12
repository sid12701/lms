package com.bhawana.lms.service;

import com.bhawana.lms.domain.LoanAccount;
import com.bhawana.lms.domain.LoanAccountClosureReason;
import com.bhawana.lms.domain.LoanApplication;
import com.bhawana.lms.domain.LoanApplicationStatus;
import com.bhawana.lms.domain.LoanApplicationStatusReasonCode;
import com.bhawana.lms.domain.LoanForeclosureQuote;
import com.bhawana.lms.domain.LoanPaymentTransaction;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class LoanWebhookPayloads {

    private LoanWebhookPayloads() {
    }

    public static Map<String, Object> loanCreated(LoanApplication application) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("loanApplicationId", application.getId());
        payload.put("externalLoanId", application.getExternalLoanId());
        payload.put("status", application.getStatus().name());
        payload.put("borrowerId", application.getBorrower().getId());
        payload.put("requestedAmount", application.getRequestedAmount());
        payload.put("tenureMonths", application.getRequestedTenureMonths());
        return payload;
    }

    public static Map<String, Object> loanStatusChanged(
            LoanApplication application,
            LoanApplicationStatus fromStatus,
            LoanApplicationStatus toStatus,
            LoanApplicationStatusReasonCode reasonCode
    ) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("loanApplicationId", application.getId());
        payload.put("externalLoanId", application.getExternalLoanId());
        payload.put("fromStatus", fromStatus.name());
        payload.put("toStatus", toStatus.name());
        payload.put("reasonCode", reasonCode == null ? null : reasonCode.name());
        payload.put(
                "invalidReasonCode",
                application.getInvalidReasonCode() == null ? null : application.getInvalidReasonCode().name()
        );
        payload.put("invalidReasonText", application.getInvalidReasonText());
        payload.put("invalidatedByUsername", application.getInvalidatedByUsername());
        payload.put("invalidatedAt", application.getInvalidatedAt());
        return payload;
    }

    public static Map<String, Object> documentsUploaded(
            LoanApplication application,
            List<String> completedRequiredDocumentTypes
    ) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("loanApplicationId", application.getId());
        payload.put("externalLoanId", application.getExternalLoanId());
        payload.put("allRequiredDocumentsUploaded", true);
        payload.put("documentTypes", completedRequiredDocumentTypes);
        return payload;
    }

    public static Map<String, Object> disbursement(LoanApplication application, LoanAccount loanAccount) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("loanApplicationId", application.getId());
        payload.put("loanAccountId", loanAccount.getId());
        payload.put("accountNumber", loanAccount.getAccountNumber());
        payload.put("loanAccountStatus", loanAccount.getStatus().name());
        payload.put("principalAmount", loanAccount.getPrincipalAmount());
        return payload;
    }

    public static Map<String, Object> repayment(
            LoanApplication application,
            LoanAccount loanAccount,
            LoanPaymentTransaction paymentTransaction
    ) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("loanApplicationId", application.getId());
        payload.put("loanAccountId", loanAccount.getId());
        payload.put("paymentTransactionId", paymentTransaction.getId());
        payload.put("reference", paymentTransaction.getReference());
        payload.put("amount", paymentTransaction.getAmount());
        payload.put("allocatedAmount", paymentTransaction.getAllocatedAmount());
        payload.put("unallocatedAmount", paymentTransaction.getUnallocatedAmount());
        payload.put("paymentStatus", paymentTransaction.getStatus().name());
        payload.put("paymentDate", paymentTransaction.getPaymentDate());
        return payload;
    }

    public static Map<String, Object> loanFullyRepaid(LoanApplication application, LoanAccount loanAccount) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("loanApplicationId", application.getId());
        payload.put("loanAccountId", loanAccount.getId());
        payload.put("accountNumber", loanAccount.getAccountNumber());
        payload.put("closureReason", loanAccount.getClosureReason().name());
        payload.put("closedAt", loanAccount.getClosedAt());
        return payload;
    }

    public static Map<String, Object> foreclosure(
            LoanApplication application,
            LoanAccount loanAccount,
            LoanForeclosureQuote quote
    ) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("loanApplicationId", application.getId());
        payload.put("loanAccountId", loanAccount.getId());
        payload.put("accountNumber", loanAccount.getAccountNumber());
        payload.put("foreclosureQuoteId", quote.getId());
        payload.put("quoteVersion", quote.getVersion());
        payload.put("effectiveDate", quote.getEffectiveDate());
        payload.put("settlementAmount", quote.getSettlementAmount());
        payload.put("closureReason", LoanAccountClosureReason.FORECLOSURE.name());
        payload.put("closedAt", loanAccount.getClosedAt());
        payload.put("applicationStatus", application.getStatus().name());
        return payload;
    }

    public static Map<String, Object> foreclosureQuote(
            LoanApplication application,
            LoanAccount loanAccount,
            LoanForeclosureQuote quote
    ) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("loanApplicationId", application.getId());
        payload.put("loanAccountId", loanAccount.getId());
        payload.put("accountNumber", loanAccount.getAccountNumber());
        payload.put("foreclosureQuoteId", quote.getId());
        payload.put("quoteVersion", quote.getVersion());
        payload.put("effectiveDate", quote.getEffectiveDate());
        payload.put("settlementAmount", quote.getSettlementAmount());
        return payload;
    }
}
