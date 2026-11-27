package com.quorum.attendanceservice.service;

import com.quorum.attendanceservice.entity.ProcessedEvent;
import com.quorum.attendanceservice.entity.SessionView;
import com.quorum.attendanceservice.event.DriveEventTypes;
import com.quorum.attendanceservice.event.InvalidEventException;
import com.quorum.attendanceservice.repository.ProcessedEventRepository;
import com.quorum.attendanceservice.repository.SessionViewRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.Set;
import java.util.function.Function;

/**
 * Applies one drive-events message. Idempotent twice over: eventIds already in
 * processed_event are skipped, and session_view is upserted by sessionId.
 * Unknown fields are ignored; schemaVersion and occurredAt are optional.
 */
@Service
public class DriveEventHandler {

    public enum Outcome { APPLIED, DUPLICATE, IGNORED }

    private static final Logger log = LoggerFactory.getLogger(DriveEventHandler.class);
    private static final Set<String> STATUSES = Set.of("SCHEDULED", "CANCELLED");

    private final SessionViewRepository sessions;
    private final ProcessedEventRepository processed;
    private final Clock clock;

    public DriveEventHandler(SessionViewRepository sessions, ProcessedEventRepository processed, Clock clock) {
        this.sessions = sessions;
        this.processed = processed;
        this.clock = clock;
    }

    @Transactional
    public Outcome handle(JsonNode event) {
        if (event == null || !event.isObject()) {
            throw new InvalidEventException("Message is not a JSON object");
        }
        String eventType = text(event, "eventType");
        if (!DriveEventTypes.HANDLED.contains(eventType)) {
            log.debug("Skipping drive-events message of type {}", eventType);
            return Outcome.IGNORED;
        }
        String eventId = text(event, "eventId");
        if (processed.existsById(eventId)) {
            log.info("Duplicate {} {} skipped", eventType, eventId);
            return Outcome.DUPLICATE;
        }

        if (DriveEventTypes.SESSION_TYPES.contains(eventType)) {
            upsertSession(eventId, eventType, event);
        }
        // DRIVE_SCHEDULED and ROUND_CREATED carry nothing this service stores yet.

        processed.save(new ProcessedEvent(eventId, eventType, clock.instant()));
        log.info("Applied {} {}{}", eventType, eventId,
                event.has("sessionId") ? " for session " + event.get("sessionId").asString() : "");
        return Outcome.APPLIED;
    }

    private void upsertSession(String eventId, String eventType, JsonNode e) {
        long sessionId = id(e, "sessionId");
        SessionView view = sessions.findById(sessionId).orElseGet(() -> {
            SessionView v = new SessionView();
            v.setSessionId(sessionId);
            return v;
        });
        view.setRoundId(id(e, "roundId"));
        view.setDriveId(id(e, "driveId"));
        view.setDate(parse(e, "date", LocalDate::parse));
        view.setStartTime(parse(e, "startTime", LocalTime::parse));
        view.setEndTime(parse(e, "endTime", LocalTime::parse));
        view.setRoom(optionalText(e, "room"));
        view.setStatus(status(eventType, e));
        view.setLastEventId(eventId);
        view.setUpdatedAt(clock.instant());
        sessions.save(view);
    }

    private static String status(String eventType, JsonNode e) {
        String status = optionalText(e, "status");
        if (status == null) {
            status = DriveEventTypes.SESSION_CANCELLED.equals(eventType) ? "CANCELLED" : "SCHEDULED";
        }
        if (!STATUSES.contains(status)) {
            throw new InvalidEventException("Unknown session status " + status);
        }
        return status;
    }

    private static String optionalText(JsonNode e, String field) {
        JsonNode n = e.get(field);
        if (n == null || n.isNull()) {
            return null;
        }
        String s = n.asString().trim();
        return s.isEmpty() ? null : s;
    }

    private static String text(JsonNode e, String field) {
        String s = optionalText(e, field);
        if (s == null) {
            throw new InvalidEventException("Missing " + field);
        }
        return s;
    }

    private static long id(JsonNode e, String field) {
        JsonNode n = e.get(field);
        if (n != null && n.isIntegralNumber()) {
            return n.asLong();
        }
        try {
            return Long.parseLong(text(e, field));
        } catch (NumberFormatException ex) {
            throw new InvalidEventException(field + " is not a number", ex);
        }
    }

    private static <T> T parse(JsonNode e, String field, Function<String, T> parser) {
        String s = text(e, field);
        try {
            return parser.apply(s);
        } catch (DateTimeParseException ex) {
            throw new InvalidEventException(field + " is not valid: " + s, ex);
        }
    }
}
