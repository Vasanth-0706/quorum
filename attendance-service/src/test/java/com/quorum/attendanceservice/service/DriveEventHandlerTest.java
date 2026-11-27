package com.quorum.attendanceservice.service;

import com.quorum.attendanceservice.config.ClockConfig;
import com.quorum.attendanceservice.entity.SessionView;
import com.quorum.attendanceservice.event.InvalidEventException;
import com.quorum.attendanceservice.repository.ProcessedEventRepository;
import com.quorum.attendanceservice.repository.SessionViewRepository;
import com.quorum.attendanceservice.service.DriveEventHandler.Outcome;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real repositories on H2: upsert by sessionId and de-duplication by eventId. */
@DataJpaTest
@Import({DriveEventHandler.class, ClockConfig.class})
class DriveEventHandlerTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    DriveEventHandler handler;
    @Autowired
    SessionViewRepository sessions;
    @Autowired
    ProcessedEventRepository processed;

    private static JsonNode json(String s) {
        return JSON.readTree(s);
    }

    private static JsonNode session(String eventId, String type, String date, String start, String end,
                                    String room, String status) {
        return json("""
                {"eventId":"%s","eventType":"%s","schemaVersion":1,"occurredAt":"2026-11-27T10:00:00.000Z",
                 "driveId":5,"roundId":11,"sessionId":21,"date":"%s","startTime":"%s","endTime":"%s",
                 "room":"%s","status":"%s"}
                """.formatted(eventId, type, date, start, end, room, status));
    }

    @Test
    void scheduledEventInsertsTheSession() {
        Outcome o = handler.handle(session("e-1", "SESSION_SCHEDULED", "2026-12-01", "09:00:00", "10:00:00",
                "Lab 1", "SCHEDULED"));

        assertThat(o).isEqualTo(Outcome.APPLIED);
        SessionView v = sessions.findById(21L).orElseThrow();
        assertThat(v.getRoundId()).isEqualTo(11L);
        assertThat(v.getDriveId()).isEqualTo(5L);
        assertThat(v.getDate()).isEqualTo(LocalDate.of(2026, 12, 1));
        assertThat(v.getStartTime()).isEqualTo(LocalTime.of(9, 0));
        assertThat(v.getEndTime()).isEqualTo(LocalTime.of(10, 0));
        assertThat(v.getRoom()).isEqualTo("Lab 1");
        assertThat(v.getStatus()).isEqualTo("SCHEDULED");
        assertThat(v.getLastEventId()).isEqualTo("e-1");
        assertThat(processed.existsById("e-1")).isTrue();
    }

    @Test
    void updateAndCancelUpsertTheSameRow() {
        handler.handle(session("e-1", "SESSION_SCHEDULED", "2026-12-01", "09:00:00", "10:00:00", "Lab 1", "SCHEDULED"));
        handler.handle(session("e-2", "SESSION_UPDATED", "2026-12-02", "14:00:00", "15:00:00", "Lab 3", "SCHEDULED"));

        SessionView v = sessions.findById(21L).orElseThrow();
        assertThat(sessions.count()).isEqualTo(1);
        assertThat(v.getDate()).isEqualTo(LocalDate.of(2026, 12, 2));
        assertThat(v.getRoom()).isEqualTo("Lab 3");

        handler.handle(session("e-3", "SESSION_CANCELLED", "2026-12-02", "14:00:00", "15:00:00", "Lab 3", "CANCELLED"));
        v = sessions.findById(21L).orElseThrow();
        assertThat(sessions.count()).isEqualTo(1);
        assertThat(v.getStatus()).isEqualTo("CANCELLED");
        assertThat(v.getStartTime()).isEqualTo(LocalTime.of(14, 0));
    }

    @Test
    void redeliveredEventChangesNothing() {
        JsonNode first = session("e-1", "SESSION_SCHEDULED", "2026-12-01", "09:00:00", "10:00:00", "Lab 1", "SCHEDULED");
        handler.handle(first);
        handler.handle(session("e-2", "SESSION_UPDATED", "2026-12-01", "11:00:00", "12:00:00", "Lab 2", "SCHEDULED"));

        assertThat(handler.handle(first)).isEqualTo(Outcome.DUPLICATE);

        SessionView v = sessions.findById(21L).orElseThrow();
        assertThat(v.getStartTime()).isEqualTo(LocalTime.of(11, 0));
        assertThat(v.getRoom()).isEqualTo("Lab 2");
        assertThat(v.getLastEventId()).isEqualTo("e-2");
        assertThat(processed.count()).isEqualTo(2);
    }

    @Test
    void unknownEventTypeIsSkippedAndNotRecorded() {
        assertThat(handler.handle(json("""
                {"eventId":"x-1","eventType":"SOMETHING_NEW","sessionId":21}"""))).isEqualTo(Outcome.IGNORED);
        assertThat(sessions.count()).isZero();
        assertThat(processed.count()).isZero();
    }

    @Test
    void driveAndRoundEventsAreRecordedButStoreNoSession() {
        assertThat(handler.handle(json("""
                {"eventId":"d-1","eventType":"DRIVE_SCHEDULED","schemaVersion":1,"driveId":5,"companyName":"Acme"}""")))
                .isEqualTo(Outcome.APPLIED);
        assertThat(handler.handle(json("""
                {"eventId":"r-1","eventType":"ROUND_CREATED","driveId":5,"roundId":11,"name":"HR","sequence":3}""")))
                .isEqualTo(Outcome.APPLIED);
        assertThat(sessions.count()).isZero();
        assertThat(processed.existsById("d-1")).isTrue();
        assertThat(processed.existsById("r-1")).isTrue();
    }

    @Test
    void unknownFieldsAndMissingOptionalFieldsAreFine() {
        handler.handle(json("""
                {"eventId":"e-9","eventType":"SESSION_SCHEDULED","driveId":"5","roundId":11,"sessionId":21,
                 "date":"2026-12-01","startTime":"09:00","endTime":"10:00","room":"Lab 1","status":"SCHEDULED",
                 "somethingNew":{"nested":true}}"""));
        assertThat(sessions.findById(21L)).get().extracting(SessionView::getDriveId).isEqualTo(5L);
    }

    @Test
    void missingRequiredFieldIsInvalidAndStoresNothing() {
        assertThatThrownBy(() -> handler.handle(json("""
                {"eventId":"e-1","eventType":"SESSION_SCHEDULED","driveId":5,"roundId":11,
                 "date":"2026-12-01","startTime":"09:00","endTime":"10:00","status":"SCHEDULED"}""")))
                .isInstanceOf(InvalidEventException.class)
                .hasMessageContaining("sessionId");
        assertThatThrownBy(() -> handler.handle(json("""
                {"eventType":"SESSION_SCHEDULED","sessionId":21}""")))
                .isInstanceOf(InvalidEventException.class)
                .hasMessageContaining("eventId");
        assertThatThrownBy(() -> handler.handle(session("e-2", "SESSION_SCHEDULED", "2026-13-45", "09:00", "10:00",
                "Lab 1", "SCHEDULED")))
                .isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> handler.handle(json("[1,2,3]"))).isInstanceOf(InvalidEventException.class);
        assertThat(sessions.count()).isZero();
        assertThat(processed.count()).isZero();
    }
}
