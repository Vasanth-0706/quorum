package com.quorum.checkinservice.service;

import com.quorum.checkinservice.entity.SessionView;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SessionMatcherTest {

    private static SessionView session(long id, String start, String end, String status) {
        SessionView s = new SessionView();
        s.setSessionId(id);
        s.setRoundId(7L);
        s.setDriveId(3L);
        s.setDate(LocalDate.parse("2026-12-07"));
        s.setStartTime(LocalTime.parse(start));
        s.setEndTime(LocalTime.parse(end));
        s.setRoom("Hall A");
        s.setStatus(status);
        return s;
    }

    private static SessionMatcher.Match at(String time, SessionView... sessions) {
        return SessionMatcher.match(List.of(sessions), LocalTime.parse(time));
    }

    @Test
    void tapInsideTheSessionMatches() {
        SessionView s = session(41, "09:30", "10:15", "SCHEDULED");
        assertThat(at("09:45", s).running()).contains(s);
    }

    @Test
    void startAndEndAreInclusive() {
        SessionView s = session(41, "09:30", "10:15", "SCHEDULED");
        assertThat(at("09:30", s).running()).contains(s);
        assertThat(at("10:15", s).running()).contains(s);
        assertThat(at("09:29:59", s).running()).isEmpty();
        assertThat(at("10:15:01", s).running()).isEmpty();
    }

    @Test
    void cancelledSessionIsNeverRunningButIsReported() {
        SessionView s = session(41, "09:30", "10:15", "CANCELLED");
        SessionMatcher.Match m = at("09:45", s);
        assertThat(m.running()).isEmpty();
        assertThat(m.cancelled()).contains(s);
    }

    @Test
    void scheduledWinsOverCancelledAtTheSameTime() {
        SessionView cancelled = session(40, "09:00", "10:00", "CANCELLED");
        SessionView scheduled = session(41, "09:30", "10:15", "SCHEDULED");
        assertThat(at("09:45", cancelled, scheduled).running()).contains(scheduled);
    }

    @Test
    void overlappingScheduledSessionsPickTheOneThatStartedFirst() {
        SessionView later = session(42, "09:40", "10:30", "SCHEDULED");
        SessionView earlier = session(41, "09:30", "10:15", "SCHEDULED");
        assertThat(at("09:45", later, earlier).running()).contains(earlier);
    }

    @Test
    void consecutiveSessionsSwitchAtTheBoundary() {
        SessionView first = session(41, "09:30", "10:15", "SCHEDULED");
        SessionView second = session(42, "10:15", "11:00", "SCHEDULED");
        assertThat(at("10:15", first, second).running()).contains(first);
        assertThat(at("10:16", first, second).running()).contains(second);
    }

    @Test
    void noSessionsNoMatch() {
        assertThat(at("09:45").running()).isEmpty();
        assertThat(at("09:45").cancelled()).isEmpty();
    }
}
