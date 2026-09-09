package com.bhawana.lms.domain;

/**
 * H02 — reason a loan account sits in the explicit reconciliation queue. Covers in-flight
 * ({@code UNKNOWN}/{@code REQUESTED}), parked ({@code PARKED}), legacy instruction mismatches,
 * stranded terminals (terminal intent, loan still REQUESTED) and conflicting definitive
 * provider evidence. Missing/contradictory instructions land here — history is never
 * fabricated to clear them.
 */
public enum DisbursementReconciliationReason {
    UNKNOWN,
    REQUESTED,
    PARKED,
    LEGACY_MISMATCH,
    STRANDED_TERMINAL,
    CONFLICTING_EVIDENCE
}
