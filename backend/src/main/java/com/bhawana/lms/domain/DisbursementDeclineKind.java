package com.bhawana.lms.domain;

/**
 * ICICI Composite Pay declines are classified as Technical or Business. The distinction drives
 * retry policy: technical declines are safe to retry after a delay; business declines must not be
 * retried without fixing the underlying data (invalid/frozen account, insufficient funds, ...).
 */
public enum DisbursementDeclineKind {

    /** Not a decline (used for SUCCESS / PENDING dispositions). */
    NONE,

    /** Technical Decline (TD) — transient; safe to retry. */
    TECHNICAL,

    /** Business Decline (BD) — terminal for this data; do not retry without remediation. */
    BUSINESS
}
