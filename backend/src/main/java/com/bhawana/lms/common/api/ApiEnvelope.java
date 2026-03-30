package com.bhawana.lms.common.api;

public record ApiEnvelope<T>(T data, String correlationId) {

    public static <T> ApiEnvelope<T> of(T data, String correlationId) {
        return new ApiEnvelope<>(data, correlationId);
    }
}
