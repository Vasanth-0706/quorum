package com.quorum.checkinservice.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * GET /sessions/{sessionId}/attendance-sheet: the round's roster merged with the latest mark of
 * each student, then the marked students who are not on the roster.
 */
public record AttendanceSheetResponse(long sessionId, long roundId, long driveId, LocalDate date,
                                      LocalTime startTime, LocalTime endTime, String room, String status,
                                      Counts counts, List<Row> students) {

    public enum Status { PRESENT, ABSENT, NOT_MARKED }

    public record Counts(int present, int absent, int notMarked, int total) {
    }

    /** source, markedAt and markedBy are null when the student is not marked. */
    public record Row(String studentId, String name, boolean onShortlist, Status status, String source,
                      Instant markedAt, String markedBy) {
    }
}
