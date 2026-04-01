package com.bhawana.lms.service;

import java.math.BigDecimal;

public interface LoanDisbursementAdapter {

    DisbursementResult requestDisbursement(DisbursementCommand command);

    record DisbursementCommand(
            String loanAccountNumber,
            BigDecimal amount,
            String borrowerName,
            String externalLoanId,
            String lspCode
    ) {
    }

    record DisbursementResult(
            String providerName,
            String providerRequestId,
            String providerStatus,
            String responsePayloadJson
    ) {
    }
}
