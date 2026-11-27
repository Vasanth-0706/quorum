package com.quorum.attendanceservice.service;

import com.quorum.attendanceservice.config.Topics;
import com.quorum.attendanceservice.entity.AttendanceMark;
import com.quorum.attendanceservice.entity.ClashEntry;
import com.quorum.attendanceservice.entity.OdRecord;
import com.quorum.attendanceservice.entity.OdRecordHistory;
import com.quorum.attendanceservice.entity.SessionView;
import com.quorum.attendanceservice.event.OdEvent;
import com.quorum.attendanceservice.event.OdEventFactory;
import com.quorum.attendanceservice.repository.AttendanceMarkRepository;
import com.quorum.attendanceservice.repository.ClashEntryRepository;
import com.quorum.attendanceservice.repository.OdRecordHistoryRepository;
import com.quorum.attendanceservice.repository.OdRecordRepository;
import com.quorum.attendanceservice.repository.SessionViewRepository;
import com.quorum.attendanceservice.service.OdCalculator.ClashSlot;
import com.quorum.attendanceservice.service.OdCalculator.Grant;
import com.quorum.attendanceservice.service.OdCalculator.Key;
import com.quorum.attendanceservice.service.OdCalculator.RoundDay;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Brings the stored OD of one student on one date in line with {@link OdCalculator}: grants what
 * is newly justified, revokes what no longer is, and leaves everything else untouched, so running
 * it again changes nothing. Every grant or revoke bumps the od_record version, writes an
 * od_record_history row and queues OD_GRANTED / OD_REVOKED in the outbox, all in the caller's
 * transaction.
 *
 * <p>The unit is (student, date), not (student, round, date): the OD key has no round, so all
 * rounds that clash with the student that day are decided together.
 */
@Service
public class OdService {

    private static final Logger log = LoggerFactory.getLogger(OdService.class);

    private final ClashEntryRepository clashes;
    private final SessionViewRepository sessions;
    private final AttendanceMarkRepository marks;
    private final OdRecordRepository records;
    private final OdRecordHistoryRepository history;
    private final OutboxService outbox;
    private final OdEventFactory events;
    private final Clock clock;

    public OdService(ClashEntryRepository clashes, SessionViewRepository sessions, AttendanceMarkRepository marks,
                     OdRecordRepository records, OdRecordHistoryRepository history, OutboxService outbox,
                     OdEventFactory events, Clock clock) {
        this.clashes = clashes;
        this.sessions = sessions;
        this.marks = marks;
        this.records = records;
        this.history = history;
        this.outbox = outbox;
        this.events = events;
        this.clock = clock;
    }

    /** Counts of what one recomputation changed. */
    public record Changes(int granted, int revoked) {

        public static final Changes NONE = new Changes(0, 0);

        public Changes plus(Changes o) {
            return new Changes(granted + o.granted, revoked + o.revoked);
        }
    }

    /** After a student's mark in a session changed. Nothing to do until the session is known. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Changes recomputeForMark(long sessionId, String studentId) {
        return sessions.findById(sessionId)
                .map(s -> recompute(studentId, s.getDate()))
                .orElse(Changes.NONE);
    }

    /**
     * After the clash list or the sessions of a round on a date changed: every student who clashes
     * there now, plus everyone holding OD from that round on that date.
     *
     * @param alsoStudents students who clashed before the change (their entries are already gone)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Changes recomputeRoundDate(long roundId, LocalDate date, Set<String> alsoStudents) {
        Set<String> students = new TreeSet<>(alsoStudents);
        students.addAll(clashes.findStudentIds(roundId, date));
        students.addAll(records.findStudentIds(roundId, date, OdRecord.Status.GRANTED));
        Changes total = Changes.NONE;
        for (String studentId : students) {
            total = total.plus(recompute(studentId, date));
        }
        return total;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Changes recompute(String studentId, LocalDate date) {
        Map<Long, RoundDay> rounds = roundDays(studentId, date);
        Map<Key, Grant> desired = OdCalculator.decideDay(rounds.values());
        Map<Key, OdRecord> existing = records.findByStudentIdAndDate(studentId, date).stream()
                .collect(Collectors.toMap(r -> new Key(r.getSectionCode(), r.getSlotStart()), Function.identity()));

        int granted = 0;
        int revoked = 0;
        for (OdRecord r : existing.values()) {
            if (r.getStatus() == OdRecord.Status.GRANTED && !desired.containsKey(key(r))) {
                r.setStatus(OdRecord.Status.REVOKED);
                r.setReason(OdCalculator.revokeReason(r.getRoundId(), r.getSectionCode(), r.getSlotStart(),
                        rounds.get(r.getRoundId())));
                save(r);
                revoked++;
            }
        }
        for (Grant g : desired.values()) {
            OdRecord r = existing.get(g.key());
            if (r == null) {
                r = new OdRecord();
                r.setStudentId(studentId);
                r.setDate(date);
                r.setSectionCode(g.sectionCode());
                r.setSlotStart(g.slotStart());
            } else if (r.getStatus() == OdRecord.Status.GRANTED && sameGrant(r, g)) {
                continue;
            }
            r.setStatus(OdRecord.Status.GRANTED);
            r.setSlotEnd(g.slotEnd());
            r.setCourseName(g.courseName());
            r.setSessionId(g.sessionId());
            r.setRoundId(g.roundId());
            r.setReason(g.reason());
            save(r);
            granted++;
        }
        if (granted + revoked > 0) {
            log.info("OD for {} on {}: {} granted, {} revoked", studentId, date, granted, revoked);
        }
        return new Changes(granted, revoked);
    }

    /**
     * Every round with a stored clash for the student that day. A round missing here has no clash
     * for them, so any OD it used to justify is revoked.
     */
    private Map<Long, RoundDay> roundDays(String studentId, LocalDate date) {
        Map<Long, List<ClashSlot>> slotsByRound = new TreeMap<>();
        for (ClashEntry c : clashes.findByStudentIdAndDate(studentId, date)) {
            slotsByRound.computeIfAbsent(c.getRoundId(), k -> new ArrayList<>())
                    .add(new ClashSlot(c.getSectionCode(), c.getCourseName(), c.getDayOfWeek(),
                            c.getSlotStart(), c.getSlotEnd()));
        }
        Map<Long, RoundDay> out = new TreeMap<>();
        for (Map.Entry<Long, List<ClashSlot>> e : slotsByRound.entrySet()) {
            long roundId = e.getKey();
            List<SessionView> roundSessions = sessions.findByRoundIdAndDate(roundId, date);
            Set<Long> present = roundSessions.isEmpty() ? Set.of()
                    : marks.findLatestByStudent(studentId, roundSessions.stream().map(SessionView::getSessionId).toList())
                    .stream()
                    .filter(AttendanceMark::isPresent)
                    .map(AttendanceMark::getSessionId)
                    .collect(Collectors.toSet());
            List<OdCalculator.Session> list = roundSessions.stream()
                    .map(s -> new OdCalculator.Session(s.getSessionId(), s.getStartTime(), s.getEndTime(),
                            "CANCELLED".equals(s.getStatus())))
                    .toList();
            out.put(roundId, new RoundDay(roundId, date, list, present, e.getValue()));
        }
        return out;
    }

    private void save(OdRecord r) {
        r.setVersion(r.getVersion() + 1);
        r.setUpdatedAt(clock.instant());
        OdRecord saved = records.saveAndFlush(r);
        OdEvent event = events.from(saved);
        history.save(new OdRecordHistory(saved, event.eventId(), clock.instant()));
        outbox.append(Topics.OD_EVENTS, saved.getStudentId(), event.eventType(), event);
    }

    private static Key key(OdRecord r) {
        return new Key(r.getSectionCode(), r.getSlotStart());
    }

    private static boolean sameGrant(OdRecord r, Grant g) {
        return Objects.equals(r.getSlotEnd(), g.slotEnd())
                && Objects.equals(r.getCourseName(), g.courseName())
                && r.getSessionId() == g.sessionId()
                && r.getRoundId() == g.roundId()
                && Objects.equals(r.getReason(), g.reason());
    }
}
