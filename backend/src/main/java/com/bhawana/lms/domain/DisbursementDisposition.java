package com.bhawana.lms.domain;

/**
 * Normalised classification of a provider (mock ICICI) response, collapsing the raw ActCode space
 * into the three outcomes the LMS lifecycle reacts to.
 */
public enum DisbursementDisposition {

    /** Funds moved (or will move) to the beneficiary — terminal success. */
    SUCCESS,

    /** Provider declined the transaction — terminal failure (see {@link DisbursementDeclineKind}). */
    FAILED,

    /** Not yet terminal — the client must poll the status-check API. */
    PENDING
}
