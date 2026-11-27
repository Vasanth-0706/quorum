package com.quorum.placementservice.dto;

import com.quorum.placementservice.entity.Session;
import com.quorum.placementservice.entity.SessionStatus;

import java.time.LocalDate;
import java.time.LocalTime;

public record SessionResponse(
        Long id,
        Long roundId,
        Long driveId,
        LocalDate date,
        LocalTime startTime,
        LocalTime endTime,
        String room,
        SessionStatus status) {

    public static SessionResponse from(Session s) {
        return new SessionResponse(s.getId(), s.getRound().getId(), s.getRound().getDrive().getId(), s.getDate(),
                s.getStartTime(), s.getEndTime(), s.getRoom(), s.getStatus());
    }
}
