package com.quorum.placementservice.event;

/** Shape shared by SESSION_SCHEDULED, SESSION_UPDATED and SESSION_CANCELLED. */
public record SessionEvent(
        String eventId,
        String eventType,
        int schemaVersion,
        String occurredAt,
        long driveId,
        long roundId,
        long sessionId,
        String date,
        String startTime,
        String endTime,
        String room,
        String status) {
}
