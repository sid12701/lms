package com.bhawana.lms.common.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record ApiError(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path,
        String correlationId,
        List<FieldViolation> violations
) {
    public static ApiError of(
            String error,
            String message,
            int status,
            String correlationId,
            Map<String, String> fieldErrors
    ) {
        List<FieldViolation> violations = fieldErrors.entrySet().stream()
                .map(entry -> new FieldViolation(entry.getKey(), entry.getValue()))
                .toList();

        return new ApiError(Instant.now(), status, error, message, null, correlationId, violations);
    }

    public record FieldViolation(String field, String message) {
    }
}
