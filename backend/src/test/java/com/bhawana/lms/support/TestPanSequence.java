package com.bhawana.lms.support;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Hands out borrower PANs that are unique for the lifetime of the JVM.
 *
 * <p>Replaces a random draw that five integration classes each declared privately and identically:
 * {@code String.format("ABCDE%04dF", Math.abs(UUID.randomUUID().hashCode()) % 10_000)}. It drew from
 * 10,000 values, all five shared that space, and none of them clean the database between tests — so
 * borrowers accumulated across a suite run until a repeat draw reached
 * {@link com.bhawana.lms.service.BorrowerOnboardingService} carrying a different name than the
 * borrower already holding that PAN. Onboarding rejected it as a borrower identity conflict, and the
 * seed helper of whichever test drew the duplicate failed with {@code 409} — far from the behaviour
 * that test was actually asserting, and only on some runs.
 *
 * <p>The {@code TSTP} prefix keeps these clear of the {@code ABCDE} space that hardcoded
 * {@code ABCDE1234F} fixtures elsewhere still occupy. Rolling a letter through the fifth position as
 * the digits wrap gives 260,000 distinct PANs before any value could repeat, which no suite run
 * approaches.
 */
public final class TestPanSequence {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private TestPanSequence() {
    }

    /** A PAN matching {@code ^[A-Za-z]{5}[0-9]{4}[A-Za-z]$}, distinct from every other call. */
    public static String uniquePan() {
        int next = SEQUENCE.getAndIncrement();
        char block = (char) ('A' + (next / 10_000) % 26);
        return String.format("TSTP%c%04dZ", block, next % 10_000);
    }
}
