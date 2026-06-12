package com.bhawana.lms.config;

import java.time.Clock;
import java.time.LocalDate;
import org.springframework.stereotype.Component;

@Component
public class BusinessCalendar {

    private final Clock clock;

    public BusinessCalendar(Clock clock) {
        this.clock = clock;
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }
}
