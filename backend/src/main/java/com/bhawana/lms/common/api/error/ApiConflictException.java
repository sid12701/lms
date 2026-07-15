package com.bhawana.lms.common.api.error;

import java.util.Optional;

public class ApiConflictException extends RuntimeException {

    private final String errorCode;
    private final Optional<Long> retryAfterSeconds;

    public ApiConflictException(String errorCode, String message) {
        this(errorCode, message, Optional.empty());
    }

    public ApiConflictException(String errorCode, String message, long retryAfterSeconds) {
        this(errorCode, message, Optional.of(retryAfterSeconds));
    }

    private ApiConflictException(String errorCode, String message, Optional<Long> retryAfterSeconds) {
        super(message);
        this.errorCode = errorCode;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public Optional<Long> getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}

