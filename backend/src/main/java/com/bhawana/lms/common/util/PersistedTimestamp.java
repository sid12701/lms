package com.bhawana.lms.common.util;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Clock reads bound for {@code timestamptz} columns. Postgres stores microseconds, while
 * {@link Instant#now()} may resolve finer digits (nanoseconds on Linux), so an entity that
 * carries the raw clock value would hold more precision than the durable row: a response
 * rendered from the just-persisted entity then diverges from the same record re-rendered
 * after a reload — breaking byte-identical idempotent replays on fast-enough platforms.
 * Any timestamp that will be persisted goes through here, so the in-memory value always
 * equals what the stored row returns.
 */
public final class PersistedTimestamp {

    private PersistedTimestamp() {
    }

    /** The current instant truncated to the precision {@code timestamptz} stores. */
    public static Instant now() {
        return normalize(Instant.now());
    }

    /**
     * Truncates {@code instant} to microseconds — the precision {@code timestamptz} stores —
     * so a caller-supplied timestamp lands on the entity exactly as the stored row will read
     * it back. Null-safe: optional timestamp columns stay null.
     */
    public static Instant normalize(Instant instant) {
        return instant == null ? null : instant.truncatedTo(ChronoUnit.MICROS);
    }
}
