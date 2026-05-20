package com.bhawana.lms.repo;

import java.math.BigDecimal;

public record PortfolioMisSummaryAggregate(
        BigDecimal totalDisbursed,
        Long activeLoanCount,
        BigDecimal weightedInterestAmount,
        BigDecimal weightedPrincipalAmount,
        BigDecimal atRiskPrincipal,
        Long totalLoanCount
) {
}
