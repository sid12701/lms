package com.bhawana.lms.common.util;

public final class Strings {

    private Strings() {
    }

    public static String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isBlank() ? null : normalized;
    }

    public static String normalizeActor(String actorUsername) {
        if (actorUsername == null) {
            return "system";
        }
        String normalized = actorUsername.trim();
        return normalized.isBlank() ? "system" : normalized;
    }
}
