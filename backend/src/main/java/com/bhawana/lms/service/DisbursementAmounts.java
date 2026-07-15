package com.bhawana.lms.service;

import com.bhawana.lms.common.api.error.BusinessRuleViolationException;
import com.bhawana.lms.common.money.LoanFeeCalculator;
import com.bhawana.lms.common.money.Money;
import com.bhawana.lms.domain.LoanAccount;
import java.math.BigDecimal;
import java.util.Map;

/**
 * ADR 0004 — principal, processing fee, and net cash to the borrower for a disbursement.
 * Shared by the command path and the ops read-only preview so displayed figures cannot drift.
 */
public record DisbursementAmounts(
        BigDecimal principal,
        BigDecimal processingFee,
        BigDecimal netDisbursalAmount
) {

    public static DisbursementAmounts fromLoanAccount(LoanAccount loanAccount) {
        BigDecimal principal = Money.scale(loanAccount.getPrincipalAmount());
        BigDecimal fee = LoanFeeCalculator.computeProcessingFee(
                principal,
                loanAccount.getLoanProductVersion().getProcessingFeeRate()
        );
        BigDecimal net = Money.scale(principal.subtract(fee));
        if (net.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessRuleViolationException(
                    "DISBURSEMENT_FEE_EXCEEDS_PRINCIPAL",
                    "Processing fee leaves no net amount to disburse to the borrower.",
                    Map.of("principal", principal.toPlainString(), "processingFee", fee.toPlainString())
            );
        }
        return new DisbursementAmounts(principal, fee, net);
    }
}
