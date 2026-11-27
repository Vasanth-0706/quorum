package com.quorum.attendanceservice.service;

import com.quorum.attendanceservice.config.Topics;
import com.quorum.attendanceservice.entity.ProcessedEvent;
import com.quorum.attendanceservice.event.EventFields;
import com.quorum.attendanceservice.event.EventTypes;
import com.quorum.attendanceservice.event.InvalidEventException;
import com.quorum.attendanceservice.repository.ProcessedEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.time.Clock;

/**
 * Applies one message from any consumed topic, together with the OD it changes, in ONE
 * transaction (the OD_GRANTED / OD_REVOKED outbox rows included). Idempotent: an eventId already
 * in processed_event is skipped, and an eventType this service does not handle is ignored.
 * schemaVersion and occurredAt are optional (import-events has neither).
 */
@Service
public class InboundEventHandler {

    public enum Outcome { APPLIED, DUPLICATE, IGNORED }

    private static final Logger log = LoggerFactory.getLogger(InboundEventHandler.class);

    private final DriveEventHandler drives;
    private final ClashResultService clashResults;
    private final MarkService marks;
    private final FileAttendanceBridge fileBridge;
    private final ProcessedEventRepository processed;
    private final Clock clock;
    private final boolean fileBridgeEnabled;

    public InboundEventHandler(DriveEventHandler drives, ClashResultService clashResults, MarkService marks,
                               FileAttendanceBridge fileBridge, ProcessedEventRepository processed, Clock clock,
                               @Value("${quorum.file-attendance-bridge.enabled:false}") boolean fileBridgeEnabled) {
        this.drives = drives;
        this.clashResults = clashResults;
        this.marks = marks;
        this.fileBridge = fileBridge;
        this.processed = processed;
        this.clock = clock;
        this.fileBridgeEnabled = fileBridgeEnabled;
    }

    @Transactional
    public Outcome handle(String topic, JsonNode event) {
        if (Topics.DRIVE_EVENTS.equals(topic)) {
            return switch (drives.handle(event)) {
                case APPLIED -> Outcome.APPLIED;
                case DUPLICATE -> Outcome.DUPLICATE;
                case IGNORED -> Outcome.IGNORED;
            };
        }
        if (event == null || !event.isObject()) {
            throw new InvalidEventException("Message is not a JSON object");
        }
        String eventType = EventFields.text(event, "eventType");
        String handled = handledType(topic);
        if (!eventType.equals(handled)) {
            log.debug("Skipping {} message of type {}", topic, eventType);
            return Outcome.IGNORED;
        }
        String eventId = EventFields.text(event, "eventId", 64);
        if (processed.existsById(eventId)) {
            log.info("Duplicate {} {} skipped", eventType, eventId);
            return Outcome.DUPLICATE;
        }

        switch (eventType) {
            case EventTypes.CLASHES_COMPUTED -> clashResults.replace(eventId, event);
            case EventTypes.ATTENDANCE_MARKED -> marks.onAttendanceMarked(eventId, event);
            case EventTypes.ATTENDANCE_UPLOADED -> fileBridge.onAttendanceUploaded(eventId, event);
            default -> throw new IllegalStateException("Unhandled " + eventType);
        }

        processed.save(new ProcessedEvent(eventId, eventType, clock.instant()));
        log.info("Applied {} {}", eventType, eventId);
        return Outcome.APPLIED;
    }

    /** The one eventType this service reads from the topic, or null. */
    private String handledType(String topic) {
        return switch (topic) {
            case Topics.CLASH_EVENTS -> EventTypes.CLASHES_COMPUTED;
            case Topics.CHECKIN_EVENTS -> EventTypes.ATTENDANCE_MARKED;
            // TEMPORARY file-attendance bridge, see FileAttendanceBridge
            case Topics.IMPORT_EVENTS -> fileBridgeEnabled ? EventTypes.ATTENDANCE_UPLOADED : null;
            default -> null;
        };
    }
}
