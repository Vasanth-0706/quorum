package com.quorum.attendanceservice.event;

/** od-events, key = studentId: OD_GRANTED or OD_REVOKED for one class slot. Dates YYYY-MM-DD, times HH:mm:ss. */
public record OdEvent(
        String eventId,
        String eventType,
        int schemaVersion,
        String occurredAt,
        String studentId,
        String date,
        String sectionCode,
        String courseName,
        String slotStart,
        String slotEnd,
        long sessionId,
        String reason) {
}
