package com.bhawana.lms.service;

import com.bhawana.lms.domain.DisbursementPaymentMode;
import java.math.BigDecimal;
import java.util.UUID;

public record DisbursementPreview(
        UUID applicationId,
        UUID loanAccountId,
        String loanAccountNumber,
        String externalLoanId,
        BigDecimal principal,
        BigDecimal processingFee,
        BigDecimal netDisbursalAmount,
        DisbursementPaymentMode paymentMode,
        String beneficiaryAccountHolderName,
        String beneficiaryBankName,
        String beneficiaryIfsc,
        String maskedBeneficiaryAccountNumber,
        /** Until Spec S5 ships: always live borrower row (not approval-time snapshot). */
        String beneficiarySource,
        UUID pendingIntentId,
        String pendingIntentTranRefNo,
        String pendingIntentState
) {
    public static final String BENEFICIARY_SOURCE_LIVE_BORROWER = "LIVE_BORROWER";
}
