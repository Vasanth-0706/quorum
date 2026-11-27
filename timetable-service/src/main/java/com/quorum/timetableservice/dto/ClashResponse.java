package com.quorum.timetableservice.dto;

import com.quorum.timetableservice.entity.ClashResult;
import com.quorum.timetableservice.event.ClashesComputedEvent;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * The clash list for roundId + date computed from current data, and what was last queued
 * on clash-events for it ({@code lastPublished.current} says whether they still match).
 */
public record ClashResponse(long roundId, LocalDate date, String semester, String windowStart, String windowEnd,
                            List<ClashesComputedEvent.Item> clashes, LastPublished lastPublished) {

    public record LastPublished(String eventId, Instant queuedAt, int clashCount, boolean current) {

        public static LastPublished from(ClashResult r, String currentHash) {
            return new LastPublished(r.getLastEventId(), r.getQueuedAt(), r.getClashCount(),
                    r.getResultHash().equals(currentHash));
        }
    }
}
