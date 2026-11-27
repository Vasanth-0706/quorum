package com.quorum.attendanceservice.service;

import com.quorum.attendanceservice.entity.AttendanceMark;
import com.quorum.attendanceservice.entity.AttendanceMark.Source;
import com.quorum.attendanceservice.event.EventFields;
import com.quorum.attendanceservice.event.InvalidEventException;
import com.quorum.attendanceservice.repository.AttendanceMarkRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

/**
 * Stores attendance marks as versioned rows (never updated) and recomputes the student's OD.
 * The latest mark of a student in a session is the one with the highest version, i.e. the one
 * that arrived last. Timestamps are not compared: sources do not share a clock, and
 * import-service's uploadedAt has no offset.
 */
@Service
public class MarkService {

    private static final Logger log = LoggerFactory.getLogger(MarkService.class);
    private static final Set<String> EVENT_SOURCES = Set.of("RFID", "MANUAL", "FILE");

    private final AttendanceMarkRepository marks;
    private final OdService od;
    private final Clock clock;

    public MarkService(AttendanceMarkRepository marks, OdService od, Clock clock) {
        this.marks = marks;
        this.od = od;
        this.clock = clock;
    }

    /** checkin-events ATTENDANCE_MARKED. A markId seen before is skipped. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<AttendanceMark> onAttendanceMarked(String eventId, JsonNode e) {
        String markId = EventFields.text(e, "markId", 100);
        long sessionId = EventFields.id(e, "sessionId");
        String studentId = EventFields.studentId(e, "studentId");
        boolean present = EventFields.bool(e, "present");
        String source = EventFields.text(e, "source");
        if (!EVENT_SOURCES.contains(source)) {
            throw new InvalidEventException("Unknown source " + source);
        }
        String markedBy = EventFields.text(e, "markedBy", 64);
        Instant markedAt = EventFields.instant(e, "markedAt");
        return record(markId, sessionId, studentId, present, Source.valueOf(source), markedBy, markedAt, eventId);
    }

    /**
     * Appends a mark as the next version for its session + student and recomputes OD.
     * Empty if a mark with this markId is already stored.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<AttendanceMark> record(String markId, long sessionId, String studentId, boolean present,
                                           Source source, String markedBy, Instant markedAt, String eventId) {
        if (marks.existsByMarkId(markId)) {
            log.info("Mark {} already stored, skipped", markId);
            return Optional.empty();
        }
        int version = latest(sessionId, studentId).map(AttendanceMark::getVersion).orElse(0) + 1;
        AttendanceMark mark = marks.saveAndFlush(new AttendanceMark(markId, sessionId, studentId, version, present,
                source, markedBy, markedAt, eventId, clock.instant()));
        od.recomputeForMark(sessionId, studentId);
        return Optional.of(mark);
    }

    public Optional<AttendanceMark> latest(long sessionId, String studentId) {
        return marks.findFirstBySessionIdAndStudentIdOrderByVersionDesc(sessionId, studentId);
    }
}
