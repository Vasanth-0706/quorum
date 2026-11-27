package com.quorum.checkinservice.service;

import com.quorum.checkinservice.entity.SessionView;

import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Pure rule: which session in a room is running at a local time. A session runs from startTime to
 * endTime, both inclusive (no separate check-in window: a tap at any moment of the session counts).
 * If several SCHEDULED sessions in the room contain the time, the one that started first wins.
 * A CANCELLED session never takes taps, but is reported so the reader can say why.
 */
public final class SessionMatcher {

    public static final String SCHEDULED = "SCHEDULED";
    public static final String CANCELLED = "CANCELLED";

    public record Match(Optional<SessionView> running, Optional<SessionView> cancelled) {
    }

    private SessionMatcher() {
    }

    /** @param inRoomOnDate the sessions of one room on the tap's local date */
    public static Match match(List<SessionView> inRoomOnDate, LocalTime at) {
        Comparator<SessionView> byStart = Comparator.comparing(SessionView::getStartTime)
                .thenComparing(SessionView::getSessionId);
        List<SessionView> containing = inRoomOnDate.stream()
                .filter(s -> !at.isBefore(s.getStartTime()) && !at.isAfter(s.getEndTime()))
                .sorted(byStart)
                .toList();
        return new Match(
                containing.stream().filter(s -> SCHEDULED.equals(s.getStatus())).findFirst(),
                containing.stream().filter(s -> CANCELLED.equals(s.getStatus())).findFirst());
    }
}
