package com.bhawana.lms.web;

public record DispatchWebhookOutboxResponse(
        int processed,
        int delivered,
        int retryableFailures,
        int permanentFailures
) {
}
