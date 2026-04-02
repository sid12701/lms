package com.bhawana.lms.domain;

public enum WebhookEventOutboxStatus {
    PENDING,
    RETRYABLE_FAILURE,
    PERMANENT_FAILURE,
    DELIVERED
}
