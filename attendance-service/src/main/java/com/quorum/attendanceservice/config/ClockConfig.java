package com.quorum.attendanceservice.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/**
 * The one clock every service reads. Its zone decides what "today" is for the 14-day
 * correction rule (quorum.time-zone, default UTC); stored Instants are always UTC.
 * Tests replace it with a fixed or mutable clock.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock(@Value("${quorum.time-zone:UTC}") String zone) {
        return Clock.system(ZoneId.of(zone));
    }
}
