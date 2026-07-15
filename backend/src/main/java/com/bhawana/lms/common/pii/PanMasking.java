package com.bhawana.lms.common.pii;

/**
 * Masks PAN on read surfaces. The full value is only returned on dedicated
 * endpoints that explicitly audit reveal; every other serialization uses this
 * mask. Format: {@code XXXXXX<last4>} (PAN is 10 characters, e.g.
 * {@code ABCDE1234F → XXXXXX234F}).
 */
public final class PanMasking {

    private PanMasking() {
    }

    public static String mask(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        String normalized = value.replaceAll("\\s", "");
        if (normalized.length() < 4) {
            return "XXXXXX";
        }
        return "XXXXXX" + normalized.substring(normalized.length() - 4);
    }
}
