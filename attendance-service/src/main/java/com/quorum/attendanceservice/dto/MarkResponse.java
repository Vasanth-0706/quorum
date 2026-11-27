package com.quorum.attendanceservice.dto;

import com.quorum.attendanceservice.entity.AttendanceMark;

import java.time.Instant;

public record MarkResponse(String markId, Long sessionId, String studentId, int version, boolean present,
                           String source, String markedBy, Instant markedAt, Instant receivedAt) {

    public static MarkResponse from(AttendanceMark m) {
        return new MarkResponse(m.getMarkId(), m.getSessionId(), m.getStudentId(), m.getVersion(), m.isPresent(),
                m.getSource().name(), m.getMarkedBy(), m.getMarkedAt(), m.getReceivedAt());
    }
}
