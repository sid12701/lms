package com.bhawana.lms.common.money;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class Money {

    private Money() {
    }

    public static BigDecimal scale(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    public static BigDecimal requirePositive(BigDecimal value, String fieldName) {
        if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException(fieldName + " must be greater than zero.");
        }
        return value;
    }

    /**
     * Formats an amount with Indian digit grouping (last 3 digits, then pairs) — e.g.
     * 1230000000.00 -> "1,23,00,00,000.00". The JDK's "en-IN" {@code NumberFormat} does not
     * produce this grouping (its pattern is the Western {@code #,##0.###}), so it's done by hand
     * here. This exists only for amounts baked directly into free-text operator copy (alert
     * messages and the like) where there's no structured field for the frontend to format with
     * {@code Intl.NumberFormat("en-IN")} itself — prefer passing the raw {@link BigDecimal} in a
     * DTO field over calling this wherever that's possible.
     */
    public static String formatIndianGrouping(BigDecimal value) {
        BigDecimal scaled = scale(value == null ? BigDecimal.ZERO : value);
        boolean negative = scaled.signum() < 0;
        String plain = scaled.abs().toPlainString();
        int decimalPointIndex = plain.indexOf('.');
        String integerDigits = decimalPointIndex < 0 ? plain : plain.substring(0, decimalPointIndex);
        String fractionDigits = decimalPointIndex < 0 ? "00" : plain.substring(decimalPointIndex + 1);
        return (negative ? "-" : "") + groupIndian(integerDigits) + "." + fractionDigits;
    }

    private static String groupIndian(String integerDigits) {
        if (integerDigits.length() <= 3) {
            return integerDigits;
        }
        String lastThree = integerDigits.substring(integerDigits.length() - 3);
        String remainder = integerDigits.substring(0, integerDigits.length() - 3);
        StringBuilder reversedGrouped = new StringBuilder();
        int digitsSinceComma = 0;
        for (int i = remainder.length() - 1; i >= 0; i--) {
            reversedGrouped.append(remainder.charAt(i));
            digitsSinceComma++;
            if (digitsSinceComma == 2 && i != 0) {
                reversedGrouped.append(',');
                digitsSinceComma = 0;
            }
        }
        return reversedGrouped.reverse() + "," + lastThree;
    }
}
