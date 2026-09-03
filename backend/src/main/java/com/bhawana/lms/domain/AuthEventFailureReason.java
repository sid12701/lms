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
    MISSING_REFRESH_COOKIE,
    OTHER
}
