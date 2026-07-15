package com.bhawana.lms.service;

import com.bhawana.lms.domain.DisbursementPaymentMode;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

@Component
public class DisbursementPaymentModeSelector {

    private final LoanDisbursementMockProperties mockProperties;

    public DisbursementPaymentModeSelector(LoanDisbursementMockProperties mockProperties) {
        this.mockProperties = mockProperties;
    }

    public DisbursementPaymentMode selectPaymentMode(BigDecimal netDisbursalAmount) {
        return netDisbursalAmount.compareTo(mockProperties.getImpsMaxAmount()) > 0
                ? DisbursementPaymentMode.NEFT
                : DisbursementPaymentMode.IMPS;
    }
}
