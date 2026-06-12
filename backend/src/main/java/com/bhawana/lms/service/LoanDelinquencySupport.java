package com.bhawana.lms.service;

import com.bhawana.lms.common.money.Money;
import com.bhawana.lms.domain.LoanDelinquencyBucket;
import com.bhawana.lms.domain.LoanRepaymentScheduleInstallment;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

public final class LoanDelinquencySupport {

    private LoanDelinquencySupport() {
    }

    public static int calculateDaysPastDue(LoanRepaymentScheduleInstallment installment, LocalDate today) {
        if (installment.getOutstandingAmount().compareTo(BigDecimal.ZERO) <= 0
                || !installment.getDueDate().isBefore(today)) {
            return 0;
        }
        return Math.toIntExact(ChronoUnit.DAYS.between(installment.getDueDate(), today));
    }

    public static LoanDelinquencyBucket resolveDelinquencyBucket(int daysPastDue) {
        if (daysPastDue <= 0) {
            return LoanDelinquencyBucket.CURRENT;
        }
        if (daysPastDue <= 30) {
            return LoanDelinquencyBucket.DPD_1_30;
        }
        if (daysPastDue <= 60) {
            return LoanDelinquencyBucket.DPD_31_60;
        }
        if (daysPastDue <= 90) {
            return LoanDelinquencyBucket.DPD_61_90;
        }
        return LoanDelinquencyBucket.DPD_90_PLUS;
    }

    public static Optional<LoanDelinquencySummary> summarize(
            List<LoanRepaymentScheduleInstallment> installments,
            LocalDate today
    ) {
        if (installments == null || installments.isEmpty()) {
            return Optional.empty();
        }
        int maxDaysPastDue = installments.stream()
                .mapToInt(installment -> calculateDaysPastDue(installment, today))
                .max()
                .orElse(0);
        BigDecimal overdueAmount = installments.stream()
                .filter(installment -> calculateDaysPastDue(installment, today) > 0)
                .map(LoanRepaymentScheduleInstallment::getOutstandingAmount)
                .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
        long overdueInstallmentCount = installments.stream()
                .filter(installment -> calculateDaysPastDue(installment, today) > 0)
                .count();
        return Optional.of(new LoanDelinquencySummary(
                maxDaysPastDue,
                resolveDelinquencyBucket(maxDaysPastDue),
                Math.toIntExact(overdueInstallmentCount),
                Money.scale(overdueAmount)
        ));
    }
}
