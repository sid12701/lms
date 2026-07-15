package com.bhawana.lms.domain;

public enum WebhookEventType {
    LOAN_CREATED,
    LOAN_STATUS_CHANGED,
    /**
     * No producer emits this event; subscriptions to it never fire (V99 migrated
     * existing rows to {@link #DISBURSEMENT_COMPLETED}). Kept only so historical
     * outbox rows still deserialize — do not offer it to new subscribers.
     */
    @Deprecated
    LOAN_DISBURSEMENT_UPDATED,
    DISBURSEMENT_REQUESTED,
    DISBURSEMENT_COMPLETED,
    DISBURSEMENT_FAILED,
    DOCUMENTS_UPLOADED,
    LOAN_REPAYMENT_RECORDED,
    LOAN_FULLY_REPAID,
    FORECLOSURE_QUOTE_REQUESTED,
    LOAN_FORECLOSURE_COMPLETED,
    BORROWER_BANK_DETAILS_UPDATED
}
