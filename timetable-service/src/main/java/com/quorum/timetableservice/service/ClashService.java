package com.quorum.timetableservice.service;

import com.quorum.timetableservice.config.Topics;
import com.quorum.timetableservice.entity.ClashResult;
import com.quorum.timetableservice.entity.Enrolment;
import com.quorum.timetableservice.entity.Semester;
import com.quorum.timetableservice.entity.Slot;
import com.quorum.timetableservice.event.ClashEventFactory;
import com.quorum.timetableservice.event.ClashesComputedEvent;
import com.quorum.timetableservice.event.EventTypes;
import com.quorum.timetableservice.repository.ClashResultRepository;
import com.quorum.timetableservice.repository.EnrolmentRepository;
import com.quorum.timetableservice.repository.SemesterRepository;
import com.quorum.timetableservice.repository.SessionViewRepository;
import com.quorum.timetableservice.repository.ShortlistEntryRepository;
import com.quorum.timetableservice.repository.SlotRepository;
import com.quorum.timetableservice.service.ClashCalculator.Result;
import com.quorum.timetableservice.service.ClashCalculator.SessionTime;
import com.quorum.timetableservice.service.ClashCalculator.SlotInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Loads the inputs for a roundId + date, runs {@link ClashCalculator} and queues
 * CLASHES_COMPUTED in the outbox, but only when the result differs from the last one
 * queued for that roundId + date (compared by hash).
 */
@Service
public class ClashService {

    public enum Outcome { PUBLISHED, UNCHANGED, NOTHING_TO_PUBLISH }

    public record Computation(long roundId, LocalDate date, String semester, Result result, String hash) {
    }

    private static final Logger log = LoggerFactory.getLogger(ClashService.class);

    private final SessionViewRepository sessions;
    private final ShortlistEntryRepository shortlists;
    private final SemesterRepository semesters;
    private final EnrolmentRepository enrolments;
    private final SlotRepository slots;
    private final ClashResultRepository results;
    private final OutboxService outbox;
    private final ClashEventFactory events;
    private final Clock clock;

    public ClashService(SessionViewRepository sessions, ShortlistEntryRepository shortlists,
                        SemesterRepository semesters, EnrolmentRepository enrolments, SlotRepository slots,
                        ClashResultRepository results, OutboxService outbox, ClashEventFactory events, Clock clock) {
        this.sessions = sessions;
        this.shortlists = shortlists;
        this.semesters = semesters;
        this.enrolments = enrolments;
        this.slots = slots;
        this.results = results;
        this.outbox = outbox;
        this.events = events;
        this.clock = clock;
    }

    /** Current clash list for roundId + date, computed from stored data. Publishes nothing. */
    @Transactional(readOnly = true)
    public Computation compute(long roundId, LocalDate date) {
        List<SessionTime> roundSessions = sessions.findByRoundIdAndDate(roundId, date).stream()
                .map(s -> new SessionTime(s.getStartTime(), s.getEndTime()))
                .toList();
        Optional<Semester> active = semesters.findFirstByActiveTrue();
        String semester = active.map(Semester::getCode).orElse(null);
        List<String> shortlist = shortlists.findStudentIdsByRoundId(roundId);

        Map<String, Set<String>> sectionsByStudent = Map.of();
        Map<String, List<SlotInfo>> slotsBySection = Map.of();
        if (semester != null && !shortlist.isEmpty() && !roundSessions.isEmpty()) {
            sectionsByStudent = enrolments.findBySemesterAndStudentIdIn(semester, shortlist).stream()
                    .collect(Collectors.groupingBy(Enrolment::getStudentId,
                            Collectors.mapping(Enrolment::getSectionCode, Collectors.toCollection(TreeSet::new))));
            Set<String> sections = sectionsByStudent.values().stream()
                    .flatMap(Set::stream).collect(Collectors.toSet());
            if (!sections.isEmpty()) {
                slotsBySection = slots.findBySemesterAndSectionCodeIn(semester, sections).stream()
                        .collect(Collectors.groupingBy(Slot::getSectionCode, Collectors.mapping(
                                s -> new SlotInfo(s.getSectionCode(), s.getCourseName(), s.getDayOfWeek(),
                                        s.getStartTime(), s.getEndTime(), s.getRoom()),
                                Collectors.toList())));
            }
        }
        Result result = ClashCalculator.compute(date, roundSessions, shortlist, sectionsByStudent, slotsBySection);
        return new Computation(roundId, date, semester, result, ClashCalculator.hash(semester, result));
    }

    /**
     * Recompute one roundId + date and queue the event if the result changed.
     * A first result with no clashes is not published: it says nothing new.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Outcome recompute(long roundId, LocalDate date) {
        Computation c = compute(roundId, date);
        Optional<ClashResult> last = results.findByRoundIdAndDate(roundId, date);
        if (last.isPresent() && last.get().getResultHash().equals(c.hash())) {
            return Outcome.UNCHANGED;
        }
        if (last.isEmpty() && c.result().clashes().isEmpty()) {
            return Outcome.NOTHING_TO_PUBLISH;
        }
        ClashesComputedEvent event = events.clashesComputed(roundId, date, c.semester(), c.result());
        outbox.append(Topics.CLASH_EVENTS, String.valueOf(roundId), EventTypes.CLASHES_COMPUTED, event);

        ClashResult row = last.orElseGet(() -> {
            ClashResult r = new ClashResult();
            r.setRoundId(roundId);
            r.setDate(date);
            return r;
        });
        row.setResultHash(c.hash());
        row.setClashCount(c.result().clashes().size());
        row.setLastEventId(event.eventId());
        row.setQueuedAt(clock.instant());
        results.save(row);
        log.info("Queued CLASHES_COMPUTED for round {} on {}: {} clash(es)", roundId, date,
                c.result().clashes().size());
        return Outcome.PUBLISHED;
    }

    /** Every date the round has sessions on, plus dates published before (they may now be empty). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recomputeRound(long roundId) {
        Set<LocalDate> dates = new TreeSet<>(sessions.findDatesByRoundId(roundId));
        dates.addAll(results.findDatesByRoundId(roundId));
        dates.forEach(d -> recompute(roundId, d));
    }

    /** After the active timetable changes. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recomputeAll() {
        Set<Long> rounds = new TreeSet<>(sessions.findRoundIds());
        rounds.addAll(results.findRoundIds());
        rounds.forEach(this::recomputeRound);
    }
}
