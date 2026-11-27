package com.quorum.attendanceservice.service;

import com.quorum.attendanceservice.TestDatabase;
import com.quorum.attendanceservice.config.Topics;
import com.quorum.attendanceservice.entity.OdRecord;
import com.quorum.attendanceservice.entity.OdRecord.Status;
import com.quorum.attendanceservice.entity.OdRecordHistory;
import com.quorum.attendanceservice.entity.OutboxEvent;
import com.quorum.attendanceservice.repository.AttendanceMarkRepository;
import com.quorum.attendanceservice.repository.ClashEntryRepository;
import com.quorum.attendanceservice.repository.ClashResultRepository;
import com.quorum.attendanceservice.repository.CorrectionRepository;
import com.quorum.attendanceservice.repository.OdRecordHistoryRepository;
import com.quorum.attendanceservice.repository.OdRecordRepository;
import com.quorum.attendanceservice.repository.OutboxEventRepository;
import com.quorum.attendanceservice.repository.ProcessedEventRepository;
import com.quorum.attendanceservice.repository.SessionViewRepository;
import com.quorum.attendanceservice.service.InboundEventHandler.Outcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The whole service on H2, events fed straight into the handler. 2026-12-07 is a MONDAY.
 * Round 7 that day: session 41 09:30-10:15 and session 42 10:15-11:00, so the clash window is
 * [09:30, 11:00]. Student A clashes with S12 10:00-10:50, S13 10:30-11:20 and S14 11:00-11:50
 * (S14 is in the clash list only to test the edge: it touches the OD window and gets nothing).
 * Student B clashes with S12.
 */
@SpringBootTest(properties = "quorum.file-attendance-bridge.enabled=true") // also covers the opt-in bridge
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(MutableClock.Config.class)
class OdFlowTest {

    static final String A = "9900000101";
    static final String B = "9900000102";
    static final LocalDate MONDAY = LocalDate.of(2026, 12, 7);
    static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    InboundEventHandler handler;
    @Autowired
    MockMvc mvc;
    @Autowired
    MutableClock clock;
    @Autowired
    OdRecordRepository records;
    @Autowired
    OdRecordHistoryRepository history;
    @Autowired
    OutboxEventRepository outbox;
    @Autowired
    AttendanceMarkRepository marks;
    @Autowired
    CorrectionRepository corrections;
    @Autowired
    ClashEntryRepository clashEntries;
    @Autowired
    ClashResultRepository clashResults;
    @Autowired
    SessionViewRepository sessions;
    @Autowired
    ProcessedEventRepository processed;

    /** Every event sent in this test, to replay them. */
    private final List<String[]> sent = new ArrayList<>();

    @BeforeEach
    void clean(@Autowired JdbcTemplate jdbc) {
        TestDatabase.wipe(jdbc);
        clock.setDay(MONDAY);
    }

    // ---------- event builders ----------

    private Outcome send(String topic, String json) {
        sent.add(new String[]{topic, json});
        return handler.handle(topic, JSON.readTree(json));
    }

    private void session(long sessionId, String start, String end, String type, String status) {
        session(sessionId, MONDAY, start, end, type, status);
    }

    private void session(long sessionId, LocalDate date, String start, String end, String type, String status) {
        send(Topics.DRIVE_EVENTS, """
                {"eventId":"%s","eventType":"%s","schemaVersion":1,"occurredAt":"2026-11-27T10:00:00.000Z",
                 "driveId":3,"roundId":7,"sessionId":%d,"date":"%s","startTime":"%s:00","endTime":"%s:00",
                 "room":"Hall","status":"%s"}""".formatted(UUID.randomUUID(), type, sessionId, date, start, end,
                status));
    }

    private void twoSessions() {
        session(41, "09:30", "10:15", "SESSION_SCHEDULED", "SCHEDULED");
        session(42, "10:15", "11:00", "SESSION_SCHEDULED", "SCHEDULED");
    }

    private static String clash(String student, String section, String start, String end) {
        return """
                {"studentId":"%s","sectionCode":"%s","courseName":"Course %s","dayOfWeek":"MONDAY",
                 "slotStart":"%s:00","slotEnd":"%s:00","room":"C-301"}""".formatted(student, section, section, start, end);
    }

    private static final List<String> FULL_CLASHES = List.of(
            clash(A, "S12", "10:00", "10:50"),
            clash(A, "S13", "10:30", "11:20"),
            clash(A, "S14", "11:00", "11:50"),
            clash(B, "S12", "10:00", "10:50"));

    private void clashes(List<String> items) {
        String window = items.isEmpty() ? "null" : "\"09:30:00\"";
        String windowEnd = items.isEmpty() ? "null" : "\"11:00:00\"";
        send(Topics.CLASH_EVENTS, """
                {"eventId":"%s","eventType":"CLASHES_COMPUTED","schemaVersion":1,"occurredAt":"2026-11-27T10:00:00.000Z",
                 "roundId":7,"date":"2026-12-07","windowStart":%s,"windowEnd":%s,"semester":"2026-ODD",
                 "clashes":[%s]}""".formatted(UUID.randomUUID(), window, windowEnd, String.join(",", items)));
    }

    private void mark(long sessionId, String student, boolean present) {
        send(Topics.CHECKIN_EVENTS, """
                {"eventId":"%s","eventType":"ATTENDANCE_MARKED","schemaVersion":1,"occurredAt":"2026-12-07T04:00:00.000Z",
                 "sessionId":%d,"roundId":7,"studentId":"%s","present":%s,"source":"RFID","markedBy":"reader-1",
                 "markedAt":"2026-12-07T04:00:00Z","markId":"%s"}""".formatted(UUID.randomUUID(), sessionId, student,
                present, UUID.randomUUID()));
    }

    private void upload(String uploadId, long sessionId, String... students) {
        send(Topics.IMPORT_EVENTS, """
                {"eventId":"%s","eventType":"ATTENDANCE_UPLOADED","uploadId":"%s","sessionId":%d,
                 "studentIds":["%s"],"uploadedAt":"2026-12-07T11:48:00"}""".formatted(UUID.randomUUID(), uploadId,
                sessionId, String.join("\",\"", students)));
    }

    // ---------- readers ----------

    private List<OdRecord> od(String student, Status status) {
        return records.findByStudentIdAndDate(student, MONDAY).stream()
                .filter(r -> r.getStatus() == status)
                .sorted(Comparator.comparing(OdRecord::getSlotStart))
                .toList();
    }

    private static List<String> sections(List<OdRecord> rows) {
        return rows.stream().map(OdRecord::getSectionCode).toList();
    }

    private List<JsonNode> published() {
        return outbox.findAll().stream()
                .sorted(Comparator.comparing(OutboxEvent::getId))
                .map(e -> JSON.readTree(e.getPayload()))
                .toList();
    }

    private ResultActions correct(String role, long sessionId, String student, boolean present) throws Exception {
        return mvc.perform(post("/corrections")
                .header("X-User-Id", "user-1").header("X-User-Role", role)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"sessionId":%d,"studentId":"%s","present":%s,"reason":"Scanner missed the card"}"""
                        .formatted(sessionId, student, present)));
    }

    // ---------- the decision ----------

    @Test
    void presentStudentGetsOdOnlyForOverlappingSlotsAndItIsPublished() {
        twoSessions();
        clashes(FULL_CLASHES);
        mark(41, A, true);

        // window [09:30, 10:15]: S12 only
        assertThat(sections(od(A, Status.GRANTED))).containsExactly("S12");
        OdRecord r = od(A, Status.GRANTED).get(0);
        assertThat(r.getSlotEnd()).isEqualTo(LocalTime.of(10, 50));
        assertThat(r.getSessionId()).isEqualTo(41L);
        assertThat(r.getVersion()).isEqualTo(1);

        List<OutboxEvent> rows = outbox.findAll();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getTopic()).isEqualTo(Topics.OD_EVENTS);
        assertThat(rows.get(0).getAggregateKey()).isEqualTo(A);
        JsonNode e = JSON.readTree(rows.get(0).getPayload());
        assertThat(e.get("eventType").asString()).isEqualTo("OD_GRANTED");
        assertThat(e.get("schemaVersion").asInt()).isEqualTo(1);
        assertThat(e.get("occurredAt").asString()).isEqualTo("2026-12-07T12:00:00.000Z");
        assertThat(e.get("studentId").asString()).isEqualTo(A);
        assertThat(e.get("date").asString()).isEqualTo("2026-12-07");
        assertThat(e.get("sectionCode").asString()).isEqualTo("S12");
        assertThat(e.get("courseName").asString()).isEqualTo("Course S12");
        assertThat(e.get("slotStart").asString()).isEqualTo("10:00:00");
        assertThat(e.get("slotEnd").asString()).isEqualTo("10:50:00");
        assertThat(e.get("sessionId").asLong()).isEqualTo(41);
        assertThat(e.get("reason").asString()).isEqualTo("Attended session 41 (09:30-10:15) of placement round 7");

        List<OdRecordHistory> h = history.findByOdRecordIdOrderByVersionAsc(r.getId());
        assertThat(h).hasSize(1);
        assertThat(h.get(0).getStatus()).isEqualTo(Status.GRANTED);
        assertThat(h.get(0).getEventId()).isEqualTo(e.get("eventId").asString());
    }

    @Test
    void absentStudentGetsNothing() {
        twoSessions();
        clashes(FULL_CLASHES);
        mark(41, B, false);
        mark(42, B, false);

        assertThat(records.count()).isZero();
        assertThat(outbox.count()).isZero();
    }

    @Test
    void waitingTimeWhenTheStudentAttendedTheSecondSessionButNotTheTouchingSlot() {
        twoSessions();
        clashes(FULL_CLASHES);
        mark(42, A, true);

        // window [09:30 (start of session 41), 11:00 (end of 42)]; S14 starts at 11:00 and only touches it
        assertThat(sections(od(A, Status.GRANTED))).containsExactly("S12", "S13");
        assertThat(od(A, Status.GRANTED)).allSatisfy(r -> assertThat(r.getSessionId()).isEqualTo(42L));
    }

    @Test
    void noStoredClashResultMeansNoOdUntilOneArrives() {
        twoSessions();
        mark(41, A, true);
        assertThat(records.count()).isZero();

        clashes(FULL_CLASHES);
        assertThat(sections(od(A, Status.GRANTED))).containsExactly("S12");
    }

    @Test
    void markBeforeTheSessionIsKnownCountsOnceTheSessionArrives() {
        clashes(FULL_CLASHES);
        mark(41, A, true);
        assertThat(records.count()).isZero();

        twoSessions();
        assertThat(sections(od(A, Status.GRANTED))).containsExactly("S12");
    }

    @Test
    void emptyReplacementRevokesEarlierOd() {
        twoSessions();
        clashes(FULL_CLASHES);
        mark(42, A, true);
        mark(41, B, true);
        assertThat(records.count()).isEqualTo(3);

        clashes(List.of());

        assertThat(od(A, Status.GRANTED)).isEmpty();
        assertThat(od(B, Status.GRANTED)).isEmpty();
        assertThat(sections(od(A, Status.REVOKED))).containsExactly("S12", "S13");
        OdRecord revoked = od(B, Status.REVOKED).get(0);
        assertThat(revoked.getVersion()).isEqualTo(2);
        assertThat(revoked.getReason()).isEqualTo("Slot is no longer in the clash list of round 7");
        assertThat(history.findByOdRecordIdOrderByVersionAsc(revoked.getId()))
                .extracting(OdRecordHistory::getStatus).containsExactly(Status.GRANTED, Status.REVOKED);
        assertThat(published()).extracting(e -> e.get("eventType").asString())
                .containsExactly("OD_GRANTED", "OD_GRANTED", "OD_GRANTED", "OD_REVOKED", "OD_REVOKED", "OD_REVOKED");
        assertThat(clashEntries.count()).isZero();
        assertThat(clashResults.findByRoundIdAndDate(7L, MONDAY)).get()
                .satisfies(c -> assertThat(c.getWindowStart()).isNull());
    }

    @Test
    void changedClashListRevokesOnlyTheSlotThatLeftIt() {
        twoSessions();
        clashes(FULL_CLASHES);
        mark(42, A, true);

        clashes(List.of(clash(A, "S13", "10:30", "11:20"), clash(B, "S12", "10:00", "10:50")));

        assertThat(sections(od(A, Status.GRANTED))).containsExactly("S13");
        assertThat(sections(od(A, Status.REVOKED))).containsExactly("S12");
        // S13 was untouched: still version 1, one history row
        assertThat(od(A, Status.GRANTED).get(0).getVersion()).isEqualTo(1);
    }

    @Test
    void cancelledSessionKeepsItsMarksAndOd() {
        twoSessions();
        clashes(FULL_CLASHES);
        mark(41, A, true);
        long before = outbox.count();

        session(41, "09:30", "10:15", "SESSION_CANCELLED", "CANCELLED");

        assertThat(sections(od(A, Status.GRANTED))).containsExactly("S12");
        assertThat(outbox.count()).isEqualTo(before);
    }

    @Test
    void sessionMovedToAnotherDayRevokesTheOldDaysOd() {
        twoSessions();
        clashes(FULL_CLASHES);
        mark(42, A, true);
        assertThat(sections(od(A, Status.GRANTED))).containsExactly("S12", "S13");

        // 42 moves to Tuesday; only 41 is left on Monday, and A has no mark in it
        session(42, MONDAY.plusDays(1), "10:15", "11:00", "SESSION_UPDATED", "SCHEDULED");

        assertThat(od(A, Status.GRANTED)).isEmpty();
        assertThat(od(A, Status.REVOKED)).allSatisfy(r ->
                assertThat(r.getReason()).isEqualTo("No longer marked present in any session of round 7"));
    }

    @Test
    void replayingEveryEventTwiceChangesNothing() {
        twoSessions();
        clashes(FULL_CLASHES);
        mark(42, A, true);
        upload("u-1", 41, B, A);
        clashes(List.of(clash(A, "S13", "10:30", "11:20"), clash(B, "S12", "10:00", "10:50")));
        long[] before = {records.count(), history.count(), outbox.count(), marks.count(), clashEntries.count(),
                processed.count()};

        for (String[] event : List.copyOf(sent)) {
            assertThat(handler.handle(event[0], JSON.readTree(event[1]))).isEqualTo(Outcome.DUPLICATE);
        }
        // the same upload again under a new eventId: markIds repeat, so no new mark
        upload("u-1", 41, B, A);

        assertThat(new long[]{records.count(), history.count(), outbox.count(), marks.count(), clashEntries.count(),
                processed.count() - 1}).containsExactly(before);
    }

    @Test
    void recomputingWithoutAChangeIsANoOp(@Autowired OdService od, @Autowired WriteLock lock,
                                         @Autowired org.springframework.transaction.support.TransactionTemplate tx) {
        twoSessions();
        clashes(FULL_CLASHES);
        mark(42, A, true);
        long before = outbox.count();

        OdService.Changes changes = tx.execute(s -> od.recompute(A, MONDAY));

        assertThat(changes).isEqualTo(OdService.Changes.NONE);
        assertThat(outbox.count()).isEqualTo(before);
    }

    // ---------- the TEMPORARY file bridge ----------

    @Test
    void uploadedAttendanceCountsAsPresentFromFile() throws Exception {
        twoSessions();
        clashes(FULL_CLASHES);
        upload("u-9", 41, A, B);

        assertThat(sections(od(A, Status.GRANTED))).containsExactly("S12");
        assertThat(sections(od(B, Status.GRANTED))).containsExactly("S12");
        mvc.perform(get("/marks/sessions/41"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].studentId").value(A))
                .andExpect(jsonPath("$[0].source").value("FILE"))
                .andExpect(jsonPath("$[0].markId").value("FILE-u-9-" + A))
                .andExpect(jsonPath("$[0].present").value(true));
    }

    @Test
    void otherImportEventsAreIgnored() {
        assertThat(send(Topics.IMPORT_EVENTS, """
                {"eventId":"t-1","eventType":"TIMETABLE_UPLOADED","uploadId":"u","semester":"2026-ODD"}"""))
                .isEqualTo(Outcome.IGNORED);
        assertThat(send(Topics.CLASH_EVENTS, """
                {"eventId":"x-1","eventType":"SOMETHING_ELSE"}""")).isEqualTo(Outcome.IGNORED);
        assertThat(processed.count()).isZero();
    }

    // ---------- corrections ----------

    @Test
    void correctionToAbsentRevokesOdAndKeepsTheOldValue() throws Exception {
        twoSessions();
        clashes(FULL_CLASHES);
        mark(41, A, true);
        assertThat(sections(od(A, Status.GRANTED))).containsExactly("S12");

        correct("COORDINATOR", 41, A, false)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.oldPresent").value(true))
                .andExpect(jsonPath("$.newPresent").value(false))
                .andExpect(jsonPath("$.correctedBy").value("user-1"))
                .andExpect(jsonPath("$.role").value("COORDINATOR"))
                .andExpect(jsonPath("$.reason").value("Scanner missed the card"))
                .andExpect(jsonPath("$.markVersion").value(2));

        assertThat(od(A, Status.GRANTED)).isEmpty();
        assertThat(od(A, Status.REVOKED).get(0).getReason())
                .isEqualTo("No longer marked present in any session of round 7");
        assertThat(published()).extracting(e -> e.get("eventType").asString())
                .containsExactly("OD_GRANTED", "OD_REVOKED");

        mvc.perform(get("/marks/sessions/41?history=true"))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].present").value(true))
                .andExpect(jsonPath("$[0].source").value("RFID"))
                .andExpect(jsonPath("$[1].present").value(false))
                .andExpect(jsonPath("$[1].source").value("CORRECTION"));
        mvc.perform(get("/marks/sessions/41"))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].version").value(2));
        mvc.perform(get("/corrections").param("sessionId", "41"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].studentId").value(A));

        // and back to present: granted again, same record, version 3
        correct("DIRECTOR", 41, A, true).andExpect(status().isCreated()).andExpect(jsonPath("$.oldPresent").value(false));
        assertThat(od(A, Status.GRANTED)).singleElement().satisfies(r -> assertThat(r.getVersion()).isEqualTo(3));
    }

    @Test
    void correctionOfAStudentWithNoMarkHasNoOldValue() throws Exception {
        twoSessions();
        clashes(FULL_CLASHES);

        correct("COORDINATOR", 42, B, true)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.oldPresent").isEmpty())
                .andExpect(jsonPath("$.markVersion").value(1));
        assertThat(sections(od(B, Status.GRANTED))).containsExactly("S12");
    }

    @Test
    void coordinatorMayCorrectUpToDay14AndDirectorAnyTime() throws Exception {
        twoSessions();

        clock.setDay(MONDAY.plusDays(14));
        correct("COORDINATOR", 41, A, true).andExpect(status().isCreated());

        clock.setDay(MONDAY.plusDays(15));
        correct("COORDINATOR", 41, A, false)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(containsString("up to 14 days after the session date")))
                .andExpect(jsonPath("$.message").value(containsString("last day 2026-12-21")));

        clock.setDay(MONDAY.plusDays(30));
        correct("DIRECTOR", 41, A, false).andExpect(status().isCreated());

        assertThat(corrections.count()).isEqualTo(2);
        assertThat(marks.count()).isEqualTo(2);
    }

    @Test
    void correctionRequestsAreChecked() throws Exception {
        twoSessions();
        String body = """
                {"sessionId":41,"studentId":"%s","present":true,"reason":"x"}""".formatted(A);

        mvc.perform(post("/corrections").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/corrections").header("X-User-Id", "u").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/corrections").header("X-User-Id", "u").header("X-User-Role", "STUDENT")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(post("/corrections").header("X-User-Id", "u").header("X-User-Role", "COORDINATOR")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sessionId":41,"studentId":"123","present":null,"reason":"  "}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field").value(org.hamcrest.Matchers.contains("present", "reason", "studentId")));
        correct("COORDINATOR", 999, A, true)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Session 999 is not known to attendance-service"));
        assertThat(marks.count()).isZero();
    }

    // ---------- read API ----------

    @Test
    void readEndpointsAndCsvExport() throws Exception {
        twoSessions();
        clashes(FULL_CLASHES);
        mark(42, A, true);
        mark(41, B, true);

        mvc.perform(get("/od").param("date", "2026-12-07"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].studentId").value(A))
                .andExpect(jsonPath("$[0].sectionCode").value("S12"))
                .andExpect(jsonPath("$[0].slotStart").value("10:00:00"))
                .andExpect(jsonPath("$[0].status").value("GRANTED"))
                .andExpect(jsonPath("$[2].studentId").value(B));
        mvc.perform(get("/od").param("date", "2026-12-08")).andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(get("/od/students/" + A).param("from", "2026-12-01").param("to", "2026-12-31"))
                .andExpect(jsonPath("$", hasSize(2)));
        mvc.perform(get("/od/students/" + A).param("from", "2026-12-08")).andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(get("/od/students/" + A).param("from", "2026-12-31").param("to", "2026-12-01"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/od/students/12345")).andExpect(status().isBadRequest());
        mvc.perform(get("/od/sessions/41"))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].studentId").value(B));
        mvc.perform(get("/od")).andExpect(status().isBadRequest());

        mvc.perform(get("/od/export").param("date", "2026-12-07"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"od-2026-12-07.csv\""))
                .andExpect(content().string("""
                        studentId,date,course,section,slotStart,slotEnd,sessionId,reason\r
                        9900000101,2026-12-07,Course S12,S12,10:00,10:50,42,Attended session 42 (10:15-11:00) of placement round 7\r
                        9900000101,2026-12-07,Course S13,S13,10:30,11:20,42,Attended session 42 (10:15-11:00) of placement round 7\r
                        9900000102,2026-12-07,Course S12,S12,10:00,10:50,41,Attended session 41 (09:30-10:15) of placement round 7\r
                        """));

        clashes(List.of());
        mvc.perform(get("/od").param("date", "2026-12-07")).andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(get("/od").param("date", "2026-12-07").param("includeRevoked", "true"))
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].status").value("REVOKED"));
    }

    @Test
    void csvCellsAreQuotedAndFormulasDefused() {
        assertThat(OdQueryService.cell("plain")).isEqualTo("plain");
        assertThat(OdQueryService.cell("a,b")).isEqualTo("\"a,b\"");
        assertThat(OdQueryService.cell("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
        assertThat(OdQueryService.cell("=SUM(A1)")).isEqualTo("'=SUM(A1)");
        assertThat(OdQueryService.cell(null)).isEmpty();
    }
}
