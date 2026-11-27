package com.quorum.timetableservice.event;

import com.quorum.timetableservice.service.ClashCalculator;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/** Builds CLASHES_COMPUTED with the common fields from CLAUDE.md. */
@Component
public class ClashEventFactory {

    private static final DateTimeFormatter OCCURRED_AT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final Clock clock;

    public ClashEventFactory(Clock clock) {
        this.clock = clock;
    }

    public ClashesComputedEvent clashesComputed(long roundId, LocalDate date, String semester,
                                                ClashCalculator.Result result) {
        return new ClashesComputedEvent(
                UUID.randomUUID().toString(),
                EventTypes.CLASHES_COMPUTED,
                EventTypes.SCHEMA_VERSION,
                OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC).format(OCCURRED_AT),
                roundId,
                date.toString(),
                result.window().map(w -> time(w.start())).orElse(null),
                result.window().map(w -> time(w.end())).orElse(null),
                semester,
                result.clashes().stream()
                        .map(c -> new ClashesComputedEvent.Item(c.studentId(), c.sectionCode(), c.courseName(),
                                c.dayOfWeek().name(), time(c.slotStart()), time(c.slotEnd()), c.room()))
                        .toList());
    }

    public static String time(LocalTime t) {
        return t == null ? null : t.format(TIME);
    }
}
