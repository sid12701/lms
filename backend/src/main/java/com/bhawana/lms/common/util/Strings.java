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
     * Replaces control characters (including CR/LF) with {@code '_'} before a value is written
     * to a log line, so user-influenced input cannot forge additional log entries. Structured
     * values that are already bounded (enums, numbers) don't need this — apply it to request
     * paths, bucket keys, principal names, and exception text derived from remote input.
     */
    public static String forLog(String value) {
        if (value == null) {
            return null;
        }
        // Line breaks first: those are the characters that let input forge new log lines.
        return value.replace("\n", "_").replace("\r", "_").replaceAll("\\p{Cntrl}", "_");
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
