package com.quorum.timetableservice.service;

import com.quorum.timetableservice.config.Topics;
import com.quorum.timetableservice.entity.ProcessedEvent;
import com.quorum.timetableservice.event.EventFields;
import com.quorum.timetableservice.event.EventTypes;
import com.quorum.timetableservice.event.InvalidEventException;
import com.quorum.timetableservice.repository.ProcessedEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.util.Set;

/**
 * Applies one message from import-events or drive-events, then recomputes the clashes it
 * affects, all in one transaction (the CLASHES_COMPUTED outbox rows included).
 * Idempotent: eventIds already in processed_event are skipped; unknown types are ignored.
 * schemaVersion and occurredAt are optional (import-events has neither).
 */
@Service
public class InboundEventHandler {

    public enum Outcome { APPLIED, DUPLICATE, IGNORED }

    private static final Logger log = LoggerFactory.getLogger(InboundEventHandler.class);

    private final ProcessedEventRepository processed;
    private final TimetableIngestService timetables;
    private final ShortlistService shortlists;
    private final SessionViewService sessionViews;
    private final ClashService clashes;
    private final Clock clock;

    public InboundEventHandler(ProcessedEventRepository processed, TimetableIngestService timetables,
                               ShortlistService shortlists, SessionViewService sessionViews, ClashService clashes,
                               Clock clock) {
        this.processed = processed;
        this.timetables = timetables;
        this.shortlists = shortlists;
        this.sessionViews = sessionViews;
        this.clashes = clashes;
        this.clock = clock;
    }

    @Transactional
    public Outcome handle(String topic, JsonNode event) {
        if (event == null || !event.isObject()) {
            throw new InvalidEventException("Message is not a JSON object");
        }
        String eventType = EventFields.text(event, "eventType");
        if (!handled(topic).contains(eventType)) {
            log.debug("Skipping {} message of type {}", topic, eventType);
            return Outcome.IGNORED;
        }
        String eventId = EventFields.text(event, "eventId", 64);
        if (processed.existsById(eventId)) {
            log.info("Duplicate {} {} skipped", eventType, eventId);
            return Outcome.DUPLICATE;
        }

        switch (eventType) {
            case EventTypes.TIMETABLE_UPLOADED -> {
                if (timetables.onPart(event) == TimetableIngestService.Outcome.APPLIED) {
                    clashes.recomputeAll();
                }
            }
            case EventTypes.SHORTLIST_UPLOADED -> clashes.recomputeRound(shortlists.replace(event));
            case EventTypes.ROUND_CREATED -> sessionViews.upsertRound(event);
            default -> clashes.recomputeRound(sessionViews.upsertSession(eventId, eventType, event));
        }

        processed.save(new ProcessedEvent(eventId, eventType, clock.instant()));
        log.info("Applied {} {}", eventType, eventId);
        return Outcome.APPLIED;
    }

    private static Set<String> handled(String topic) {
        return switch (topic) {
            case Topics.IMPORT_EVENTS -> EventTypes.IMPORT_HANDLED;
            case Topics.DRIVE_EVENTS -> EventTypes.DRIVE_HANDLED;
            default -> Set.of();
        };
    }
}
