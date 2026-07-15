package com.bhawana.lms.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bhawana.lms.domain.BorrowerProfile;
import com.bhawana.lms.domain.BorrowerProfile.Builder;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class BorrowerOnboardingRequirementsTest {

    @Test
    void completeProfileHasNoMissingFields() {
        assertTrue(BorrowerOnboardingRequirements.missingRequiredFields(completeProfile()).isEmpty());
    }

    @Test
    void eachRequiredFieldIsReportedWhenMissing() {
        assertMissing("fullName", withField("fullName", null));
        assertMissing("addressLine1", withField("addressLine1", null));
        assertMissing("addressCity", withField("addressCity", null));
        assertMissing("addressState", withField("addressState", null));
        assertMissing("addressZipcode", withField("addressZipcode", null));
        assertMissing("monthlyIncome", withField("monthlyIncome", null));
        assertMissing("monthlyIncome", withField("monthlyIncome", BigDecimal.ZERO));
        assertMissing("referencePersonName", withField("referencePersonName", null));
        assertMissing("referencePersonNumber", withField("referencePersonNumber", null));
    }

    private static void assertMissing(String field, BorrowerProfile profile) {
        List<String> missing = BorrowerOnboardingRequirements.missingRequiredFields(profile);
        assertEquals(List.of(field), missing, "Expected only " + field + " to be missing");
    }

    private static BorrowerProfile withField(String field, Object value) {
        Builder builder = completeBuilder();
        switch (field) {
            case "fullName" -> builder.fullName((String) value);
            case "addressLine1" -> builder.addressLine1((String) value);
            case "addressCity" -> builder.addressCity((String) value);
            case "addressState" -> builder.addressState((String) value);
            case "addressZipcode" -> builder.addressZipcode((String) value);
            case "monthlyIncome" -> builder.monthlyIncome((BigDecimal) value);
            case "referencePersonName" -> builder.referencePersonName((String) value);
            case "referencePersonNumber" -> builder.referencePersonNumber((String) value);
            default -> throw new IllegalArgumentException("Unsupported field: " + field);
        }
        return builder.build();
    }

    private static Builder completeBuilder() {
        return BorrowerProfile.builder()
                .fullName("Anika Sharma")
                .emailAddress("anika@example.com")
                .mobileNumber("9999999999")
                .aadharNumber("123412341234")
                .panNumber("ABCDE1234F")
                .addressLine1("Palm Residency")
                .addressCity("Mumbai")
                .addressState("Maharashtra")
                .addressZipcode("400001")
                .monthlyIncome(new BigDecimal("78000.00"))
                .referencePersonName("Neha Verma")
                .referencePersonNumber("9888877777");
    }

    private static BorrowerProfile completeProfile() {
        return completeBuilder().build();
    }
}
