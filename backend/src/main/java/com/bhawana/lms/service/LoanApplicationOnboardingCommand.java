package com.bhawana.lms.service;

import com.bhawana.lms.domain.BorrowerProfile;
import java.math.BigDecimal;
import java.util.UUID;

public record LoanApplicationOnboardingCommand(
        UUID lspId,
        UUID productId,
        String loanProduct,
        String lspLoanId,
        String sourceChannel,
        BigDecimal loanAmount,
        BigDecimal interestRate,
        Integer loanTenure,
        BorrowerProfile borrowerProfile
) {
}
