package com.quorum.attendanceservice.service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The OD decision as a pure function: no database, no Spring, no clock.
 *
 * <p>For one student, one round and one date: if the student's latest mark in ANY session of
 * that round on that date is present, the OD window is [earliest start of all the round's
 * sessions that date, cancelled ones included; end of the session the student attended].
 * Every clash slot of that student in the stored result for round + date that overlaps the
 * window ({@code slotStart < windowEnd AND windowStart < slotEnd}) is granted OD.
 *
 * <p>An OD record is unique on (studentId, date, sectionCode, slotStart), which does not include
 * the round, so {@link #decideDay} merges the rounds of one student's day.
 */
public final class OdCalculator {

    private OdCalculator() {
    }

    /** A session of the round on the date. Cancelled sessions still count. */
    public record Session(long sessionId, LocalTime start, LocalTime end, boolean cancelled) {
    }

    /** One clash of this student in the stored CLASHES_COMPUTED result for round + date. */
    public record ClashSlot(String sectionCode, String courseName, DayOfWeek dayOfWeek,
                            LocalTime slotStart, LocalTime slotEnd) {
    }

    /**
     * Everything known about one student in one round on one date.
     *
     * @param presentSessionIds sessions whose LATEST mark for the student is present
     * @param clashes           the student's clashes in the stored result; empty when there is none
     */
    public record RoundDay(long roundId, LocalDate date, List<Session> sessions, Set<Long> presentSessionIds,
                           List<ClashSlot> clashes) {
    }

    /** [start, end) the student was away for, and the attended session that ends it. */
    public record Window(LocalTime start, LocalTime end, Session attended) {
    }

    public record Key(String sectionCode, LocalTime slotStart) {
    }

    public record Grant(String sectionCode, LocalTime slotStart, LocalTime slotEnd, String courseName,
                        long sessionId, long roundId, String reason) {

        public Key key() {
            return new Key(sectionCode, slotStart);
        }
    }

    /** Touching edges do not overlap: a 10:00-11:00 slot and a window from 11:00 do not clash. */
    public static boolean overlaps(LocalTime slotStart, LocalTime slotEnd, LocalTime windowStart, LocalTime windowEnd) {
        return slotStart.isBefore(windowEnd) && windowStart.isBefore(slotEnd);
    }

    /**
     * Empty when the student is not present in any session of the round that date. If they are
     * present in several, the window ends with the one that ends last (lowest sessionId on a tie).
     */
    public static Optional<Window> window(RoundDay day) {
        Optional<LocalTime> earliest = day.sessions().stream().map(Session::start).min(Comparator.naturalOrder());
        Optional<Session> attended = day.sessions().stream()
                .filter(s -> day.presentSessionIds().contains(s.sessionId()))
                .max(Comparator.comparing(Session::end).thenComparing(Session::sessionId, Comparator.reverseOrder()));
        if (earliest.isEmpty() || attended.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Window(earliest.get(), attended.get().end(), attended.get()));
    }

    /** The OD this one round justifies for the student on the date. */
    public static List<Grant> decide(RoundDay day) {
        Optional<Window> window = window(day);
        if (window.isEmpty()) {
            return List.of();
        }
        Window w = window.get();
        DayOfWeek weekday = day.date().getDayOfWeek();
        String reason = reason(day.roundId(), w.attended());
        return day.clashes().stream()
                .filter(c -> c.dayOfWeek() == weekday)
                .filter(c -> overlaps(c.slotStart(), c.slotEnd(), w.start(), w.end()))
                .map(c -> new Grant(c.sectionCode(), c.slotStart(), c.slotEnd(), c.courseName(),
                        w.attended().sessionId(), day.roundId(), reason))
                .distinct()
                .toList();
    }

    /**
     * The OD of one student on one date over every round that clashes that day, by key.
     * When two rounds justify the same slot, the lower roundId (then sessionId) wins, so
     * the answer does not depend on the order the rounds are given in.
     */
    public static Map<Key, Grant> decideDay(Collection<RoundDay> rounds) {
        Map<Key, Grant> out = new LinkedHashMap<>();
        rounds.stream()
                .sorted(Comparator.comparingLong(RoundDay::roundId))
                .flatMap(r -> decide(r).stream())
                .sorted(Comparator.comparing((Grant g) -> g.slotStart())
                        .thenComparing(Grant::sectionCode)
                        .thenComparingLong(Grant::roundId)
                        .thenComparingLong(Grant::sessionId))
                .forEach(g -> out.putIfAbsent(g.key(), g));
        return out;
    }

    /**
     * Why an OD the given round used to justify no longer holds.
     *
     * @param day what is known now about that round for the student; null if nothing
     */
    public static String revokeReason(long roundId, String sectionCode, LocalTime slotStart, RoundDay day) {
        boolean stillClashes = day != null && day.clashes().stream()
                .anyMatch(c -> Objects.equals(c.sectionCode(), sectionCode) && c.slotStart().equals(slotStart));
        if (!stillClashes) {
            return "Slot is no longer in the clash list of round " + roundId;
        }
        Optional<Window> window = window(day);
        if (window.isEmpty()) {
            return "No longer marked present in any session of round " + roundId;
        }
        return "Slot no longer overlaps the OD window " + window.get().start() + "-" + window.get().end()
                + " of round " + roundId;
    }

    static String reason(long roundId, Session attended) {
        return "Attended session " + attended.sessionId() + " (" + attended.start() + "-" + attended.end()
                + ") of placement round " + roundId;
    }
}
