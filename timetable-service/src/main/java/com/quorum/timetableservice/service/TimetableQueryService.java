package com.quorum.timetableservice.service;

import com.quorum.timetableservice.dto.ClashResponse;
import com.quorum.timetableservice.dto.SemesterResponse;
import com.quorum.timetableservice.dto.SlotResponse;
import com.quorum.timetableservice.dto.StudentSlotsResponse;
import com.quorum.timetableservice.entity.Semester;
import com.quorum.timetableservice.event.ClashEventFactory;
import com.quorum.timetableservice.event.ClashesComputedEvent;
import com.quorum.timetableservice.repository.ClashResultRepository;
import com.quorum.timetableservice.repository.EnrolmentRepository;
import com.quorum.timetableservice.repository.SemesterRepository;
import com.quorum.timetableservice.repository.SessionViewRepository;
import com.quorum.timetableservice.repository.SlotRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

@Service
@Transactional(readOnly = true)
public class TimetableQueryService {

    private final SemesterRepository semesters;
    private final EnrolmentRepository enrolments;
    private final SlotRepository slots;
    private final SessionViewRepository sessions;
    private final ClashResultRepository results;
    private final ClashService clashService;

    public TimetableQueryService(SemesterRepository semesters, EnrolmentRepository enrolments, SlotRepository slots,
                                 SessionViewRepository sessions, ClashResultRepository results,
                                 ClashService clashService) {
        this.semesters = semesters;
        this.enrolments = enrolments;
        this.slots = slots;
        this.sessions = sessions;
        this.results = results;
        this.clashService = clashService;
    }

    /** Active first, then most recently applied. */
    public List<SemesterResponse> semesters() {
        return semesters.findAllByOrderByActiveDescAppliedAtDesc().stream().map(SemesterResponse::from).toList();
    }

    public StudentSlotsResponse studentSlots(String studentId, LocalDate date) {
        Optional<Semester> active = semesters.findFirstByActiveTrue();
        if (active.isEmpty()) {
            return new StudentSlotsResponse(studentId, date, date.getDayOfWeek(), null, List.of());
        }
        String semester = active.get().getCode();
        List<String> sections = enrolments.findSectionCodes(semester, studentId);
        List<SlotResponse> daySlots = sections.isEmpty() ? List.of()
                : slots.findBySemesterAndSectionCodeInAndDayOfWeekOrderByStartTimeAscSectionCodeAsc(
                        semester, sections, date.getDayOfWeek()).stream().map(SlotResponse::from).toList();
        return new StudentSlotsResponse(studentId, date, date.getDayOfWeek(), semester, daySlots);
    }

    /** One entry for the date given, or one per date the round has sessions or results on. */
    public List<ClashResponse> clashes(long roundId, LocalDate date) {
        Set<LocalDate> dates = new TreeSet<>();
        if (date != null) {
            dates.add(date);
        } else {
            dates.addAll(sessions.findDatesByRoundId(roundId));
            dates.addAll(results.findDatesByRoundId(roundId));
        }
        return dates.stream().map(d -> clash(roundId, d)).toList();
    }

    private ClashResponse clash(long roundId, LocalDate date) {
        ClashService.Computation c = clashService.compute(roundId, date);
        List<ClashesComputedEvent.Item> items = c.result().clashes().stream()
                .map(x -> new ClashesComputedEvent.Item(x.studentId(), x.sectionCode(), x.courseName(),
                        x.dayOfWeek().name(), ClashEventFactory.time(x.slotStart()), ClashEventFactory.time(x.slotEnd()),
                        x.room()))
                .toList();
        return new ClashResponse(roundId, date, c.semester(),
                c.result().window().map(w -> ClashEventFactory.time(w.start())).orElse(null),
                c.result().window().map(w -> ClashEventFactory.time(w.end())).orElse(null),
                items,
                results.findByRoundIdAndDate(roundId, date)
                        .map(r -> ClashResponse.LastPublished.from(r, c.hash()))
                        .orElse(null));
    }
}
