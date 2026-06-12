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
}
