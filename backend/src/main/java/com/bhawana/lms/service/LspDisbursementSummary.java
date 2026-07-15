package com.bhawana.lms.service;

import java.math.BigDecimal;
import java.time.Instant;

public record LspDisbursementSummary(
        String status,
        String failureReasonCode,
        String failureReason,
        Instant disbursedAt,
        BigDecimal grossAmount,
        BigDecimal netDisbursedAmount
) {
}
