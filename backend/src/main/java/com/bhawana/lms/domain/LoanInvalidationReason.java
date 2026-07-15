package com.bhawana.lms.domain;

public enum LoanInvalidationReason {
    DUPLICATE_APPLICATION("Duplicate application", false),
    KYC_MISMATCH("KYC / document mismatch", false),
    BORROWER_WITHDREW("Borrower withdrew", false),
    FRAUD_SUSPECTED("Suspected fraud", true),
    DATA_ENTRY_ERROR("Data-entry error", false),
    OTHERS("Others", true);

    private final String label;
    private final boolean requiresDetail;

    LoanInvalidationReason(String label, boolean requiresDetail) {
        this.label = label;
        this.requiresDetail = requiresDetail;
    }

    public String getLabel() {
        return label;
    }

    public boolean requiresDetail() {
        return requiresDetail;
    }
}
