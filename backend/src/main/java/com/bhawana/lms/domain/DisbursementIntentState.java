package com.bhawana.lms.domain;

/**
 * Lifecycle for a durable disbursement intent. Terminal states ({@link #SUCCEEDED},
 * {@link #FAILED}, {@link #CANCELLED}) release the per-account live-intent slot.
 */
public enum DisbursementIntentState {
    CREATED,
    REQUESTED,
    SUCCEEDED,
    FAILED,
    UNKNOWN,
    CANCELLED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }

    public boolean isClaimable() {
        return this == CREATED;
    }
}
