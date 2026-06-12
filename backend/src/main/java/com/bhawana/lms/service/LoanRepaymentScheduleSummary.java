package com.bhawana.lms.service;

import com.bhawana.lms.domain.LoanRepaymentScheduleInstallment;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public record LoanRepaymentScheduleSummary(
        int installmentCount,
        BigDecimal installmentAmount,
        LocalDate firstDueDate,
        LocalDate finalDueDate
) {

    public static Optional<LoanRepaymentScheduleSummary> fromInstallments(
            List<LoanRepaymentScheduleInstallment> installments
    ) {
        if (installments == null || installments.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new LoanRepaymentScheduleSummary(
                installments.size(),
                installments.getFirst().getInstallmentAmount(),
                installments.getFirst().getDueDate(),
                installments.getLast().getDueDate()
        ));
    }
}
