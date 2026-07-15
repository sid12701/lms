package com.bhawana.lms.service;

import com.bhawana.lms.domain.Borrower;
import com.bhawana.lms.domain.BorrowerProfile;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Single source of truth for borrower fields required before a loan application can be
 * created or auto-approved. Shared by onboarding validation and the auto-approval rule engine.
 */
public final class BorrowerOnboardingRequirements {

    private BorrowerOnboardingRequirements() {
    }

    public static List<String> missingRequiredFields(Borrower borrower) {
        if (borrower == null) {
            return List.of("borrower");
        }
        return missingRequiredFields(BorrowerProfile.fromEntity(borrower));
    }

    public static List<String> missingRequiredFields(BorrowerProfile profile) {
        List<String> missing = new ArrayList<>();
        if (profile == null) {
            missing.add("borrower");
            return List.copyOf(missing);
        }
        if (isBlank(profile.fullName())) {
            missing.add("fullName");
        }
        if (isBlank(profile.panNumber())) {
            missing.add("panNumber");
        }
        if (isBlank(profile.mobileNumber())) {
            missing.add("mobileNumber");
        }
        if (isBlank(profile.aadharNumber())) {
            missing.add("aadharNumber");
        }
        if (isBlank(profile.addressLine1())) {
            missing.add("addressLine1");
        }
        if (isBlank(profile.addressCity())) {
            missing.add("addressCity");
        }
        if (isBlank(profile.addressState())) {
            missing.add("addressState");
        }
        if (isBlank(profile.addressZipcode())) {
            missing.add("addressZipcode");
        }
        if (profile.monthlyIncome() == null || profile.monthlyIncome().compareTo(BigDecimal.ZERO) <= 0) {
            missing.add("monthlyIncome");
        }
        if (isBlank(profile.referencePersonName())) {
            missing.add("referencePersonName");
        }
        if (isBlank(profile.referencePersonNumber())) {
            missing.add("referencePersonNumber");
        }
        return List.copyOf(missing);
    }

    public static Map<String, String> missingRequiredFieldErrors(Borrower borrower) {
        return toFieldErrors(missingRequiredFields(borrower));
    }

    public static Map<String, String> missingRequiredFieldErrors(BorrowerProfile profile) {
        return toFieldErrors(missingRequiredFields(profile));
    }

    private static Map<String, String> toFieldErrors(List<String> missingFields) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (String field : missingFields) {
            fieldErrors.put(field, "This field is required.");
        }
        return fieldErrors;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
