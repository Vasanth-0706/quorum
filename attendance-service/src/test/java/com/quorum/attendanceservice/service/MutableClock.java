package com.quorum.attendanceservice.service;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A clock the test can move, so the 14-day correction rule can be checked on exact days. */
public class MutableClock extends Clock {

    private volatile Instant now;

    public MutableClock(Instant now) {
        this.now = now;
    }

    /** Noon UTC on the given day. */
    public void setDay(LocalDate day) {
        now = day.atTime(12, 0).toInstant(ZoneOffset.UTC);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
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
            return new MutableClock(Instant.parse("2026-12-07T12:00:00Z"));
        }
    }
}
