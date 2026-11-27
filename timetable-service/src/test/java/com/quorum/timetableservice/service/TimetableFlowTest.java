package com.quorum.timetableservice.service;

import com.quorum.timetableservice.config.Topics;
import com.quorum.timetableservice.entity.OutboxEvent;
import com.quorum.timetableservice.event.InvalidEventException;
import com.quorum.timetableservice.repository.EnrolmentRepository;
import com.quorum.timetableservice.repository.OutboxEventRepository;
import com.quorum.timetableservice.repository.SemesterRepository;
import com.quorum.timetableservice.repository.SlotRepository;
import com.quorum.timetableservice.repository.TimetablePartRepository;
import com.quorum.timetableservice.service.InboundEventHandler.Outcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.StreamSupport;

import static com.quorum.timetableservice.service.TimetableFixture.ENROLMENTS_PART_1;
import static com.quorum.timetableservice.service.TimetableFixture.ENROLMENTS_PART_2;
import static com.quorum.timetableservice.service.TimetableFixture.SLOTS_JSON;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The whole service on H2, fed with the same JSON the Kafka listener receives. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TimetableFlowTest {

    private static final long ROUND = 11;

    @Autowired
    InboundEventHandler handler;
    @Autowired
    ObjectMapper json;
    @Autowired
    List<JpaRepository<?, ?>> allRepositories;
    @Autowired
    OutboxEventRepository outbox;
    @Autowired
    SemesterRepository semesters;
    @Autowired
    SlotRepository slots;
    @Autowired
    EnrolmentRepository enrolments;
    @Autowired
    TimetablePartRepository parts;
    @Autowired
    MockMvc mvc;

    @BeforeEach
    void clean() {
        allRepositories.forEach(JpaRepository::deleteAllInBatch);
    }

    // ---------- event builders

    private JsonNode node(String s) {
        return json.readTree(s);
    }

    private Outcome importEvent(String body) {
        return handler.handle(Topics.IMPORT_EVENTS, node(body));
    }

    private Outcome driveEvent(String body) {
        return handler.handle(Topics.DRIVE_EVENTS, node(body));
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }

    private static String timetablePart(String eventId, String uploadId, String semester, int part, int total,
                                        String slotsJson, String enrolmentsJson) {
        return """
                {"eventId":"%s","eventType":"TIMETABLE_UPLOADED","uploadId":"%s","semester":"%s","part":%d,
                 "totalParts":%d,"slots":%s,"enrolments":%s,"uploadedAt":"2026-11-27T10:00:00"}"""
                .formatted(eventId, uploadId, semester, part, total, slotsJson, enrolmentsJson);
    }

    private void fullTimetable(String semester) {
        String upload = id();
        importEvent(timetablePart(id(), upload, semester, 1, 2, SLOTS_JSON, ENROLMENTS_PART_1));
        importEvent(timetablePart(id(), upload, semester, 2, 2, "[]", ENROLMENTS_PART_2));
    }

    private static String shortlist(String eventId, long roundId, String... studentIds) {
        String ids = String.join("\",\"", studentIds);
        return """
                {"eventId":"%s","eventType":"SHORTLIST_UPLOADED","uploadId":"%s","roundId":%d,
                 "studentIds":["%s"],"uploadedAt":"2026-11-27T10:00:00"}""".formatted(eventId, id(), roundId, ids);
    }

    private static String session(String type, long sessionId, String date, String start, String end, String status) {
        return """
                {"eventId":"%s","eventType":"%s","schemaVersion":1,"occurredAt":"2026-11-27T10:00:00.000Z",
                 "driveId":5,"roundId":%d,"sessionId":%d,"date":"%s","startTime":"%s","endTime":"%s",
                 "room":"Hall","status":"%s"}""".formatted(id(), type, ROUND, sessionId, date, start, end, status);
    }

    private List<JsonNode> clashEvents() {
        return outbox.findAll().stream()
                .sorted(Comparator.comparing(OutboxEvent::getId))
                .peek(e -> assertThat(e.getTopic()).isEqualTo("clash-events"))
                .map(e -> {
                    assertThat(e.getAggregateKey()).isEqualTo(String.valueOf(ROUND));
                    return node(e.getPayload());
                })
                .toList();
    }

    private static List<String> students(JsonNode clashEvent) {
        return StreamSupport.stream(clashEvent.get("clashes").spliterator(), false)
                .map(c -> c.get("studentId").asString() + " " + c.get("sectionCode").asString() + " "
                        + c.get("slotStart").asString())
                .toList();
    }

    private void standardScenario() {
        fullTimetable("2026-ODD");
        importEvent(shortlist(id(), ROUND, "9900000101", "9900000102", "9900000103", "9900000104", "9900000105"));
        driveEvent(session("SESSION_SCHEDULED", 1, "2026-12-04", "11:00:00", "12:30:00", "SCHEDULED"));
        driveEvent(session("SESSION_SCHEDULED", 2, "2026-12-05", "09:00:00", "11:00:00", "SCHEDULED"));
    }

    // ---------- reassembly

    @Test
    void partsAreBufferedUntilAllArriveThenReplaceTheSemesterAtOnce() {
        String upload = id();
        assertThat(importEvent(timetablePart(id(), upload, "2026-ODD", 2, 2, "[]", ENROLMENTS_PART_2)))
                .isEqualTo(Outcome.APPLIED);
        assertThat(semesters.count()).isZero();
        assertThat(parts.count()).isEqualTo(1);

        importEvent(timetablePart(id(), upload, "2026-ODD", 1, 2, SLOTS_JSON, ENROLMENTS_PART_1));

        assertThat(semesters.findFirstByActiveTrue()).get().satisfies(s -> {
            assertThat(s.getCode()).isEqualTo("2026-ODD");
            assertThat(s.getUploadId()).isEqualTo(upload);
            assertThat(s.getSlotCount()).isEqualTo(6);
            assertThat(s.getEnrolmentCount()).isEqualTo(5);
        });
        assertThat(slots.count()).isEqualTo(6);
        assertThat(enrolments.count()).isEqualTo(5);
        assertThat(parts.count()).isZero();
    }

    @Test
    void duplicatePartIsIgnored() {
        String upload = id();
        importEvent(timetablePart(id(), upload, "2026-ODD", 1, 2, SLOTS_JSON, ENROLMENTS_PART_1));
        // same uploadId + part, new eventId (a re-send): ignored, not stored twice
        importEvent(timetablePart(id(), upload, "2026-ODD", 1, 2, SLOTS_JSON, ENROLMENTS_PART_1));
        assertThat(parts.count()).isEqualTo(1);

        importEvent(timetablePart(id(), upload, "2026-ODD", 2, 2, "[]", ENROLMENTS_PART_2));
        assertThat(enrolments.count()).isEqualTo(5);
        assertThat(slots.count()).isEqualTo(6);

        // a late copy of a part of an upload that is already applied changes nothing
        importEvent(timetablePart(id(), upload, "2026-ODD", 2, 2, "[]", ENROLMENTS_PART_2));
        assertThat(parts.count()).isZero();
        assertThat(enrolments.count()).isEqualTo(5);
    }

    @Test
    void newUploadReplacesTheSemesterAndTheNewestSemesterIsActive() {
        fullTimetable("2026-ODD");
        importEvent(timetablePart(id(), id(), "2026-ODD", 1, 1, """
                [{"sectionCode":"CS101","courseName":"Algorithms","dayOfWeek":"FRIDAY","startTime":"12:00","endTime":"13:00","room":"A-101"}]""",
                """
                [{"studentId":"9900000101","sectionCode":"CS101"}]"""));
        assertThat(slots.count()).isEqualTo(1);
        assertThat(enrolments.count()).isEqualTo(1);

        fullTimetable("2027-EVEN");
        assertThat(semesters.findAll()).hasSize(2);
        assertThat(semesters.findFirstByActiveTrue()).get().extracting(s -> s.getCode()).isEqualTo("2027-EVEN");
        assertThat(slots.count()).isEqualTo(7); // the old semester keeps its rows, just inactive
    }

    @Test
    void badPartIsInvalidAndStoresNothing() {
        assertThatThrownBy(() -> importEvent(timetablePart(id(), id(), "2026-ODD", 1, 1, "[]", """
                [{"studentId":"12345","sectionCode":"CS101"}]"""))).isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> importEvent(timetablePart(id(), id(), "2026-ODD", 3, 2, "[]", "[]")))
                .isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> importEvent(timetablePart(id(), id(), "2026-ODD", 1, 1, """
                [{"sectionCode":"X","dayOfWeek":"FUNDAY","startTime":"10:00","endTime":"11:00"}]""", "[]")))
                .isInstanceOf(InvalidEventException.class);
        assertThat(parts.count()).isZero();
        assertThat(semesters.count()).isZero();
    }

    // ---------- clash computation and publishing

    @Test
    void publishesOneFullReplacementPerRoundAndDate() {
        standardScenario();

        List<JsonNode> events = clashEvents();
        JsonNode friday = events.stream().filter(e -> e.get("date").asString().equals("2026-12-04")).reduce((a, b) -> b).orElseThrow();
        JsonNode saturday = events.stream().filter(e -> e.get("date").asString().equals("2026-12-05")).reduce((a, b) -> b).orElseThrow();

        assertThat(friday.get("eventType").asString()).isEqualTo("CLASHES_COMPUTED");
        assertThat(friday.get("schemaVersion").asInt()).isEqualTo(1);
        assertThat(friday.get("eventId").asString()).hasSize(36);
        assertThat(friday.get("occurredAt").asString()).endsWith("Z");
        assertThat(friday.get("roundId").asLong()).isEqualTo(ROUND);
        assertThat(friday.get("semester").asString()).isEqualTo("2026-ODD");
        assertThat(friday.get("windowStart").asString()).isEqualTo("11:00:00");
        assertThat(friday.get("windowEnd").asString()).isEqualTo("12:30:00");
        assertThat(students(friday)).containsExactly("9900000101 CS101 12:00:00", "9900000103 CS101 12:00:00");
        JsonNode first = friday.get("clashes").get(0);
        assertThat(first.get("courseName").asString()).isEqualTo("Algorithms");
        assertThat(first.get("dayOfWeek").asString()).isEqualTo("FRIDAY");
        assertThat(first.get("slotEnd").asString()).isEqualTo("13:00:00");
        assertThat(first.get("room").asString()).isEqualTo("A-101");

        assertThat(saturday.get("windowStart").asString()).isEqualTo("09:00:00");
        assertThat(students(saturday)).containsExactly("9900000101 CS101 10:30:00", "9900000102 CS102 08:00:00",
                "9900000103 CS102 08:00:00", "9900000103 CS101 10:30:00");
        assertThat(events).hasSize(2);
    }

    @Test
    void recomputeIsIdempotentAndUnchangedResultsAreNotRepublished() {
        standardScenario();
        assertThat(clashEvents()).hasSize(2);

        // redelivery of the same event: skipped by eventId
        String sl = shortlist(id(), ROUND, "9900000101", "9900000102", "9900000103", "9900000104", "9900000105");
        importEvent(sl);
        assertThat(importEvent(sl)).isEqualTo(Outcome.DUPLICATE);
        // a fresh upload with the same students in another order: recomputed, same hash, nothing new
        importEvent(shortlist(id(), ROUND, "9900000105", "9900000103", "9900000101", "9900000102", "9900000104"));
        // the same timetable uploaded again
        fullTimetable("2026-ODD");
        // cancelling a session keeps it in the window: same result
        driveEvent(session("SESSION_CANCELLED", 2, "2026-12-05", "09:00:00", "11:00:00", "CANCELLED"));

        assertThat(clashEvents()).hasSize(2);
    }

    @Test
    void shortlistChangeRepublishesOnlyTheDatesThatChanged() {
        standardScenario();

        importEvent(shortlist(id(), ROUND, "9900000102", "9900000104"));

        List<JsonNode> events = clashEvents();
        assertThat(events).hasSize(4);
        assertThat(events.get(2).get("date").asString()).isEqualTo("2026-12-04");
        assertThat(students(events.get(2))).isEmpty(); // full replacement: nobody clashes on Friday now
        assertThat(events.get(3).get("date").asString()).isEqualTo("2026-12-05");
        assertThat(students(events.get(3))).containsExactly("9900000102 CS102 08:00:00");
    }

    @Test
    void reschedulingToAnotherDateClearsTheOldDate() {
        standardScenario();

        // Saturday session moves to Monday 08:00-09:00: no Monday clash (CS103 is 11:00-12:00)
        driveEvent(session("SESSION_UPDATED", 2, "2026-12-07", "08:00:00", "09:00:00", "SCHEDULED"));

        List<JsonNode> events = clashEvents();
        assertThat(events).hasSize(3);
        JsonNode cleared = events.get(2);
        assertThat(cleared.get("date").asString()).isEqualTo("2026-12-05");
        assertThat(cleared.get("windowStart").isNull()).isTrue();
        assertThat(students(cleared)).isEmpty();
    }

    @Test
    void secondSessionWidensTheWindow() {
        standardScenario();
        // a later Friday session widens the window to 13:15: CS102 FRI 12:30-13:30 now clashes for 102 and 103
        driveEvent(session("SESSION_SCHEDULED", 3, "2026-12-04", "12:45:00", "13:15:00", "SCHEDULED"));

        List<JsonNode> all = clashEvents();
        JsonNode last = all.get(all.size() - 1);
        assertThat(last.get("windowEnd").asString()).isEqualTo("13:15:00");
        assertThat(students(last)).containsExactly("9900000101 CS101 12:00:00", "9900000102 CS102 12:30:00",
                "9900000103 CS101 12:00:00", "9900000103 CS102 12:30:00");
    }

    @Test
    void timetableArrivingLastStillProducesClashes() {
        importEvent(shortlist(id(), ROUND, "9900000101"));
        driveEvent(session("SESSION_SCHEDULED", 1, "2026-12-04", "11:00:00", "12:30:00", "SCHEDULED"));
        assertThat(clashEvents()).isEmpty();

        fullTimetable("2026-ODD");

        assertThat(clashEvents()).hasSize(1);
        assertThat(students(clashEvents().get(0))).containsExactly("9900000101 CS101 12:00:00");
    }

    @Test
    void otherEventTypesAreIgnored() {
        assertThat(importEvent("""
                {"eventId":"a-1","eventType":"ATTENDANCE_UPLOADED","uploadId":"u","sessionId":1,"studentIds":[]}"""))
                .isEqualTo(Outcome.IGNORED);
        assertThat(driveEvent("""
                {"eventId":"d-1","eventType":"DRIVE_SCHEDULED","driveId":5}""")).isEqualTo(Outcome.IGNORED);
        assertThat(driveEvent("""
                {"eventId":"r-1","eventType":"ROUND_CREATED","driveId":5,"roundId":11,"name":"HR","sequence":3}"""))
                .isEqualTo(Outcome.APPLIED);
    }

    // ---------- REST

    @Test
    void readEndpoints() throws Exception {
        standardScenario();

        mvc.perform(get("/timetable/semesters"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].semester").value("2026-ODD"))
                .andExpect(jsonPath("$[0].active").value(true))
                .andExpect(jsonPath("$[0].slotCount").value(6));

        mvc.perform(get("/timetable/students/9900000103/slots").param("date", "2026-12-05"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dayOfWeek").value("SATURDAY"))
                .andExpect(jsonPath("$.slots.length()").value(2))
                .andExpect(jsonPath("$.slots[0].sectionCode").value("CS102"))
                .andExpect(jsonPath("$.slots[0].startTime").value("08:00:00"));

        mvc.perform(get("/clashes").param("roundId", "11").param("date", "2026-12-04"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].windowStart").value("11:00:00"))
                .andExpect(jsonPath("$[0].clashes.length()").value(2))
                .andExpect(jsonPath("$[0].lastPublished.current").value(true));

        mvc.perform(get("/outbox"))
                .andExpect(jsonPath("$.pending").value(2))
                .andExpect(jsonPath("$.latest[0].eventType").value("CLASHES_COMPUTED"))
                .andExpect(jsonPath("$.latest[0].aggregateKey").value("11"));

        mvc.perform(get("/clashes").param("roundId", "11"))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[1].date").value("2026-12-05"));
    }

    @Test
    void badParametersAre400() throws Exception {
        mvc.perform(get("/timetable/students/12345/slots").param("date", "2026-12-05"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("studentId"));
        mvc.perform(get("/timetable/students/9900000101/slots"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("date"));
        mvc.perform(get("/timetable/students/9900000101/slots").param("date", "05-12-2026"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/clashes"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("roundId"));
    }

    @Test
    void noActiveSemesterMeansNoSlots() throws Exception {
        mvc.perform(get("/timetable/students/9900000101/slots").param("date", "2026-12-04"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.semester").doesNotExist())
                .andExpect(jsonPath("$.slots.length()").value(0));
    }
}
