package com.bhawana.lms.service;

import com.bhawana.lms.domain.LoanDelinquencyBucket;
import java.math.BigDecimal;

public record LoanDelinquencySummary(
        int maxDaysPastDue,
        LoanDelinquencyBucket bucket,
        int overdueInstallmentCount,
        BigDecimal overdueAmount
) {
}
