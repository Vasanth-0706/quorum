package com.quorum.attendanceservice.event;

import com.quorum.attendanceservice.entity.OdRecord;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/** Builds OD_GRANTED / OD_REVOKED from an od_record, with the common fields from CLAUDE.md. */
@Component
public class OdEventFactory {

    private static final DateTimeFormatter OCCURRED_AT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final Clock clock;

    public OdEventFactory(Clock clock) {
        this.clock = clock;
    }

    public OdEvent from(OdRecord r) {
        return new OdEvent(
                UUID.randomUUID().toString(),
                r.getStatus() == OdRecord.Status.GRANTED ? EventTypes.OD_GRANTED : EventTypes.OD_REVOKED,
                EventTypes.SCHEMA_VERSION,
                OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC).format(OCCURRED_AT),
                r.getStudentId(),
                r.getDate().toString(),
                r.getSectionCode(),
                r.getCourseName(),
                time(r.getSlotStart()),
                time(r.getSlotEnd()),
                r.getSessionId(),
                r.getReason());
    }

    public static String time(LocalTime t) {
        return t == null ? null : t.format(TIME);
    }
}
