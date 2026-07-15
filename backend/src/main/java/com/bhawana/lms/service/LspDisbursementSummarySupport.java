package com.bhawana.lms.service;

import com.bhawana.lms.common.money.Money;
import com.bhawana.lms.domain.DisbursementDeclineKind;
import com.bhawana.lms.domain.LoanAccount;
import com.bhawana.lms.domain.LoanAccountStatus;
import com.bhawana.lms.domain.LoanDisbursementRequestLog;
import java.math.BigDecimal;

public final class LspDisbursementSummarySupport {

    private LspDisbursementSummarySupport() {
    }

    public static LspDisbursementSummary toSummary(
            LoanAccount loanAccount,
            LoanDisbursementRequestLog latestRequest
    ) {
        if (loanAccount == null || latestRequest == null) {
            return null;
        }
        // ADR 0004: request-log amount is the net cash sent to the rail (principal − fee).
        BigDecimal grossAmount = loanAccount.getPrincipalAmount();
        BigDecimal netDisbursedAmount = Money.scale(latestRequest.getAmount());

        String status = resolveStatus(loanAccount, latestRequest);
        String failureReasonCode = null;
        String failureReason = null;
        if (loanAccount.getStatus() == LoanAccountStatus.DISBURSEMENT_FAILED) {
            DisbursementDeclineKind declineKind = latestRequest.getDeclineKind();
            if (declineKind != null && declineKind != DisbursementDeclineKind.NONE) {
                failureReasonCode = declineKind.name();
                failureReason = resolveFailureReason(declineKind, latestRequest);
            } else if (latestRequest.getProviderActCode() != null) {
                failureReasonCode = latestRequest.getProviderActCode();
                failureReason = "Disbursement declined by provider (ActCode "
                        + latestRequest.getProviderActCode() + ").";
            } else {
                failureReason = "Disbursement failed.";
            }
        }

        return new LspDisbursementSummary(
                status,
                failureReasonCode,
                failureReason,
                loanAccount.getDisbursedAt(),
                grossAmount,
                netDisbursedAmount
        );
    }

    private static String resolveStatus(LoanAccount loanAccount, LoanDisbursementRequestLog latestRequest) {
        if (latestRequest.getProviderStatus() != null) {
            return latestRequest.getProviderStatus();
        }
        return loanAccount.getStatus().name();
    }

    private static String resolveFailureReason(
            DisbursementDeclineKind declineKind,
            LoanDisbursementRequestLog latestRequest
    ) {
        String actCodeSuffix = latestRequest.getProviderActCode() == null
                ? ""
                : " (ActCode " + latestRequest.getProviderActCode() + ")";
        return switch (declineKind) {
            case BUSINESS -> "Disbursement declined by provider (business decline)" + actCodeSuffix + ".";
            case TECHNICAL -> "Disbursement failed due to a technical decline" + actCodeSuffix + ".";
            case NONE -> "Disbursement failed.";
        };
    }
}
