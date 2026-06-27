package com.bhawana.lms.common.api.error;

public class ApiConflictException extends RuntimeException {

    private final String errorCode;

    public ApiConflictException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}

