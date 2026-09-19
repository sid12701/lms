package com.bhawana.lms.domain;

public enum OpsAlertSeverity {
    HIGH,
    CRITICAL,
    /**
     * H30 — the frontend triage contract names four severities and historical
     * rows may already carry these values in the (unconstrained) VARCHAR
     * column. JPA {@code EnumType.STRING} fails the whole read when a row
     * names a constant missing here, so the enum must cover the contract.
     * Emitters still choose HIGH/CRITICAL; these constants exist so stored
     * rows load and filter correctly. No migration: the column is a plain
     * VARCHAR with no check constraint.
     */
    MEDIUM,
    LOW
}
