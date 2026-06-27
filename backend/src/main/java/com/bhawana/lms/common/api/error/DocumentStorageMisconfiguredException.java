package com.bhawana.lms.common.api.error;

public class DocumentStorageMisconfiguredException extends RuntimeException {

    private final String providerName;
    private final String missingField;

    public DocumentStorageMisconfiguredException(String providerName, String missingField, String message) {
        super(message);
        this.providerName = providerName;
        this.missingField = missingField;
    }

    public String getProviderName() {
        return providerName;
    }

    public String getMissingField() {
        return missingField;
    }
}

