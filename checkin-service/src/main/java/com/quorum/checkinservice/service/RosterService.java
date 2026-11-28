package com.quorum.checkinservice.service;

import com.quorum.checkinservice.dto.RosterStudentResponse;
import com.quorum.checkinservice.entity.RosterEntry;
import com.quorum.checkinservice.entity.StudentName;
import com.quorum.checkinservice.event.EventFields;
import com.quorum.checkinservice.repository.RosterEntryRepository;
import com.quorum.checkinservice.repository.StudentNameRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Round rosters (SHORTLIST_UPLOADED) and the student_name table (shortlist and attendance uploads).
 *
 * <ul>
 *   <li>A shortlist upload replaces the round's whole roster. Uploads of one round share the Kafka
 *       key roundId, so they arrive in upload order and the last one applied wins, exactly like
 *       timetable-service's shortlist.</li>
 *   <li>Every non-empty name that arrives is stored in student_name; the newest one wins.</li>
 * </ul>
 */
@Service
public class RosterService {

    private static final Logger log = LoggerFactory.getLogger(RosterService.class);

    private final RosterEntryRepository roster;
    private final StudentNameRepository names;
    private final Clock clock;

    public RosterService(RosterEntryRepository roster, StudentNameRepository names, Clock clock) {
        this.roster = roster;
        this.names = names;
        this.clock = clock;
    }

    /** SHORTLIST_UPLOADED: replaces the round's roster and stores the names. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void replace(JsonNode event) {
        long roundId = EventFields.id(event, "roundId");
        String uploadId = EventFields.text(event, "uploadId", 64);
        Map<String, String> students = StudentList.read(event);
        Instant now = clock.instant();

        int removed = roster.deleteByRoundId(roundId);
        roster.saveAll(students.entrySet().stream()
                .map(s -> new RosterEntry(roundId, s.getKey(), s.getValue(), uploadId, now))
                .toList());
        remember(students);
        log.info("Round {} roster replaced by upload {}: {} students (was {})", roundId, uploadId,
                students.size(), removed);
    }

    /** Stores every non-null name; the newest one wins. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void remember(Map<String, String> studentNames) {
        Instant now = clock.instant();
        Map<String, StudentName> known = byId(names.findAllById(studentNames.keySet()));
        studentNames.forEach((id, name) -> {
            if (name == null) {
                return;
            }
            StudentName row = known.get(id);
            if (row == null) {
                names.save(new StudentName(id, name, now));
            } else if (!name.equals(row.getName())) {
                row.setName(name);
                row.setUpdatedAt(now);
            }
        });
    }

    /** The round's roster by studentId; a name missing from the file falls back to student_name. */
    @Transactional(readOnly = true)
    public List<RosterStudentResponse> roster(long roundId) {
        List<RosterEntry> entries = roster.findByRoundIdOrderByStudentIdAsc(roundId);
        Map<String, String> fallback = namesOf(entries.stream().map(RosterEntry::getStudentId).toList());
        return entries.stream()
                .map(e -> new RosterStudentResponse(e.getStudentId(),
                        e.getStudentName() != null ? e.getStudentName() : fallback.get(e.getStudentId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<RosterEntry> entries(long roundId) {
        return roster.findByRoundIdOrderByStudentIdAsc(roundId);
    }

    /** studentId → latest known name, for the given students that have one. */
    @Transactional(readOnly = true)
    public Map<String, String> namesOf(Collection<String> studentIds) {
        return names.findAllById(studentIds).stream()
                .collect(Collectors.toMap(StudentName::getStudentId, StudentName::getName));
    }

    private static Map<String, StudentName> byId(List<StudentName> rows) {
        return rows.stream().collect(Collectors.toMap(StudentName::getStudentId, Function.identity()));
    }
}
