package com.bhawana.lms.config;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import org.springframework.stereotype.Component;

/**
 * Single source for the business calendar. The business day is defined in
 * {@link TimeConfig#BUSINESS_ZONE} (Asia/Kolkata), not UTC and not the caller's
 * locale: an approval recorded at 02:00 IST belongs to that IST date even though
 * UTC is still on the previous day (M09).
 */
@Component
public class BusinessCalendar {

    private final Clock clock;

    public BusinessCalendar(Clock clock) {
        this.clock = clock;
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    /**
     * The business date an instant falls on. Contractual dates derived from
     * persisted instants (approval anchoring, schedule windows, DPD) go through
     * here so they agree with {@link #today()} at day boundaries.
     */
    public LocalDate businessDate(Instant instant) {
        return instant.atZone(TimeConfig.BUSINESS_ZONE).toLocalDate();
    }

    /** Inclusive start instant of a business date — used for date-only query filters. */
    public Instant startOfBusinessDay(LocalDate date) {
        return date.atStartOfDay(TimeConfig.BUSINESS_ZONE).toInstant();
    }

    /** Exclusive end instant of a business date — the start of the next business day. */
    public Instant endOfBusinessDayExclusive(LocalDate date) {
        return date.plusDays(1).atStartOfDay(TimeConfig.BUSINESS_ZONE).toInstant();
    }
}
