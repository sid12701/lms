package com.bhawana.lms.common.money;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Single source of truth for converting a (principal, processing-fee rate) pair into a fee amount.
 * The rate is interpreted as a percentage; the result is scaled to 2 decimals HALF_UP.
 *
 * <p>Centralising this prevents the report-side and disbursement-side fee formulas from drifting
 * (see ADR 0004 — processing fee deduction at disbursement).
 */
public final class LoanFeeCalculator {

    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);

    private LoanFeeCalculator() {
    }

    public static BigDecimal computeProcessingFee(BigDecimal principal, BigDecimal processingFeeRate) {
        if (principal == null) {
            throw new IllegalArgumentException("principal must not be null.");
        }
        if (processingFeeRate == null) {
            throw new IllegalArgumentException("processingFeeRate must not be null.");
        }
        if (principal.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("principal must not be negative.");
        }
        if (processingFeeRate.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("processingFeeRate must not be negative.");
        }
        return principal
                .multiply(processingFeeRate)
                .divide(ONE_HUNDRED, 2, RoundingMode.HALF_UP);
    }
}
