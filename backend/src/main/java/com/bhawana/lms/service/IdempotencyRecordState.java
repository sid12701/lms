package com.bhawana.lms.service;

/**
 * Sentinel values stored on idempotency rows between claim and completion.
 * Avoids a schema migration while still allowing claim-first execution.
 */
final class IdempotencyRecordState {

    static final String PENDING_RESPONSE_BODY = "{\"__idempotencyPending\":true}";
    static final int PENDING_RESPONSE_STATUS = 0;

    private IdempotencyRecordState() {
    }

    static boolean isPending(String responseBody) {
        return PENDING_RESPONSE_BODY.equals(responseBody);
    }
}
