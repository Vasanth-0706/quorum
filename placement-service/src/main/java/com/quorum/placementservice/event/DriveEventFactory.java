package com.quorum.placementservice.event;

import com.quorum.placementservice.entity.Drive;
import com.quorum.placementservice.entity.Round;
import com.quorum.placementservice.entity.Session;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/** Builds drive-events messages that follow the contract in CLAUDE.md. */
@Component
public class DriveEventFactory {

    private static final DateTimeFormatter OCCURRED_AT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final Clock clock;

    public DriveEventFactory(Clock clock) {
        this.clock = clock;
    }

    public DriveScheduledEvent driveScheduled(Drive d) {
        return new DriveScheduledEvent(newId(), EventTypes.DRIVE_SCHEDULED, EventTypes.SCHEMA_VERSION, now(),
                d.getId(), d.getCompanyName(), d.getDriveDate().toString(),
                time(d.getStartTime()), time(d.getEndTime()), d.getVenue());
    }

    public RoundCreatedEvent roundCreated(Round r) {
        return new RoundCreatedEvent(newId(), EventTypes.ROUND_CREATED, EventTypes.SCHEMA_VERSION, now(),
                r.getDrive().getId(), r.getId(), r.getName(), r.getSequence());
    }

    /** eventType is SESSION_SCHEDULED, SESSION_UPDATED or SESSION_CANCELLED. */
    public SessionEvent session(String eventType, Session s) {
        Round r = s.getRound();
        return new SessionEvent(newId(), eventType, EventTypes.SCHEMA_VERSION, now(),
                r.getDrive().getId(), r.getId(), s.getId(), s.getDate().toString(),
                time(s.getStartTime()), time(s.getEndTime()), s.getRoom(), s.getStatus().name());
    }

    private String now() {
        return OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC).format(OCCURRED_AT);
    }

    private static String newId() {
        return UUID.randomUUID().toString();
    }

    private static String time(LocalTime t) {
        return t.format(TIME);
    }
}
