package com.bhawana.lms.web;

import com.bhawana.lms.domain.LoanForeclosureQuote;
import com.bhawana.lms.domain.LoanPaymentTransaction;

/**
 * LSP-surface mappers for the loan servicing endpoints. The field mapping is
 * shared with the ops surface via {@link LoanServicingResponseFields}; the LSP
 * wire records remain separate so the partner contract can evolve on its own.
 */
public final class LspLoanApiResponses {

    private LspLoanApiResponses() {
    }

    public static LspLoanApiController.LspPaymentTransactionResponse toPaymentTransactionResponse(
            LoanPaymentTransaction paymentTransaction
    ) {
        return LoanServicingResponseFields.PaymentTransactionFields.from(paymentTransaction).toLsp();
    }

    public static LspLoanApiController.LspForeclosureQuoteResponse toForeclosureQuoteResponse(
            LoanForeclosureQuote quote
    ) {
        return LoanServicingResponseFields.ForeclosureQuoteFields.from(quote).toLsp();
    }
}
