package com.bhawana.lms.service;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Normalises account-holder names for disbursement comparison (issue #125).
 * Pipeline: NFKD → strip diacritics → strip punctuation → collapse whitespace → uppercase.
 * Non-matches are soft-warned only when the sole divergence is a leading honorific; all other
 * divergences (including initial expansion such as JOHN K vs JOHN KUMAR) are hard rejects.
 */
@Component
public class BankAccountHolderNameMatcher {

    private static final Pattern HONORIFIC_PREFIX = Pattern.compile(
            "^(MR|MRS|MS|MISS|DR|SHRI|SRI|SMT)\\.?\\s+",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    public enum HolderNameMatchOutcome {
        MATCH,
        SOFT_MISMATCH,
        HARD_MISMATCH
    }

    public HolderNameMatchOutcome compare(String left, String right) {
        if (matches(left, right)) {
            return HolderNameMatchOutcome.MATCH;
        }
        if (matches(stripHonorificPrefixes(left), right)
                || matches(left, stripHonorificPrefixes(right))
                || matches(stripHonorificPrefixes(left), stripHonorificPrefixes(right))) {
            return HolderNameMatchOutcome.SOFT_MISMATCH;
        }
        return HolderNameMatchOutcome.HARD_MISMATCH;
    }

    public boolean matches(String left, String right) {
        String normalizedLeft = normalize(left);
        String normalizedRight = normalize(right);
        if (normalizedLeft == null || normalizedRight == null) {
            return normalizedLeft == normalizedRight;
        }
        return normalizedLeft.equals(normalizedRight);
    }

    public String normalize(String value) {
        if (value == null) {
            return null;
        }
        String nfkd = Normalizer.normalize(value.trim(), Normalizer.Form.NFKD);
        String withoutDiacritics = nfkd.replaceAll("\\p{M}+", "");
        String withoutPunctuation = withoutDiacritics.replaceAll("[.,'\\-]", "");
        String collapsed = withoutPunctuation.replaceAll("\\s+", " ").trim();
        if (collapsed.isBlank()) {
            return null;
        }
        return collapsed.toUpperCase(Locale.ROOT);
    }

    private String stripHonorificPrefixes(String value) {
        String normalized = normalize(value);
        if (normalized == null) {
            return null;
        }
        String stripped = normalized;
        while (true) {
            Matcher matcher = HONORIFIC_PREFIX.matcher(stripped);
            if (!matcher.find()) {
                break;
            }
            stripped = stripped.substring(matcher.end()).trim();
        }
        return stripped.isBlank() ? null : stripped;
    }
}
