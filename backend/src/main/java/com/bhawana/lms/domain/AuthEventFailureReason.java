package com.bhawana.lms.domain;

public enum AuthEventFailureReason {
    INVALID_CREDENTIALS,
    ACCOUNT_LOCKED,
    ACCOUNT_DISABLED,
    TOKEN_EXPIRED,
    TOKEN_REVOKED,
    MISSING_REFRESH_COOKIE,
    OTHER
}
