package com.bhawana.lms.domain;

public enum AuthEventFailureReason {
    INVALID_CREDENTIALS,
    ACCOUNT_LOCKED,
    ACCOUNT_DISABLED,
    USER_INACTIVE,
    LSP_INACTIVE,
    SESSION_INVALID_STATUS,
    TOKEN_EXPIRED,
    TOKEN_REVOKED,
    /** Benign direct-parent loser within tolerance (no state change, no cookie). */
    TOKEN_ROTATED,
    MISSING_REFRESH_COOKIE,
    OTHER
}
