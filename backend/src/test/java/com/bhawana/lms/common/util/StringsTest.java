package com.bhawana.lms.common.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class StringsTest {

    @Test
    void normalizeOptionalTrimsAndBlanksToNull() {
        assertEquals("hello", Strings.normalizeOptional("  hello  "));
        assertNull(Strings.normalizeOptional("   "));
        assertNull(Strings.normalizeOptional(null));
    }

    @Test
    void normalizeActorDefaultsToSystem() {
        assertEquals("ops.user", Strings.normalizeActor("  ops.user  "));
        assertEquals("system", Strings.normalizeActor("   "));
        assertEquals("system", Strings.normalizeActor(null));
    }

    @Test
    void pluralizeKeepsSingularNounAtCountOne() {
        assertEquals("day", Strings.pluralize(1, "day"));
        assertEquals("hour", Strings.pluralize(1L, "hour"));
    }

    @Test
    void pluralizeAppendsSAtCountZeroAndAboveOne() {
        assertEquals("days", Strings.pluralize(0, "day"));
        assertEquals("days", Strings.pluralize(2, "day"));
        assertEquals("days", Strings.pluralize(27, "day"));
    }

    @Test
    void pluralizeHandlesNegativeCounts() {
        // Not reachable on any current alert path, but the helper shouldn't misbehave if a
        // caller ever passes a negative delta — English plural rule for anything but 1 is "s".
        assertEquals("days", Strings.pluralize(-1, "day"));
    }
}
