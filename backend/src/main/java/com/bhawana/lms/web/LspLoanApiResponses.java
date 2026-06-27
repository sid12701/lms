package com.bhawana.lms.web;

import com.bhawana.lms.domain.LoanForeclosureQuote;
import com.bhawana.lms.domain.LoanPaymentTransaction;

public final class LspLoanApiResponses {

    private LspLoanApiResponses() {
    }

    public static LspLoanApiController.LspPaymentTransactionResponse toPaymentTransactionResponse(
            LoanPaymentTransaction paymentTransaction
    ) {
        return new LspLoanApiController.LspPaymentTransactionResponse(
                paymentTransaction.getId().toString(),
                paymentTransaction.getLoanAccount().getId().toString(),
                paymentTransaction.getRepaymentInstallment() == null
                        ? null
                        : paymentTransaction.getRepaymentInstallment().getId().toString(),
                paymentTransaction.getActorUsername(),
                paymentTransaction.getAmount(),
                paymentTransaction.getPaymentDate(),
                paymentTransaction.getReference(),
                paymentTransaction.getChannel().name(),
                paymentTransaction.getStatus().name(),
                paymentTransaction.getAllocatedAmount(),
                paymentTransaction.getUnallocatedAmount(),
                paymentTransaction.getNote(),
                paymentTransaction.getCorrelationId(),
                paymentTransaction.getCreatedAt().toString(),
                paymentTransaction.getUpdatedAt().toString()
        );
    }

    public static LspLoanApiController.LspForeclosureQuoteResponse toForeclosureQuoteResponse(
            LoanForeclosureQuote quote
    ) {
        return new LspLoanApiController.LspForeclosureQuoteResponse(
                quote.getId().toString(),
                quote.getLoanAccount().getId().toString(),
                quote.getVersion(),
                quote.getRequestedByUsername(),
                quote.getExecutedByUsername(),
                quote.getEffectiveDate(),
                quote.getOutstandingPrincipal(),
                quote.getOutstandingInterest(),
                quote.getSettlementAmount(),
                quote.getStatus().name(),
                quote.getExecutedAt() == null ? null : quote.getExecutedAt().toString(),
                quote.getCreatedAt().toString(),
                quote.getUpdatedAt().toString()
        );
    }
}
