package com.bhawana.lms.common.web;

public class LspSurfaceIpAccessDeniedException extends RuntimeException {

    private final String errorCode;

    public LspSurfaceIpAccessDeniedException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
