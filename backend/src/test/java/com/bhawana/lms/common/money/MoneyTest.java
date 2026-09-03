package com.bhawana.lms.common.money;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    void scaleRoundsHalfUpToTwoDecimals() {
        assertEquals(new BigDecimal("308.63"), Money.scale(new BigDecimal("308.625")));
    }

    @Test
    void formatIndianGroupingGroupsLastThreeThenPairs() {
        // Verified against the frontend's Intl.NumberFormat("en-IN") output for the same figures.
        assertEquals("13,787.72", Money.formatIndianGrouping(new BigDecimal("13787.72")));
        assertEquals("39,70,862.00", Money.formatIndianGrouping(new BigDecimal("3970862")));
        assertEquals("12,30,00,000.00", Money.formatIndianGrouping(new BigDecimal("123000000")));
    }

    @Test
    void formatIndianGroupingLeavesThreeOrFewerIntegerDigitsUngrouped() {
        assertEquals("0.00", Money.formatIndianGrouping(BigDecimal.ZERO));
        assertEquals("100.00", Money.formatIndianGrouping(new BigDecimal("100")));
        assertEquals("999.00", Money.formatIndianGrouping(new BigDecimal("999")));
        assertEquals("1,000.00", Money.formatIndianGrouping(new BigDecimal("1000")));
    }

    @Test
    void formatIndianGroupingHandlesNegativeAmounts() {
        assertEquals("-4,500.50", Money.formatIndianGrouping(new BigDecimal("-4500.5")));
    }

    @Test
    void formatIndianGroupingTreatsNullAsZero() {
        assertEquals("0.00", Money.formatIndianGrouping(null));
    }
}
