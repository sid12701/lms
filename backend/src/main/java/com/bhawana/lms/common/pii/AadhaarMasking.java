package com.bhawana.lms.common.pii;

import java.util.Set;

public final class AadhaarMasking {

    public static final Set<String> JSON_FIELD_NAMES = Set.of(
            "borrowerAadharNumber",
            "borrowerAadhaarNumber",
            "aadharNumber",
            "aadhaarNumber",
            "aadhar",
            "aadhaar",
            "incomingAadhar"
    );

    private AadhaarMasking() {
    }

    public static boolean isJsonFieldName(String fieldName) {
        return fieldName != null && JSON_FIELD_NAMES.contains(fieldName);
    }

    public static String mask(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        String digits = value.replaceAll("\\s", "");
        if (digits.length() < 4) {
            return "XXXXXXXX";
        }
        return "XXXXXXXX" + digits.substring(digits.length() - 4);
    }
}
