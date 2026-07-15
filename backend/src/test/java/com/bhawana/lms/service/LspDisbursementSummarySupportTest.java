package com.bhawana.lms.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.bhawana.lms.domain.DisbursementDeclineKind;
import com.bhawana.lms.domain.LoanAccount;
import com.bhawana.lms.domain.LoanAccountStatus;
import com.bhawana.lms.domain.LoanDisbursementRequestLog;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LspDisbursementSummarySupportTest {

    @Mock private LoanAccount loanAccount;
    @Mock private LoanDisbursementRequestLog requestLog;

    @Test
    void returnsNullWhenNoDisbursementAttemptExists() {
        assertNull(LspDisbursementSummarySupport.toSummary(loanAccount, null));
    }

    @Test
    void disbursedLoanUsesPrincipalAsGrossAndRequestLogAmountAsNet() {
        when(loanAccount.getStatus()).thenReturn(LoanAccountStatus.DISBURSED);
        when(loanAccount.getPrincipalAmount()).thenReturn(new BigDecimal("46012.50"));
        Instant disbursedAt = Instant.parse("2026-06-01T10:00:00Z");
        when(loanAccount.getDisbursedAt()).thenReturn(disbursedAt);
        when(requestLog.getAmount()).thenReturn(new BigDecimal("45000.00"));
        when(requestLog.getProviderStatus()).thenReturn("SUCCESS");

        LspDisbursementSummary summary =
                LspDisbursementSummarySupport.toSummary(loanAccount, requestLog);

        assertEquals("SUCCESS", summary.status());
        assertEquals(disbursedAt, summary.disbursedAt());
        assertEquals(new BigDecimal("46012.50"), summary.grossAmount());
        assertEquals(new BigDecimal("45000.00"), summary.netDisbursedAmount());
        assertNull(summary.failureReasonCode());
        assertNull(summary.failureReason());
    }

    @Test
    void failedDisbursementIncludesDeclineReasonAndPrincipalGross() {
        when(loanAccount.getStatus()).thenReturn(LoanAccountStatus.DISBURSEMENT_FAILED);
        when(loanAccount.getPrincipalAmount()).thenReturn(new BigDecimal("45000.00"));
        when(loanAccount.getDisbursedAt()).thenReturn(null);
        when(requestLog.getAmount()).thenReturn(new BigDecimal("43987.50"));
        when(requestLog.getProviderStatus()).thenReturn("FAILED");
        when(requestLog.getDeclineKind()).thenReturn(DisbursementDeclineKind.BUSINESS);
        when(requestLog.getProviderActCode()).thenReturn("52");

        LspDisbursementSummary summary =
                LspDisbursementSummarySupport.toSummary(loanAccount, requestLog);

        assertEquals("FAILED", summary.status());
        assertEquals("BUSINESS", summary.failureReasonCode());
        assertEquals(
                "Disbursement declined by provider (business decline) (ActCode 52).",
                summary.failureReason()
        );
        assertEquals(new BigDecimal("45000.00"), summary.grossAmount());
        assertEquals(new BigDecimal("43987.50"), summary.netDisbursedAmount());
        assertNull(summary.disbursedAt());
    }
}
