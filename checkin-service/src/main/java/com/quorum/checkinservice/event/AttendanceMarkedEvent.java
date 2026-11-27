package com.quorum.checkinservice.event;

/**
 * checkin-events, key = sessionId. One per stored mark. Timestamps ISO-8601 UTC with offset.
 * source is RFID, MANUAL or FILE; markId is the mark's UUID, so consumers can de-duplicate on it.
 */
public record AttendanceMarkedEvent(
        String eventId,
        String eventType,
        int schemaVersion,
        String occurredAt,
        long sessionId,
        long roundId,
        String studentId,
        boolean present,
        String source,
        String markedBy,
        String markedAt,
        String markId) {
}
