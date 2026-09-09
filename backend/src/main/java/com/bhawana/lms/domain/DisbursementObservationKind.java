package com.bhawana.lms.domain;

/**
 * H02 — kind of a provider observation. {@code LEGACY} marks the V121 backfill row built from
 * the actual latest stored request-log evidence (attempt kind unknown); live traffic only
 * writes {@code INITIATE} / {@code POLL}.
 */
public enum DisbursementObservationKind {
    INITIATE,
    POLL,
    LEGACY
}
