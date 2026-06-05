package com.bhawana.lms.service;

/**
 * Typed schedule validation failures for LSP_PROVIDED repayment schedules.
 */
public enum ScheduleViolationType {
    SCHEDULE_OPENING_MISMATCH,
    SCHEDULE_CHAIN_BROKEN,
    SCHEDULE_FINAL_NONZERO,
    SCHEDULE_ROW_RECONCILE_FAILED,
    SCHEDULE_PRINCIPAL_NOT_CLOSED,
    SCHEDULE_INSTALLMENT_COUNT_MISMATCH,
    SCHEDULE_GENERIC
}
