package com.bhawana.lms.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import com.bhawana.lms.domain.LoanAccount;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AdminReportingProcessingFeeTest {

    @Mock private LoanAccount loanAccount;

    @Test
    void usesPersistedFeeWhenPresent() {
        when(loanAccount.getProcessingFeeAmount()).thenReturn(new BigDecimal("2250.00"));

        assertEquals(
                new BigDecimal("2250.00"),
                AdminReportingService.resolveProcessingFeeAmount(loanAccount)
        );
    }

    @Test
    void reportsZeroFeeForLegacyRowsWithNullPersistedFee() {
        when(loanAccount.getProcessingFeeAmount()).thenReturn(null);

        assertEquals(
                new BigDecimal("0.00"),
                AdminReportingService.resolveProcessingFeeAmount(loanAccount)
        );
    }
}
