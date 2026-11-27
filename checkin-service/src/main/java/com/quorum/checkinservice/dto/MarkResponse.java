package com.quorum.checkinservice.dto;

import com.quorum.checkinservice.entity.Mark;

import java.time.Instant;

public record MarkResponse(String markId, long sessionId, long roundId, String studentId, int version,
                           boolean present, String source, String markedBy, Instant markedAt, Instant createdAt) {

    public static MarkResponse from(Mark m) {
        return new MarkResponse(m.getMarkId(), m.getSessionId(), m.getRoundId(), m.getStudentId(), m.getVersion(),
                m.isPresent(), m.getSource().name(), m.getMarkedBy(), m.getMarkedAt(), m.getCreatedAt());
    }
}
