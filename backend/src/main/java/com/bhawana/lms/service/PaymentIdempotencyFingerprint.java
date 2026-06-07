package com.bhawana.lms.service;

import com.bhawana.lms.domain.LoanPaymentChannel;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

record PaymentIdempotencyFingerprint(
        UUID applicationId,
        UUID targetInstallmentId,
        BigDecimal amount,
        LocalDate postedAt,
        String reference,
        LoanPaymentChannel channel
) {
}
