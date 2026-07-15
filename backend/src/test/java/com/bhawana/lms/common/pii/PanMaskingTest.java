package com.bhawana.lms.common.pii;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class PanMaskingTest {

    @Test
    void masksAllButLastFourCharacters() {
        assertEquals("XXXXXX234F", PanMasking.mask("ABCDE1234F"));
        assertEquals("XXXXXX678B", PanMasking.mask("WXYZA5678B"));
    }

    @Test
    void stripsWhitespaceBeforeMasking() {
        assertEquals("XXXXXX234F", PanMasking.mask("ABCDE 1234 F"));
    }

    @Test
    void shortValuesAreFullyMasked() {
        assertEquals("XXXXXX", PanMasking.mask("ABC"));
    }

    @Test
    void nullAndBlankPassThrough() {
        assertNull(PanMasking.mask(null));
        assertEquals("", PanMasking.mask(""));
        assertEquals("   ", PanMasking.mask("   "));
    }
}
