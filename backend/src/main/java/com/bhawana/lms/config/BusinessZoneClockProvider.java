package com.bhawana.lms.config;

import jakarta.validation.ClockProvider;
import java.time.Clock;

/**
 * The "now" Bean Validation evaluates date constraints against. Hibernate Validator
 * resolves {@code @Past}, {@code @PastOrPresent}, {@code @Future} and
 * {@code @FutureOrPresent} on {@link java.time.LocalDate} fields from this clock —
 * which defaults to the JVM default zone when no provider is configured.
 *
 * <p>Business dates (payment {@code postedAt}, foreclosure {@code settlementDate}) are
 * contractual dates in {@link TimeConfig#BUSINESS_ZONE} (M09), so "present" must mean the
 * current business day, not the JVM zone's day. With the default clock a request dated on
 * the current business date is rejected as future-dated for the whole 18:30-24:00 UTC
 * window (00:00-05:30 IST next day) — the validation layer and the service layer would
 * accept no date at all. Registered globally via {@code META-INF/validation.xml}.
 */
public class BusinessZoneClockProvider implements ClockProvider {

    @Override
    public Clock getClock() {
        return Clock.system(TimeConfig.BUSINESS_ZONE);
    }
}
