package com.bhawana.lms.common.money;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class LoanFeeCalculatorTest {

    @Test
    void computesPercentageOfPrincipalAtScaleTwo() {
        assertEquals(
                new BigDecimal("2250.00"),
                LoanFeeCalculator.computeProcessingFee(new BigDecimal("150000"), new BigDecimal("1.5"))
        );
    }

    @Test
    void zeroRateYieldsZeroFeeAtScaleTwo() {
        assertEquals(
                new BigDecimal("0.00"),
                LoanFeeCalculator.computeProcessingFee(new BigDecimal("150000"), new BigDecimal("0"))
        );
    }

    @Test
    void roundsHalfUp() {
        // 12345 * 2.5 / 100 = 308.625 -> 308.63
        assertEquals(
                new BigDecimal("308.63"),
                LoanFeeCalculator.computeProcessingFee(new BigDecimal("12345"), new BigDecimal("2.5"))
        );
    }

    @Test
    void roundsHalfUpAtExactHalf() {
        // 33333 * 1.5 / 100 = 499.995 -> 500.00
        assertEquals(
                new BigDecimal("500.00"),
                LoanFeeCalculator.computeProcessingFee(new BigDecimal("33333"), new BigDecimal("1.5"))
        );
    }

    @Test
    void hundredPercentRateEqualsPrincipal() {
        assertEquals(
                new BigDecimal("150000.00"),
                LoanFeeCalculator.computeProcessingFee(new BigDecimal("150000"), new BigDecimal("100"))
        );
    }

    @Test
    void rejectsNullPrincipal() {
        assertThrows(
                IllegalArgumentException.class,
                () -> LoanFeeCalculator.computeProcessingFee(null, new BigDecimal("1.5"))
        );
    }

    @Test
    void rejectsNullRate() {
        assertThrows(
                IllegalArgumentException.class,
                () -> LoanFeeCalculator.computeProcessingFee(new BigDecimal("150000"), null)
        );
    }

    @Test
    void rejectsNegativePrincipal() {
        assertThrows(
                IllegalArgumentException.class,
                () -> LoanFeeCalculator.computeProcessingFee(new BigDecimal("-1"), new BigDecimal("1.5"))
        );
    }

    @Test
    void rejectsNegativeRate() {
        assertThrows(
                IllegalArgumentException.class,
                () -> LoanFeeCalculator.computeProcessingFee(new BigDecimal("150000"), new BigDecimal("-0.01"))
        );
    }
}
