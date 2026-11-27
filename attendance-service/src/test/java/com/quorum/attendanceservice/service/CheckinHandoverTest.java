package com.quorum.attendanceservice.service;

import com.quorum.attendanceservice.TestDatabase;
import com.quorum.attendanceservice.config.Topics;
import com.quorum.attendanceservice.entity.AttendanceMark;
import com.quorum.attendanceservice.entity.OdRecord;
import com.quorum.attendanceservice.repository.AttendanceMarkRepository;
import com.quorum.attendanceservice.repository.OdRecordRepository;
import com.quorum.attendanceservice.repository.OutboxEventRepository;
import com.quorum.attendanceservice.service.InboundEventHandler.Outcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The hand-over to checkin-service, with the default settings (file bridge OFF): FILE marks arrive
 * as ATTENDANCE_MARKED on checkin-events, and a mark is counted once per markId even when it comes
 * again under a new eventId, so nothing is counted twice.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:attendance-handover;MODE=MySQL;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
@Import(MutableClock.Config.class)
class CheckinHandoverTest {

    static final String A = "9900000301";
    static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    InboundEventHandler handler;
    @Autowired
    MutableClock clock;
    @Autowired
    AttendanceMarkRepository marks;
    @Autowired
    OdRecordRepository records;
    @Autowired
    OutboxEventRepository outbox;

    @BeforeEach
    void setUp(@Autowired JdbcTemplate jdbc) {
        TestDatabase.wipe(jdbc);
        clock.setDay(LocalDate.of(2026, 12, 7));
        handler.handle(Topics.DRIVE_EVENTS, JSON.readTree("""
                {"eventId":"%s","eventType":"SESSION_SCHEDULED","driveId":3,"roundId":7,"sessionId":41,
                 "date":"2026-12-07","startTime":"09:30:00","endTime":"10:15:00","room":"Hall A","status":"SCHEDULED"}"""
                .formatted(UUID.randomUUID())));
        handler.handle(Topics.CLASH_EVENTS, JSON.readTree("""
                {"eventId":"%s","eventType":"CLASHES_COMPUTED","roundId":7,"date":"2026-12-07",
                 "windowStart":"09:30:00","windowEnd":"10:15:00","semester":"2026-ODD",
                 "clashes":[{"studentId":"%s","sectionCode":"S12","courseName":"Cloud Native","dayOfWeek":"MONDAY",
                             "slotStart":"10:00:00","slotEnd":"10:50:00","room":"C-301"}]}"""
                .formatted(UUID.randomUUID(), A)));
    }

    private Outcome marked(String eventId, String markId, String source, boolean present) {
        return handler.handle(Topics.CHECKIN_EVENTS, JSON.readTree("""
                {"eventId":"%s","eventType":"ATTENDANCE_MARKED","schemaVersion":1,"occurredAt":"2026-12-07T06:18:00.000Z",
                 "sessionId":41,"roundId":7,"studentId":"%s","present":%s,"source":"%s","markedBy":"import-service",
                 "markedAt":"2026-12-07T06:18:00.000Z","markId":"%s"}""".formatted(eventId, A, present, source, markId)));
    }

    @Test
    void fileMarkFromCheckinServiceGrantsOdOnceEvenIfDeliveredTwice() {
        String markId = UUID.randomUUID().toString();
        assertThat(marked("e-1", markId, "FILE", true)).isEqualTo(Outcome.APPLIED);
        assertThat(marked("e-1", markId, "FILE", true)).isEqualTo(Outcome.DUPLICATE);
        // same mark, new eventId: the event is new, the mark is not
        assertThat(marked("e-2", markId, "FILE", true)).isEqualTo(Outcome.APPLIED);

        assertThat(marks.count()).isEqualTo(1);
        AttendanceMark m = marks.findAll().get(0);
        assertThat(m.getSource()).isEqualTo(AttendanceMark.Source.FILE);
        assertThat(records.findAll()).extracting(OdRecord::getSectionCode).containsExactly("S12");
        assertThat(outbox.count()).as("one OD_GRANTED").isEqualTo(1);
    }

    @Test
    void anRfidTapAndAFileMarkForTheSameStudentStillGiveOneOd() {
        marked("e-1", UUID.randomUUID().toString(), "RFID", true);
        marked("e-2", UUID.randomUUID().toString(), "FILE", true);

        assertThat(marks.count()).isEqualTo(2); // two versions, both present
        assertThat(records.count()).isEqualTo(1);
        assertThat(outbox.count()).isEqualTo(1);
    }

    @Test
    void importEventsAreNotReadByDefault() {
        assertThat(handler.handle(Topics.IMPORT_EVENTS, JSON.readTree("""
                {"eventId":"a-1","eventType":"ATTENDANCE_UPLOADED","uploadId":"u-1","sessionId":41,
                 "studentIds":["%s"]}""".formatted(A)))).isEqualTo(Outcome.IGNORED);
        assertThat(marks.count()).isZero();
    }
}
