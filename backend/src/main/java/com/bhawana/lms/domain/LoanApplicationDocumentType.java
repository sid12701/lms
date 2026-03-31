package com.bhawana.lms.domain;

public enum LoanApplicationDocumentType {
    PAN_CARD("PAN Card", true),
    ADDRESS_PROOF("Address Proof", true),
    INCOME_PROOF("Income Proof", true),
    BANK_STATEMENT("Bank Statement", true),
    SELFIE_PHOTOGRAPH("Selfie Photograph", false);

    private final String displayName;
    private final boolean requiredByDefault;

    LoanApplicationDocumentType(String displayName, boolean requiredByDefault) {
        this.displayName = displayName;
        this.requiredByDefault = requiredByDefault;
    }

    public String getDisplayName() {
        return displayName;
    }

    public boolean isRequiredByDefault() {
        return requiredByDefault;
    }
}
