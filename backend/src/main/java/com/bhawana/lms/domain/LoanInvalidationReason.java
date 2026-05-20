package com.bhawana.lms.domain;

public enum LoanInvalidationReason {
    REASON_A("Reason A", false),
    REASON_B("Reason B", false),
    REASON_C("Reason C", false),
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
