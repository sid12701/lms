package com.bhawana.lms.common.pii;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AadhaarMaskingTest {

    @Test
    void masksTwelveDigitValueToLastFour() {
        assertEquals("XXXXXXXX1234", AadhaarMasking.mask("123412341234"));
    }

    @Test
    void recognizesBorrowerAadharNumberFieldName() {
        assertTrue(AadhaarMasking.isJsonFieldName("borrowerAadharNumber"));
        assertFalse(AadhaarMasking.isJsonFieldName("bankAccountNumber"));
    }
}
