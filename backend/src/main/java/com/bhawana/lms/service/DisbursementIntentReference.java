package com.bhawana.lms.service;

import java.util.UUID;

public final class DisbursementIntentReference {

    private DisbursementIntentReference() {
    }

    /**
     * Deterministic ICICI client reference derived from the intent id (≤ 16 chars for NEFT).
     */
    public static String deriveTranRefNo(UUID intentId) {
        return "ICI" + intentId.toString().replace("-", "").substring(0, 13).toUpperCase();
    }
}
