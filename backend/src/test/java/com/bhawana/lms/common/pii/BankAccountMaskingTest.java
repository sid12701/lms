package com.bhawana.lms.common.pii;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class BankAccountMaskingTest {

    @Test
    void masksAllButLastFourDigits() {
        assertEquals("XXXXXXXX9012", BankAccountMasking.mask("123456789012"));
        assertEquals("XXXXXXXX5544", BankAccountMasking.mask("998877665544"));
    }

    @Test
    void stripsWhitespaceBeforeMasking() {
        assertEquals("XXXXXXXX9012", BankAccountMasking.mask("1234 5678 9012"));
    }

    @Test
    void shortValuesAreFullyMasked() {
        assertEquals("XXXXXXXX", BankAccountMasking.mask("123"));
    }

    @Test
    void nullAndBlankPassThrough() {
        assertNull(BankAccountMasking.mask(null));
        assertEquals("", BankAccountMasking.mask(""));
        assertEquals("   ", BankAccountMasking.mask("   "));
    }
}
