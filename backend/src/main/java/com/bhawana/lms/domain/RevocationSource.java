package com.bhawana.lms.domain;

public enum RevocationSource {
    ADMIN_EXPLICIT,
    ADMIN_RESET_PASSWORD,
    ROLE_CHANGE,
    STATUS_CHANGE,
    LSP_DISABLED,
    BRUTE_FORCE_LOCKOUT,
    /** Self-service password change revokes all of the user's own sessions. */
    SELF_PASSWORD_CHANGE,
    /** LSP reassignment revokes all sessions so old lspId claims cannot survive. */
    LSP_ASSIGNMENT_CHANGE,
    /** Per-family logout (only that session family). */
    FAMILY_LOGOUT,
    /** Per-family reuse revocation (only that session family, no tv bump). */
    FAMILY_REUSE
}
