package com.quorum.checkinservice.service;

import com.quorum.checkinservice.config.Topics;
import com.quorum.checkinservice.entity.ProcessedEvent;
import com.quorum.checkinservice.entity.SessionView;
import com.quorum.checkinservice.event.EventFields;
import com.quorum.checkinservice.event.EventTypes;
import com.quorum.checkinservice.event.InvalidEventException;
import com.quorum.checkinservice.repository.ProcessedEventRepository;
import com.quorum.checkinservice.repository.SessionViewRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.util.Map;
import java.util.Set;

/**
 * Applies one message from drive-events or import-events in ONE transaction (marks and their
 * outbox rows included). Handled: SESSION_SCHEDULED / _UPDATED / _CANCELLED (drive-events),
 * ATTENDANCE_UPLOADED (FILE marks, plus names) and SHORTLIST_UPLOADED (round roster, plus names). Idempotent: an eventId already in processed_event is skipped, and an
 * eventType this service does not handle is ignored. schemaVersion and occurredAt are optional
 * (import-events has neither). Unknown fields are ignored.
 */
@Service
public class InboundEventHandler {

    public enum Outcome { APPLIED, DUPLICATE, IGNORED }

    private static final Logger log = LoggerFactory.getLogger(InboundEventHandler.class);
    private static final Set<String> STATUSES = Set.of(SessionMatcher.SCHEDULED, SessionMatcher.CANCELLED);

    private final SessionViewRepository sessions;
    private final FileMarkService fileMarks;
    private final RosterService rosters;
    private final ProcessedEventRepository processed;
    private final Clock clock;

    public InboundEventHandler(SessionViewRepository sessions, FileMarkService fileMarks, RosterService rosters,
                               ProcessedEventRepository processed, Clock clock) {
        this.sessions = sessions;
        this.fileMarks = fileMarks;
        this.rosters = rosters;
        this.processed = processed;
        this.clock = clock;
    }

    @Transactional
    public Outcome handle(String topic, JsonNode event) {
        if (event == null || !event.isObject()) {
            throw new InvalidEventException("Message is not a JSON object");
        }
        String eventType = EventFields.text(event, "eventType");
        boolean handled = switch (topic) {
            case Topics.DRIVE_EVENTS -> EventTypes.SESSION_TYPES.contains(eventType);
            case Topics.IMPORT_EVENTS -> EventTypes.IMPORT_TYPES.contains(eventType);
            default -> false;
        };
        if (!handled) {
            log.debug("Skipping {} message of type {}", topic, eventType);
            return Outcome.IGNORED;
        }
        String eventId = EventFields.text(event, "eventId", 64);
        if (processed.existsById(eventId)) {
            log.info("Duplicate {} {} skipped", eventType, eventId);
            return Outcome.DUPLICATE;
        }

        if (Topics.DRIVE_EVENTS.equals(topic)) {
            upsertSession(eventId, eventType, event);
        } else if (EventTypes.SHORTLIST_UPLOADED.equals(eventType)) {
            rosters.replace(event);
        } else {
            onAttendanceUploaded(eventId, event);
        }

        processed.save(new ProcessedEvent(eventId, eventType, clock.instant()));
        log.info("Applied {} {}", eventType, eventId);
        return Outcome.APPLIED;
    }

    private void upsertSession(String eventId, String eventType, JsonNode e) {
        long sessionId = EventFields.id(e, "sessionId");
        SessionView view = sessions.findById(sessionId).orElseGet(() -> {
            SessionView v = new SessionView();
            v.setSessionId(sessionId);
            return v;
        });
        view.setRoundId(EventFields.id(e, "roundId"));
        view.setDriveId(EventFields.id(e, "driveId"));
        view.setDate(EventFields.date(e, "date"));
        view.setStartTime(EventFields.time(e, "startTime"));
        view.setEndTime(EventFields.time(e, "endTime"));
        view.setRoom(EventFields.optionalText(e, "room", 60));
        view.setStatus(status(eventType, e));
        view.setLastEventId(eventId);
        view.setUpdatedAt(clock.instant());
        sessions.saveAndFlush(view);
        fileMarks.applyPending(view);
    }

    private void onAttendanceUploaded(String eventId, JsonNode e) {
        String uploadId = EventFields.text(e, "uploadId", 64);
        long sessionId = EventFields.id(e, "sessionId");
        Map<String, String> students = StudentList.read(e);
        Set<String> studentIds = students.keySet();
        rosters.remember(students);
        sessions.findById(sessionId).ifPresentOrElse(
                session -> fileMarks.apply(uploadId, session, studentIds),
                () -> fileMarks.park(eventId, uploadId, sessionId, studentIds));
    }

    private static String status(String eventType, JsonNode e) {
        String status = EventFields.optionalText(e, "status");
        if (status == null) {
            status = EventTypes.SESSION_CANCELLED.equals(eventType) ? SessionMatcher.CANCELLED : SessionMatcher.SCHEDULED;
        }
        if (EventTypes.SESSION_CANCELLED.equals(eventType)) {
            status = SessionMatcher.CANCELLED;
        }
        if (!STATUSES.contains(status)) {
            throw new InvalidEventException("Unknown session status " + status);
        }
        return status;
    }
}
