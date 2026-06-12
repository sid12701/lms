package com.bhawana.lms.config;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TimeConfig {

    public static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Kolkata");

    @Bean
    Clock clock() {
        return Clock.system(BUSINESS_ZONE);
    }
}
