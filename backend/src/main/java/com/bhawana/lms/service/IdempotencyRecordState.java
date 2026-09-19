package com.bhawana.lms.service;

/**
 * Sentinel values stored on idempotency rows between claim and completion.
 * Avoids a schema migration while still allowing claim-first execution.
 *
 * <p>{@link #RECOVERY_REQUIRED_RESPONSE_BODY} is a terminal state, not a
 * transient one: a row carrying it is evidence for reconciliation and is never
 * reclaimed or re-executed. Callers get a deterministic
 * {@code IDEMPOTENCY_RECOVERY_REQUIRED} without another dead-lease renewal.
 */
final class IdempotencyRecordState {

    static final String PENDING_RESPONSE_BODY = "{\"__idempotencyPending\":true}";
    static final int PENDING_RESPONSE_STATUS = 0;
    static final String RECOVERY_REQUIRED_RESPONSE_BODY = "{\"__idempotencyRecoveryRequired\":true}";
    static final int RECOVERY_REQUIRED_RESPONSE_STATUS = 409;

    private IdempotencyRecordState() {
    }

    static boolean isPending(String responseBody) {
        return PENDING_RESPONSE_BODY.equals(responseBody);
    }

    static boolean isRecoveryRequired(String responseBody) {
        return RECOVERY_REQUIRED_RESPONSE_BODY.equals(responseBody);
    }
}
