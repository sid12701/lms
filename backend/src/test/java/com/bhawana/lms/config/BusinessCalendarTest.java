package com.bhawana.lms.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class BusinessCalendarTest {

    @Test
    void todayUsesInjectedClockInBusinessZone() {
        ZoneId zone = TimeConfig.BUSINESS_ZONE;
        // 2026-06-10 20:00 UTC = 2026-06-11 01:30 IST
        Clock clock = Clock.fixed(Instant.parse("2026-06-10T20:00:00Z"), zone);
        BusinessCalendar calendar = new BusinessCalendar(clock);

        assertThat(calendar.today()).isEqualTo(LocalDate.of(2026, 6, 11));
    }

    @Test
    void todayBeforeIstMidnightStillPriorCalendarDay() {
        ZoneId zone = TimeConfig.BUSINESS_ZONE;
        // 2026-06-10 18:29 UTC = 2026-06-10 23:59 IST
        Clock clock = Clock.fixed(Instant.parse("2026-06-10T18:29:00Z"), zone);
        BusinessCalendar calendar = new BusinessCalendar(clock);

        assertThat(calendar.today()).isEqualTo(LocalDate.of(2026, 6, 10));
    }

    @Test
    void businessDateMapsInstantToKolkataDate() {
        BusinessCalendar calendar = new BusinessCalendar(Clock.system(TimeConfig.BUSINESS_ZONE));

        // 20:30 UTC is 02:00 IST the next day — the UTC date must not leak through.
        assertThat(calendar.businessDate(Instant.parse("2026-03-10T20:30:00Z")))
                .isEqualTo(LocalDate.of(2026, 3, 11));
        // 18:29 UTC is 23:59 IST — still the same business day.
        assertThat(calendar.businessDate(Instant.parse("2026-03-10T18:29:00Z")))
                .isEqualTo(LocalDate.of(2026, 3, 10));
    }

    @Test
    void businessDateHandlesLeapDay() {
        BusinessCalendar calendar = new BusinessCalendar(Clock.system(TimeConfig.BUSINESS_ZONE));

        // 2024-02-28T20:00Z is 2024-02-29 01:30 IST — the leap day itself.
        assertThat(calendar.businessDate(Instant.parse("2024-02-28T20:00:00Z")))
                .isEqualTo(LocalDate.of(2024, 2, 29));
    }

    @Test
    void businessDayBoundsBracketTheKolkataDay() {
        BusinessCalendar calendar = new BusinessCalendar(Clock.system(TimeConfig.BUSINESS_ZONE));
        LocalDate date = LocalDate.of(2026, 3, 11);

        assertThat(calendar.startOfBusinessDay(date))
                .isEqualTo(Instant.parse("2026-03-10T18:30:00Z"));
        assertThat(calendar.endOfBusinessDayExclusive(date))
                .isEqualTo(Instant.parse("2026-03-11T18:30:00Z"));
    }
}
