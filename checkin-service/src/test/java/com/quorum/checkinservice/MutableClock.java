package com.quorum.checkinservice;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * A clock the test can move. Its zone is Asia/Kolkata (UTC+05:30) on purpose, so the tests prove
 * that a tap Instant is matched against session times in quorum.time-zone, not in UTC.
 */
public class MutableClock extends Clock {

    public static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");

    private volatile Instant now;

    public MutableClock(Instant now) {
        this.now = now;
    }

    /** Sets the clock to a local date-time in {@link #ZONE}. */
    public void setLocal(LocalDateTime local) {
        now = local.atZone(ZONE).toInstant();
    }

    @Override
    public ZoneId getZone() {
        return ZONE;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now;
    }

    @TestConfiguration
    public static class Config {

        @Bean
        @Primary
        public MutableClock mutableClock() {
            return new MutableClock(Instant.parse("2026-12-07T04:15:00Z")); // 09:45 in Kolkata
        }
    }
}
