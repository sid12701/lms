package com.bhawana.lms.seed.synthetic;

import com.bhawana.lms.common.money.Money;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/** EMI schedule generation aligned with {@link com.bhawana.lms.service.LoanRepaymentScheduleService}. */
final class SyntheticPortfolioEmiCalculator {

    private SyntheticPortfolioEmiCalculator() {
    }

    static List<InstallmentRow> buildSchedule(
            BigDecimal principal,
            BigDecimal annualInterestRate,
            int tenureMonths,
            Instant approvedAt
    ) {
        BigDecimal scaledPrincipal = Money.scale(principal);
        BigDecimal monthlyRate = annualInterestRate.divide(BigDecimal.valueOf(1200), 10, RoundingMode.HALF_UP);
        BigDecimal emiAmount = calculateMonthlyEmi(scaledPrincipal, monthlyRate, tenureMonths);
        LocalDate firstDueDate = approvedAt.atZone(ZoneOffset.UTC).toLocalDate().plusMonths(1);

        BigDecimal remainingPrincipal = scaledPrincipal;
        List<InstallmentRow> installments = new ArrayList<>(tenureMonths);
        for (int installmentNumber = 1; installmentNumber <= tenureMonths; installmentNumber++) {
            BigDecimal openingPrincipal = Money.scale(remainingPrincipal);
            BigDecimal interestDue = Money.scale(openingPrincipal.multiply(monthlyRate));
            BigDecimal installmentAmount = emiAmount;
            BigDecimal principalDue = Money.scale(installmentAmount.subtract(interestDue));
            if (installmentNumber == tenureMonths) {
                principalDue = openingPrincipal;
                installmentAmount = Money.scale(principalDue.add(interestDue));
            }
            BigDecimal closingPrincipal = Money.scale(openingPrincipal.subtract(principalDue));
            if (closingPrincipal.compareTo(BigDecimal.ZERO) < 0) {
                closingPrincipal = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            }
            installments.add(new InstallmentRow(
                    installmentNumber,
                    firstDueDate.plusMonths(installmentNumber - 1L),
                    openingPrincipal,
                    principalDue,
                    interestDue,
                    installmentAmount,
                    closingPrincipal
            ));
            remainingPrincipal = closingPrincipal;
        }
        return installments;
    }

    private static BigDecimal calculateMonthlyEmi(BigDecimal principal, BigDecimal monthlyRate, int tenureMonths) {
        if (monthlyRate.compareTo(BigDecimal.ZERO) == 0) {
            return principal.divide(BigDecimal.valueOf(tenureMonths), 2, RoundingMode.HALF_UP);
        }
        BigDecimal onePlusRate = BigDecimal.ONE.add(monthlyRate);
        BigDecimal compounded = onePlusRate.pow(tenureMonths, MathContext.DECIMAL64);
        BigDecimal numerator = principal.multiply(monthlyRate).multiply(compounded);
        BigDecimal denominator = compounded.subtract(BigDecimal.ONE);
        return numerator.divide(denominator, 2, RoundingMode.HALF_UP);
    }

    record InstallmentRow(
            int installmentNumber,
            LocalDate dueDate,
            BigDecimal openingPrincipal,
            BigDecimal principalDue,
            BigDecimal interestDue,
            BigDecimal installmentAmount,
            BigDecimal closingPrincipal
    ) {
    }
}
