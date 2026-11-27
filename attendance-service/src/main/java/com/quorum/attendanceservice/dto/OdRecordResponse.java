package com.quorum.attendanceservice.dto;

import com.quorum.attendanceservice.entity.OdRecord;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

public record OdRecordResponse(String studentId, LocalDate date, String sectionCode, String courseName,
                               LocalTime slotStart, LocalTime slotEnd, Long sessionId, Long roundId, String reason,
                               String status, int version, Instant updatedAt) {

    public static OdRecordResponse from(OdRecord r) {
        return new OdRecordResponse(r.getStudentId(), r.getDate(), r.getSectionCode(), r.getCourseName(),
                r.getSlotStart(), r.getSlotEnd(), r.getSessionId(), r.getRoundId(), r.getReason(),
                r.getStatus().name(), r.getVersion(), r.getUpdatedAt());
    }
}
