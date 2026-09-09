package com.bhawana.lms.domain;

/**
 * H02 — provenance of a provider observation. {@code LEGACY} rows are the V121 backfill from
 * the actual latest stored request-log evidence; {@code LIVE} rows are written by the
 * intent/poll path from real provider responses.
 */
public enum DisbursementObservationProvenance {
    LIVE,
    LEGACY
}
