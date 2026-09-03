package com.bhawana.lms.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import com.bhawana.lms.domain.LoanAccount;
import com.bhawana.lms.domain.LoanAccountStatus;
import com.bhawana.lms.domain.LoanApplication;
import com.bhawana.lms.domain.LoanProduct;
import com.bhawana.lms.domain.LoanProductVersion;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LoanEventPayloadsDisbursementTest {

    @Mock private LoanApplication application;
    @Mock private LoanAccount loanAccount;
    @Mock private LoanProduct loanProduct;
    @Mock private LoanProductVersion loanProductVersion;

    @Test
    void disbursementPayloadCarriesPersistedFeeAndNetCash() {
        when(application.getId()).thenReturn(UUID.randomUUID());
        when(loanAccount.getId()).thenReturn(UUID.randomUUID());
        when(loanAccount.getAccountNumber()).thenReturn("LMS-LN-1");
        when(loanAccount.getStatus()).thenReturn(LoanAccountStatus.DISBURSED);
        when(loanAccount.getPrincipalAmount()).thenReturn(new BigDecimal("150000"));
        when(loanAccount.getProcessingFeeAmount()).thenReturn(new BigDecimal("2250.00"));

        Map<String, Object> payload = LoanEventPayloads.disbursement(application, loanAccount);

        assertEquals(new BigDecimal("150000"), payload.get("principalAmount"));
        assertEquals(new BigDecimal("2250.00"), payload.get("processingFeeAmount"));
        assertEquals(new BigDecimal("147750.00"), payload.get("netDisbursedAmount"));
    }

    @Test
    void disbursementPayloadComputesFeeWhenNotYetPersisted() {
        when(application.getId()).thenReturn(UUID.randomUUID());
        when(loanAccount.getId()).thenReturn(UUID.randomUUID());
        when(loanAccount.getAccountNumber()).thenReturn("LMS-LN-2");
        when(loanAccount.getStatus()).thenReturn(LoanAccountStatus.DISBURSEMENT_REQUESTED);
        when(loanAccount.getPrincipalAmount()).thenReturn(new BigDecimal("150000"));
        when(loanAccount.getProcessingFeeAmount()).thenReturn(null);
        when(loanAccount.getLoanProductVersion()).thenReturn(loanProductVersion);
        when(loanProductVersion.getProcessingFeeRate()).thenReturn(new BigDecimal("1.5"));

        Map<String, Object> payload = LoanEventPayloads.disbursement(application, loanAccount);

        assertEquals(new BigDecimal("2250.00"), payload.get("processingFeeAmount"));
        assertEquals(new BigDecimal("147750.00"), payload.get("netDisbursedAmount"));
    }
}
