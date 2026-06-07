package com.bhawana.lms.tenant;

/**
 * Raised when data access runs without an explicit tenant or admin scope on the current thread.
 */
public class MissingTenantContextException extends IllegalStateException {

    public MissingTenantContextException(String message) {
        super(message);
    }
}
