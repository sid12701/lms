package com.bhawana.lms.service;

/**
 * Read-model for the durable disbursement provider reference after Tx-A (intent)
 * or after a request-log row exists — closes the async intent reference lag (S12 residual).
 */
public record DisbursementReference(
        String tranRefNo,
        String source,
        java.util.UUID intentId,
        String intentState
) {
    public static final String SOURCE_INTENT = "INTENT";
    public static final String SOURCE_REQUEST_LOG = "REQUEST_LOG";
}
