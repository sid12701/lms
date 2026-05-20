package com.bhawana.lms.common.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record ApiError(
        Instant timestamp,
        int status,
        String code,
        String error,
        String message,
        String path,
        String correlationId,
        String errorCode,
        String errorReason,
        String errorSource,
        List<FieldViolation> violations,
        List<ErrorDetail> errors
) {
    public static ApiError of(
            int status,
            String code,
            String message,
            String path,
            String correlationId,
            Map<String, String> fieldErrors
    ) {
        List<FieldViolation> violations = fieldErrors.entrySet().stream()
                .map(entry -> new FieldViolation(entry.getKey(), entry.getValue()))
                .toList();

        List<ErrorDetail> errors = violations.isEmpty()
                ? List.of(new ErrorDetail(code, code, message, null, message))
                : violations.stream()
                .map(violation -> new ErrorDetail(
                        code,
                        code,
                        violation.field() + ": " + violation.message(),
                        violation.field(),
                        violation.message()
                ))
                .toList();

        return new ApiError(
                Instant.now(),
                status,
                code,
                code,
                message,
                path,
                correlationId,
                code,
                code,
                message,
                violations,
                errors
        );
    }

    public record FieldViolation(String field, String message) {
    }

    public record ErrorDetail(
            String errorCode,
            String errorReason,
            String errorSource,
            String field,
            String message
    ) {
    }
}
