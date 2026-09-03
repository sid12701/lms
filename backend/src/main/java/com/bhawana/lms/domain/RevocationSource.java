package com.bhawana.lms.domain;

public enum RevocationSource {
    ADMIN_EXPLICIT,
    ADMIN_RESET_PASSWORD,
    ROLE_CHANGE,
    STATUS_CHANGE,
    LSP_DISABLED,
    BRUTE_FORCE_LOCKOUT
}
