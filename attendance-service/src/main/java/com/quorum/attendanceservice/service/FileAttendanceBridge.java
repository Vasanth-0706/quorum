package com.quorum.attendanceservice.service;

import com.quorum.attendanceservice.entity.AttendanceMark.Source;
import com.quorum.attendanceservice.event.EventFields;
import com.quorum.attendanceservice.event.InvalidEventException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * TEMPORARY, until checkin-service exists: import-events ATTENDANCE_UPLOADED is applied here
 * directly. Every listed student counts as present (source FILE) in that session; students not
 * listed are left as they are. Switch it off with {@code quorum.file-attendance-bridge.enabled=false}
 * (import-events is then not even subscribed). Once checkin-service exists it turns file uploads
 * into ATTENDANCE_MARKED events with source FILE, and this class can be deleted.
 *
 * <p>markId = {@code FILE-<uploadId>-<studentId>}, so replaying the upload stores nothing twice.
 * markedAt is the time the event is applied, because uploadedAt carries no offset.
 */
@Service
public class FileAttendanceBridge {

    static final String MARKED_BY = "import-service";

    private final MarkService marks;
    private final Clock clock;

    public FileAttendanceBridge(MarkService marks, Clock clock) {
        this.marks = marks;
        this.clock = clock;
    }

    /** @return how many new marks were stored */
    @Transactional(propagation = Propagation.MANDATORY)
    public int onAttendanceUploaded(String eventId, JsonNode e) {
        String uploadId = EventFields.text(e, "uploadId", 64);
        long sessionId = EventFields.id(e, "sessionId");
        Set<String> studentIds = new LinkedHashSet<>();
        for (JsonNode n : EventFields.array(e, "studentIds")) {
            if (!n.isString() && !n.isIntegralNumber()) {
                throw new InvalidEventException("studentIds[] must hold strings");
            }
            studentIds.add(EventFields.studentId(n.asString()));
        }
        Instant now = clock.instant();
        int stored = 0;
        for (String studentId : studentIds) {
            if (marks.record("FILE-" + uploadId + "-" + studentId, sessionId, studentId, true, Source.FILE,
                    MARKED_BY, now, eventId).isPresent()) {
                stored++;
            }
        }
        return stored;
    }
}
