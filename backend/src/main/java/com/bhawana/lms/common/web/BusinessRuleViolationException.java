package com.bhawana.lms.common.web;

import java.util.LinkedHashMap;
import java.util.Map;

public class BusinessRuleViolationException extends RuntimeException {

    private final String errorCode;
    private final Map<String, String> fieldErrors;

    public BusinessRuleViolationException(String errorCode, String message, Map<String, String> fieldErrors) {
        super(message);
        this.errorCode = errorCode;
        this.fieldErrors = Map.copyOf(new LinkedHashMap<>(fieldErrors));
    }

    public String getErrorCode() {
        return errorCode;
    }

    public Map<String, String> getFieldErrors() {
        return fieldErrors;
    }
}
