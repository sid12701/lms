package com.bhawana.lms.common.api.error;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A partner's loan event feed cursor names a point the log no longer retains (ADR 0007 point 6;
 * CONTEXT.md § Retention window). The token itself was well-formed — this is not the partner's bug,
 * it fell behind — so it is kept distinct from {@link BusinessRuleViolationException}'s 422 and maps
 * to 410 Gone instead, letting a partner tell "your integration is broken" from "you have lagged"
 * by status code alone.
 */
public class CursorExpiredException extends RuntimeException {

    private final String errorCode;
    private final Map<String, String> fieldErrors;

    public CursorExpiredException(String errorCode, String message, Map<String, String> fieldErrors) {
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
