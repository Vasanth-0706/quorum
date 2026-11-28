package com.quorum.checkinservice.service;

import com.quorum.checkinservice.dto.AttendanceSheetResponse;
import com.quorum.checkinservice.dto.AttendanceSheetResponse.Counts;
import com.quorum.checkinservice.dto.AttendanceSheetResponse.Row;
import com.quorum.checkinservice.dto.AttendanceSheetResponse.Status;
import com.quorum.checkinservice.entity.Mark;
import com.quorum.checkinservice.entity.RosterEntry;
import com.quorum.checkinservice.entity.SessionView;
import com.quorum.checkinservice.repository.SessionViewRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Builds the attendance sheet of a session. Read-only: marking still goes through POST .../marks. */
@Service
public class AttendanceSheetService {

    private final SessionViewRepository sessions;
    private final RosterService rosters;
    private final MarkService marks;

    public AttendanceSheetService(SessionViewRepository sessions, RosterService rosters, MarkService marks) {
        this.sessions = sessions;
        this.rosters = rosters;
        this.marks = marks;
    }

    @Transactional(readOnly = true)
    public AttendanceSheetResponse sheet(long sessionId) {
        SessionView session = sessions.findById(sessionId).orElseThrow(() -> ApiException.notFound(
                "SESSION_NOT_FOUND", "Session " + sessionId + " is not known (not received from drive-events yet)"));
        List<RosterEntry> roster = rosters.entries(session.getRoundId());
        List<Mark> latest = marks.ofSession(sessionId, false);

        Set<String> everyone = new HashSet<>();
        roster.forEach(r -> everyone.add(r.getStudentId()));
        latest.forEach(m -> everyone.add(m.getStudentId()));
        return merge(session, roster, latest, rosters.namesOf(everyone));
    }

    /**
     * Pure merge: roster rows first (by studentId) with their latest mark or NOT_MARKED, then the
     * marked students who are not on the roster (by studentId, onShortlist=false). A name comes from
     * the roster row, else from student_name, else null. A cancelled session keeps its marks.
     */
    static AttendanceSheetResponse merge(SessionView session, Collection<RosterEntry> roster,
                                         Collection<Mark> latestMarks, Map<String, String> knownNames) {
        Map<String, Mark> markByStudent = new LinkedHashMap<>();
        latestMarks.forEach(m -> markByStudent.put(m.getStudentId(), m));

        Map<String, RosterEntry> onRoster = new TreeMap<>();
        roster.forEach(r -> onRoster.putIfAbsent(r.getStudentId(), r));

        List<Row> rows = new ArrayList<>();
        onRoster.forEach((id, r) -> rows.add(row(id,
                r.getStudentName() != null ? r.getStudentName() : knownNames.get(id), true, markByStudent.get(id))));
        new TreeMap<>(markByStudent).forEach((id, m) -> {
            if (!onRoster.containsKey(id)) {
                rows.add(row(id, knownNames.get(id), false, m));
            }
        });

        int present = 0;
        int absent = 0;
        for (Row r : rows) {
            if (r.status() == Status.PRESENT) {
                present++;
            } else if (r.status() == Status.ABSENT) {
                absent++;
            }
        }
        Counts counts = new Counts(present, absent, rows.size() - present - absent, rows.size());
        return new AttendanceSheetResponse(session.getSessionId(), session.getRoundId(), session.getDriveId(),
                session.getDate(), session.getStartTime(), session.getEndTime(), session.getRoom(),
                session.getStatus(), counts, List.copyOf(rows));
    }

    private static Row row(String studentId, String name, boolean onShortlist, Mark mark) {
        if (mark == null) {
            return new Row(studentId, name, onShortlist, Status.NOT_MARKED, null, null, null);
        }
        return new Row(studentId, name, onShortlist, mark.isPresent() ? Status.PRESENT : Status.ABSENT,
                mark.getSource().name(), mark.getMarkedAt(), mark.getMarkedBy());
    }
}
