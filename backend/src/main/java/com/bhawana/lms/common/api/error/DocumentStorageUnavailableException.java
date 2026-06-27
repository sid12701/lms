package com.bhawana.lms.common.api.error;

public class DocumentStorageUnavailableException extends RuntimeException {

    private final String storageKey;
    private final String providerName;

    public DocumentStorageUnavailableException(String storageKey, String providerName, String message, Throwable cause) {
        super(message, cause);
        this.storageKey = storageKey;
        this.providerName = providerName;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public String getProviderName() {
        return providerName;
    }
}

