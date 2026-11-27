package com.quorum.checkinservice.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/**
 * The one clock every service reads. Its zone (quorum.time-zone, default UTC) is the zone in which
 * session dates and times are read, so a tap Instant is matched to the right local date and time.
 * Stored Instants are always UTC.
 * Tests replace it with a fixed or mutable clock.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock(@Value("${quorum.time-zone:UTC}") String zone) {
        return Clock.system(ZoneId.of(zone));
    }
}
