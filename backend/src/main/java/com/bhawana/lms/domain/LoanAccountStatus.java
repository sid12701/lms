package com.bhawana.lms.domain;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(enumAsRef = true)
public enum LoanAccountStatus {
    PENDING_DISBURSEMENT,
    DISBURSEMENT_REQUESTED,
    DISBURSED,
    INVALID,
    CLOSED,
    FORECLOSED,
    DISBURSEMENT_FAILED,
    DISBURSEMENT_PENDING_RECONCILIATION
}
