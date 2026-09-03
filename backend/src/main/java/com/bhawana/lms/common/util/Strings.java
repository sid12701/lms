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

    /**
     * Returns {@code singularNoun} unchanged for a count of exactly one, otherwise the noun with
     * a trailing "s". Covers the regular nouns used in operator-facing alert copy (day, hour,
     * login, attempt, ...) — this is English pluralisation for a handful of known nouns, not a
     * general i18n framework, so irregular plurals aren't handled.
     */
    public static String pluralize(long count, String singularNoun) {
        return count == 1 ? singularNoun : singularNoun + "s";
    }
}
