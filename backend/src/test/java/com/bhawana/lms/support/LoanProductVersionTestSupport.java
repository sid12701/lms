package com.bhawana.lms.support;

import com.bhawana.lms.domain.LoanProduct;
import com.bhawana.lms.domain.LoanProductVersion;
import java.time.Instant;

public final class LoanProductVersionTestSupport {

    private LoanProductVersionTestSupport() {
    }

    public static LoanProductVersion versionOne(LoanProduct product) {
        return new LoanProductVersion(
                product,
                1,
                product.getMinPrincipal(),
                product.getMaxPrincipal(),
                product.getInterestRate(),
                product.getProcessingFeeRate(),
                product.getMinTenureMonths(),
                product.getMaxTenureMonths(),
                product.getCreatedAt() == null ? Instant.now() : product.getCreatedAt(),
                null
        );
    }
}
