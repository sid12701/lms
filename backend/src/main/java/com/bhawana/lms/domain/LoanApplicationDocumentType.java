package com.bhawana.lms.domain;

public enum LoanApplicationDocumentType {
    PAN_CARD("PAN Card", true, true),
    AADHAAR_FILE("Verified Aadhaar File", true, true),
    ADDRESS_PROOF("Address Proof", true, true),
    INCOME_PROOF("Income Proof", true, true),
    BANK_STATEMENT("Bank Statement", true, true),
    SELFIE_PHOTOGRAPH("Selfie Photograph", true, true),
    KFS("KFS", false, true),
    LOAN_AGREEMENT("Loan Agreement", false, true);

    private final String displayName;
    private final boolean requiredForApproval;
    private final boolean requiredForDisbursement;

    LoanApplicationDocumentType(
            String displayName,
            boolean requiredForApproval,
            boolean requiredForDisbursement
    ) {
        this.displayName = displayName;
        this.requiredForApproval = requiredForApproval;
        this.requiredForDisbursement = requiredForDisbursement;
    }

    public String getDisplayName() {
        return displayName;
    }

    public boolean isRequiredByDefault() {
        return requiredForApproval || requiredForDisbursement;
    }

    public boolean isRequiredForApproval() {
        return requiredForApproval;
    }

    public boolean isRequiredForDisbursement() {
        return requiredForDisbursement;
    }
}
