package com.bhawana.lms.domain;

public enum RevocationSource {
    ADMIN_EXPLICIT,
    ADMIN_RESET_PASSWORD,
    ROLE_CHANGE,
    BRUTE_FORCE_LOCKOUT
}
