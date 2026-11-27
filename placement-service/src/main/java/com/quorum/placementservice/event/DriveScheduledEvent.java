package com.quorum.placementservice.event;

public record DriveScheduledEvent(
        String eventId,
        String eventType,
        int schemaVersion,
        String occurredAt,
        long driveId,
        String companyName,
        String driveDate,
        String startTime,
        String endTime,
        String venue) {
}
