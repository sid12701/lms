package com.bhawana.lms.domain;

public enum WebhookEventOutboxStatus {
    PENDING,
    IN_FLIGHT,
    RETRYABLE_FAILURE,
    PERMANENT_FAILURE,
    DELIVERED
}
