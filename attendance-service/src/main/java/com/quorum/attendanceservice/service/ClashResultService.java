package com.quorum.attendanceservice.service;

import com.quorum.attendanceservice.entity.ClashEntry;
import com.quorum.attendanceservice.entity.ClashResult;
import com.quorum.attendanceservice.event.EventFields;
import com.quorum.attendanceservice.event.InvalidEventException;
import com.quorum.attendanceservice.repository.ClashEntryRepository;
import com.quorum.attendanceservice.repository.ClashResultRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Stores CLASHES_COMPUTED as a FULL replacement of the clash list for its roundId + date, then
 * recomputes OD for every student who clashed before or clashes now.
 * An empty list, or windowStart / windowEnd = null, means no clashes.
 */
@Service
public class ClashResultService {

    private final ClashResultRepository results;
    private final ClashEntryRepository entries;
    private final OdService od;
    private final Clock clock;

    public ClashResultService(ClashResultRepository results, ClashEntryRepository entries, OdService od, Clock clock) {
        this.results = results;
        this.entries = entries;
        this.od = od;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public OdService.Changes replace(String eventId, JsonNode e) {
        long roundId = EventFields.id(e, "roundId");
        LocalDate date = EventFields.date(e, "date");
        LocalTime windowStart = EventFields.optionalTime(e, "windowStart");
        LocalTime windowEnd = EventFields.optionalTime(e, "windowEnd");
        boolean empty = windowStart == null || windowEnd == null;
        List<ClashEntry> fresh = empty ? List.of() : parse(roundId, date, EventFields.array(e, "clashes"));

        Set<String> before = new HashSet<>(entries.findStudentIds(roundId, date));
        entries.deleteByRoundAndDate(roundId, date);
        entries.saveAll(fresh);

        ClashResult header = results.findByRoundIdAndDate(roundId, date).orElseGet(() -> {
            ClashResult r = new ClashResult();
            r.setRoundId(roundId);
            r.setDate(date);
            return r;
        });
        header.setWindowStart(empty ? null : windowStart);
        header.setWindowEnd(empty ? null : windowEnd);
        header.setSemester(EventFields.optionalText(e, "semester", 32));
        header.setClashCount(fresh.size());
        header.setLastEventId(eventId);
        header.setUpdatedAt(clock.instant());
        results.save(header);

        return od.recomputeRoundDate(roundId, date, before);
    }

    private static List<ClashEntry> parse(long roundId, LocalDate date, JsonNode items) {
        // de-duplicated on the OD key, first one wins
        Map<String, ClashEntry> out = new LinkedHashMap<>();
        for (JsonNode c : items) {
            if (!c.isObject()) {
                throw new InvalidEventException("clashes[] item is not an object");
            }
            String studentId = EventFields.studentId(c, "studentId");
            String section = EventFields.text(c, "sectionCode", 32);
            LocalTime start = EventFields.time(c, "slotStart");
            LocalTime end = EventFields.time(c, "slotEnd");
            if (!start.isBefore(end)) {
                throw new InvalidEventException("slotStart must be before slotEnd for " + studentId + " " + section);
            }
            DayOfWeek day;
            try {
                day = DayOfWeek.valueOf(EventFields.text(c, "dayOfWeek"));
            } catch (IllegalArgumentException ex) {
                throw new InvalidEventException("Unknown dayOfWeek " + c.get("dayOfWeek"), ex);
            }
            out.putIfAbsent(studentId + "|" + section + "|" + start,
                    new ClashEntry(roundId, date, studentId, section, EventFields.optionalText(c, "courseName", 200),
                            day, start, end, EventFields.optionalText(c, "room", 60)));
        }
        return new ArrayList<>(out.values());
    }
}
