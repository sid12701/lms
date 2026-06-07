package com.bhawana.lms.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bhawana.lms.service.BankAccountHolderNameMatcher.HolderNameMatchOutcome;
import java.text.Normalizer;
import org.junit.jupiter.api.Test;

class BankAccountHolderNameMatcherTest {

    private final BankAccountHolderNameMatcher matcher = new BankAccountHolderNameMatcher();

    @Test
    void matches_canonical_form() {
        assertTrue(matcher.matches("John Kumar", "JOHN KUMAR"));
    }

    @Test
    void strips_diacritics_NFKD() {
        assertTrue(matcher.matches("JÖHN KUMAR", "JOHN KUMAR"));
    }

    @Test
    void collapses_internal_whitespace() {
        assertTrue(matcher.matches("JOHN  KUMAR", "JOHN KUMAR"));
    }

    @Test
    void strips_dot_comma_apostrophe_hyphen() {
        assertTrue(matcher.matches("JOHN K.", "JOHN K"));
    }

    @Test
    void case_insensitive_locale_root() {
        assertTrue(matcher.matches("john kumar", "JOHN KUMAR"));
    }

    @Test
    void rejects_genuinely_different_names() {
        assertFalse(matcher.matches("JANE DOE", "JOHN KUMAR"));
    }

    @Test
    void rejects_initial_expansion_JOHN_K_vs_JOHN_KUMAR() {
        assertFalse(matcher.matches("JOHN K", "JOHN KUMAR"));
    }

    @Test
    void rejects_word_reorder_KUMAR_comma_JOHN_vs_JOHN_KUMAR() {
        assertFalse(matcher.matches("KUMAR, JOHN", "JOHN KUMAR"));
    }

    @Test
    void matches_unicode_composition_form() {
        String nfc = "JÖHN";
        String nfd = Normalizer.normalize(nfc, Normalizer.Form.NFD);
        assertTrue(matcher.matches(nfc, nfd));
    }

    @Test
    void compare_soft_mismatch_when_only_honorific_differs() {
        assertEquals(HolderNameMatchOutcome.SOFT_MISMATCH, matcher.compare("MR. JOHN K", "JOHN K"));
    }

    @Test
    void compare_hard_mismatch_for_initial_expansion() {
        assertEquals(HolderNameMatchOutcome.HARD_MISMATCH, matcher.compare("JOHN K", "JOHN KUMAR"));
    }

    @Test
    void compare_hard_mismatch_for_word_reorder() {
        assertEquals(HolderNameMatchOutcome.HARD_MISMATCH, matcher.compare("KUMAR, JOHN", "JOHN KUMAR"));
    }
}
