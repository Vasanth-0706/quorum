package com.quorum.timetableservice.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/**
 * Pure clash computation: no database, no Spring, only values in and values out.
 *
 * <p>For one round on one date the window is [earliest session start, latest session end]
 * over every session of that round on that date (cancelled ones included). A shortlisted
 * student clashes with a slot of one of their sections when the slot falls on the date's
 * day of week and overlaps the window: {@code slotStart < windowEnd && windowStart < slotEnd}.
 * Touching edges (a slot ending exactly when the window starts) do not clash.
 */
public final class ClashCalculator {

    /** Start and end of one session; only the times matter, the caller filters by round and date. */
    public record SessionTime(LocalTime start, LocalTime end) {
    }

    public record SlotInfo(String sectionCode, String courseName, DayOfWeek dayOfWeek,
                           LocalTime start, LocalTime end, String room) {
    }

    public record Window(LocalTime start, LocalTime end) {
    }

    public record Clash(String studentId, String sectionCode, String courseName, DayOfWeek dayOfWeek,
                        LocalTime slotStart, LocalTime slotEnd, String room) {
    }

    /** {@code window} is empty when the round has no session on that date. */
    public record Result(Optional<Window> window, List<Clash> clashes) {
    }

    /** Stable order, so equal inputs always give equal lists and equal hashes. */
    static final Comparator<Clash> ORDER = Comparator.comparing(Clash::studentId)
            .thenComparing(Clash::slotStart)
            .thenComparing(Clash::slotEnd)
            .thenComparing(Clash::sectionCode)
            .thenComparing(c -> c.courseName() == null ? "" : c.courseName())
            .thenComparing(c -> c.room() == null ? "" : c.room());

    private ClashCalculator() {
    }

    public static Optional<Window> window(Collection<SessionTime> sessions) {
        LocalTime start = null;
        LocalTime end = null;
        for (SessionTime s : sessions) {
            if (start == null || s.start().isBefore(start)) {
                start = s.start();
            }
            if (end == null || s.end().isAfter(end)) {
                end = s.end();
            }
        }
        return start == null ? Optional.empty() : Optional.of(new Window(start, end));
    }

    public static boolean overlaps(LocalTime slotStart, LocalTime slotEnd, Window w) {
        return slotStart.isBefore(w.end()) && w.start().isBefore(slotEnd);
    }

    /**
     * @param date               the session date
     * @param sessions           every session of the round on that date
     * @param shortlist          shortlisted student ids of the round
     * @param sectionsByStudent  sections each student is enrolled in (active semester)
     * @param slotsBySection     weekly slots of each section (active semester)
     */
    public static Result compute(LocalDate date,
                                 Collection<SessionTime> sessions,
                                 Collection<String> shortlist,
                                 Map<String, ? extends Collection<String>> sectionsByStudent,
                                 Map<String, ? extends Collection<SlotInfo>> slotsBySection) {
        Optional<Window> window = window(sessions);
        if (window.isEmpty()) {
            return new Result(window, List.of());
        }
        Window w = window.get();
        DayOfWeek day = date.getDayOfWeek();
        List<Clash> clashes = new ArrayList<>();
        for (String studentId : new TreeSet<>(shortlist)) {
            Collection<String> sections = sectionsByStudent.get(studentId);
            if (sections == null) {
                continue; // not enrolled in anything this semester
            }
            for (String section : new TreeSet<>(sections)) {
                Collection<? extends SlotInfo> sectionSlots = slotsBySection.get(section);
                if (sectionSlots == null) {
                    continue;
                }
                for (SlotInfo slot : sectionSlots) {
                    if (slot.dayOfWeek() == day && overlaps(slot.start(), slot.end(), w)) {
                        clashes.add(new Clash(studentId, section, slot.courseName(), slot.dayOfWeek(),
                                slot.start(), slot.end(), slot.room()));
                    }
                }
            }
        }
        clashes.sort(ORDER);
        return new Result(window, List.copyOf(clashes));
    }

    /** SHA-256 over everything a consumer would see except eventId and occurredAt. */
    public static String hash(String semester, Result result) {
        StringBuilder b = new StringBuilder();
        b.append(semester).append('|');
        result.window().ifPresent(w -> b.append(w.start()).append('-').append(w.end()));
        b.append('\n');
        for (Clash c : result.clashes()) {
            b.append(c.studentId()).append('|').append(c.sectionCode()).append('|').append(c.courseName())
                    .append('|').append(c.dayOfWeek()).append('|').append(c.slotStart()).append('|')
                    .append(c.slotEnd()).append('|').append(c.room()).append('\n');
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(b.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
