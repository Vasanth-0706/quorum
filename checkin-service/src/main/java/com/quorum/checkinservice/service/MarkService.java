package com.quorum.checkinservice.service;

import com.quorum.checkinservice.config.Topics;
import com.quorum.checkinservice.entity.Mark;
import com.quorum.checkinservice.entity.Mark.Source;
import com.quorum.checkinservice.entity.SessionView;
import com.quorum.checkinservice.event.AttendanceMarkedEvent;
import com.quorum.checkinservice.event.EventTypes;
import com.quorum.checkinservice.repository.MarkRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Stores marks as versioned rows (never updated) and queues ATTENDANCE_MARKED for each new one in
 * the outbox, in the same transaction. The latest mark of a student in a session is the highest version.
 */
@Service
public class MarkService {

    static final DateTimeFormatter ISO_UTC = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
            .withZone(ZoneOffset.UTC);

    private final MarkRepository marks;
    private final OutboxService outbox;
    private final Clock clock;

    public MarkService(MarkRepository marks, OutboxService outbox, Clock clock) {
        this.marks = marks;
        this.outbox = outbox;
        this.clock = clock;
    }

    /** Appends a mark with a random markId. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Mark record(SessionView session, String studentId, boolean present, Source source, String markedBy,
                       Instant markedAt) {
        return record(UUID.randomUUID().toString(), session, studentId, present, source, markedBy, markedAt);
    }

    /** Appends a mark as the next version for its session + student and queues ATTENDANCE_MARKED. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Mark record(String markId, SessionView session, String studentId, boolean present, Source source,
                       String markedBy, Instant markedAt) {
        int version = latest(session.getSessionId(), studentId).map(Mark::getVersion).orElse(0) + 1;
        Mark mark = marks.saveAndFlush(new Mark(markId, session.getSessionId(), session.getRoundId(), studentId,
                version, present, source, markedBy, markedAt, clock.instant()));
        outbox.append(Topics.CHECKIN_EVENTS, String.valueOf(mark.getSessionId()), EventTypes.ATTENDANCE_MARKED,
                event(mark));
        return mark;
    }

    public Optional<Mark> latest(long sessionId, String studentId) {
        return marks.findFirstBySessionIdAndStudentIdOrderByVersionDesc(sessionId, studentId);
    }

    public boolean hasMark(long sessionId, String studentId, Source source) {
        return marks.existsBySessionIdAndStudentIdAndSource(sessionId, studentId, source);
    }

    public boolean exists(String markId) {
        return marks.existsByMarkId(markId);
    }

    /** The latest mark of every student in the session, by studentId; or every version with history. */
    @Transactional(readOnly = true)
    public List<Mark> ofSession(long sessionId, boolean history) {
        List<Mark> all = marks.findBySessionIdOrderByStudentIdAscVersionDesc(sessionId);
        if (history) {
            return all;
        }
        List<Mark> latest = new ArrayList<>();
        String previous = null;
        for (Mark m : all) {
            if (!m.getStudentId().equals(previous)) {
                latest.add(m);
                previous = m.getStudentId();
            }
        }
        return latest;
    }

    AttendanceMarkedEvent event(Mark m) {
        return new AttendanceMarkedEvent(
                UUID.randomUUID().toString(),
                EventTypes.ATTENDANCE_MARKED,
                EventTypes.SCHEMA_VERSION,
                ISO_UTC.format(clock.instant()),
                m.getSessionId(),
                m.getRoundId(),
                m.getStudentId(),
                m.isPresent(),
                m.getSource().name(),
                m.getMarkedBy(),
                ISO_UTC.format(m.getMarkedAt()),
                m.getMarkId());
    }
}
