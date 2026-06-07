package com.bhawana.lms.service;

/**
 * Typed foreclosure validation failures on the LSP execute path.
 */
public enum ForeclosureViolationType {
    QUOTE_NOT_ACTIVE,
    QUOTE_OWNERSHIP_MISMATCH,
    SETTLEMENT_DATE_MISMATCH,
    LOAN_ACCOUNT_NOT_DISBURSED,
    SETTLEMENT_INCOMPLETE,
    REFERENCE_MISSING,
    FORECLOSURE_GENERIC
}
