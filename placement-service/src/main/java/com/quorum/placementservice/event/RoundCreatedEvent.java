package com.quorum.placementservice.event;

public record RoundCreatedEvent(
        String eventId,
        String eventType,
        int schemaVersion,
        String occurredAt,
        long driveId,
        long roundId,
        String name,
        int sequence) {
}
