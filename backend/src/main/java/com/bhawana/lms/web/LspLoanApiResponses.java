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
                paymentTransaction.getId(),
                paymentTransaction.getLoanAccount().getId(),
                paymentTransaction.getRepaymentInstallment() == null
                        ? null
                        : paymentTransaction.getRepaymentInstallment().getId(),
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
                paymentTransaction.getCreatedAt(),
                paymentTransaction.getUpdatedAt()
        );
    }

    public static LspLoanApiController.LspForeclosureQuoteResponse toForeclosureQuoteResponse(
            LoanForeclosureQuote quote
    ) {
        return new LspLoanApiController.LspForeclosureQuoteResponse(
                quote.getId(),
                quote.getLoanAccount().getId(),
                quote.getVersion(),
                quote.getRequestedByUsername(),
                quote.getExecutedByUsername(),
                quote.getEffectiveDate(),
                quote.getOutstandingPrincipal(),
                quote.getOutstandingInterest(),
                quote.getSettlementAmount(),
                quote.getStatus().name(),
                quote.getExecutedAt(),
                quote.getCreatedAt(),
                quote.getUpdatedAt()
        );
    }
}
