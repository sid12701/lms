package com.bhawana.lms.common.pii;

/**
 * Masks bank account numbers on read surfaces. The full value is only ever
 * returned by the dedicated bank-details endpoints
 * ({@code GET /api/v1/lsp/borrowers/{id}/bank-details} and the admin PATCH
 * response) and the payment-rail integration; every other serialization
 * (loan-application responses, ops alerts, loan event payloads) uses this mask.
 * Format matches {@link AadhaarMasking}: {@code XXXXXXXX<last4>}.
 */
public final class BankAccountMasking {

    private BankAccountMasking() {
    }

    public static String mask(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        String normalized = value.replaceAll("\\s", "");
        if (normalized.length() < 4) {
            return "XXXXXXXX";
        }
        return "XXXXXXXX" + normalized.substring(normalized.length() - 4);
    }
}
