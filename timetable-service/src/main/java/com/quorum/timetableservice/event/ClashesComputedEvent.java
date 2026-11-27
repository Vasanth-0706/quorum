package com.quorum.timetableservice.event;

import java.util.List;

/**
 * clash-events, key = roundId. A full replacement of the clash list for roundId + date.
 * windowStart / windowEnd are null when the round no longer has a session on that date.
 */
public record ClashesComputedEvent(
        String eventId,
        String eventType,
        int schemaVersion,
        String occurredAt,
        long roundId,
        String date,
        String windowStart,
        String windowEnd,
        String semester,
        List<Item> clashes) {

    public record Item(String studentId, String sectionCode, String courseName, String dayOfWeek,
                       String slotStart, String slotEnd, String room) {
    }
}
