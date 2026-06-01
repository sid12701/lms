package com.bhawana.lms.common.web;

public class LspStatusUpdateException extends RuntimeException {

    private final String errorCode;

    public LspStatusUpdateException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
