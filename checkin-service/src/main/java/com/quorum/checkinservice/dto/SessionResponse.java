package com.quorum.checkinservice.dto;

import com.quorum.checkinservice.entity.SessionView;

import java.time.LocalDate;
import java.time.LocalTime;

public record SessionResponse(long sessionId, long roundId, long driveId, LocalDate date, LocalTime startTime,
                              LocalTime endTime, String room, String status) {

    public static SessionResponse from(SessionView s) {
        return new SessionResponse(s.getSessionId(), s.getRoundId(), s.getDriveId(), s.getDate(), s.getStartTime(),
                s.getEndTime(), s.getRoom(), s.getStatus());
    }
}
