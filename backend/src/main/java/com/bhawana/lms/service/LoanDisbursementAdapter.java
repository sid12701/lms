package com.bhawana.lms.service;

import com.bhawana.lms.domain.DisbursementDeclineKind;
import com.bhawana.lms.domain.DisbursementDisposition;
import com.bhawana.lms.domain.DisbursementPaymentMode;
import java.math.BigDecimal;

/**
 * Seam between the LMS and the bank money-movement provider. The production ICICI Composite Pay
 * adapter will slot in here later; {@link MockLoanDisbursementAdapter} mirrors the real lifecycle
 * (payment request → accepted/terminal, then status check for pending transactions).
 */
public interface LoanDisbursementAdapter {

    /** Stable provider identifier persisted before the network call for crash-safe reconciliation. */
    String providerName();

    /**
     * Submits a payment request (ICICI {@code /composite-payment}). For IMPS the result is often
     * terminal on the spot; for NEFT (and IMPS timeout codes) it is {@link DisbursementDisposition#PENDING}
     * and the caller must poll {@link #checkStatus(DisbursementStatusQuery)}.
     */
    DisbursementResult requestDisbursement(DisbursementCommand command);

    /**
     * Polls the terminal status of a previously accepted transaction (ICICI {@code /composite-status}).
     * The two-layer response mirrors ICICI: {@code checkStatusCode} reports whether the status query
     * itself succeeded; the {@link DisbursementStatusResult#disposition()} is only meaningful when the
     * query succeeded ({@link DisbursementStatusResult#isQueryResolved()}).
     */
    DisbursementStatusResult checkStatus(DisbursementStatusQuery query);

    record DisbursementCommand(
            String loanAccountNumber,
            BigDecimal amount,
            String borrowerName,
            String externalLoanId,
            String lspCode,
            DisbursementPaymentMode paymentMode,
            String tranRefNo,
            String beneficiaryAccountNumber,
            String beneficiaryIfsc
    ) {
    }

    record DisbursementResult(
            String providerName,
            String providerRequestId,
            String providerStatus,
            DisbursementPaymentMode paymentMode,
            DisbursementDisposition disposition,
            DisbursementDeclineKind declineKind,
            String actCode,
            String bankRrn,
            String message,
            String responsePayloadJson
    ) {
    }

    record DisbursementStatusQuery(
            String tranRefNo,
            DisbursementPaymentMode paymentMode,
            String beneficiaryIfsc,
            int priorStatusCheckCount
    ) {
    }

    /**
     * @param checkStatusCode ICICI {@code CheckStatusCode} — "0" when the status query resolved;
     *                        "17"/"100" when the transaction is not yet queryable (retry later).
     */
    record DisbursementStatusResult(
            String checkStatusCode,
            String checkStatusMessage,
            DisbursementDisposition disposition,
            DisbursementDeclineKind declineKind,
            String actCode,
            String bankRrn,
            String message,
            String responsePayloadJson
    ) {
        /** True when the status query itself succeeded and {@link #disposition()} can be trusted. */
        public boolean isQueryResolved() {
            return "0".equals(checkStatusCode);
        }
    }
}
