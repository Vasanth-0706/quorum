package com.quorum.checkinservice.service;

import com.quorum.checkinservice.MutableClock;
import com.quorum.checkinservice.TestDatabase;
import com.quorum.checkinservice.config.Topics;
import com.quorum.checkinservice.entity.Mark;
import com.quorum.checkinservice.entity.OutboxEvent;
import com.quorum.checkinservice.repository.DeviceRepository;
import com.quorum.checkinservice.repository.MarkRepository;
import com.quorum.checkinservice.repository.OutboxEventRepository;
import com.quorum.checkinservice.repository.PendingFileUploadRepository;
import com.quorum.checkinservice.repository.UnknownTapRepository;
import com.quorum.checkinservice.service.InboundEventHandler.Outcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The whole service on H2: events fed straight into the handler, HTTP through MockMvc.
 * The clock is in Asia/Kolkata. Session 41 (round 7) runs 09:30-10:15 local on 2026-12-07 in
 * "Hall A"; session 42 runs 10:15-11:00 in "Lab 2". Reader R-HALL-A is in Hall A.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(MutableClock.Config.class)
class CheckinFlowTest {

    static final String A = "9900000101";
    static final String B = "9900000102";
    static final String CARD_A = "04A31B2C";
    static final String CARD_B = "04A31B2C5D6E80";
    static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    MockMvc mvc;
    @Autowired
    InboundEventHandler handler;
    @Autowired
    MutableClock clock;
    @Autowired
    MarkRepository marks;
    @Autowired
    OutboxEventRepository outbox;
    @Autowired
    UnknownTapRepository unknownTaps;
    @Autowired
    DeviceRepository devices;
    @Autowired
    PendingFileUploadRepository pending;

    private String hallKey;

    @BeforeEach
    void setUp(@Autowired JdbcTemplate jdbc) throws Exception {
        TestDatabase.wipe(jdbc);
        at("09:45");
        session(41, "09:30", "10:15", "Hall A", "SESSION_SCHEDULED", "SCHEDULED");
        session(42, "10:15", "11:00", "Lab 2", "SESSION_SCHEDULED", "SCHEDULED");
        hallKey = registerDevice("R-HALL-A", "Hall A");
        enrol(A, "04:a3:1b:2c").andExpect(status().isCreated());
        enrol(B, CARD_B).andExpect(status().isCreated());
    }

    // ---------- helpers ----------

    private void at(String localTime) {
        clock.setLocal(LocalDateTime.parse("2026-12-07T" + localTime));
    }

    private Outcome send(String topic, String json) {
        return handler.handle(topic, JSON.readTree(json));
    }

    private void session(long id, String start, String end, String room, String type, String status) {
        send(Topics.DRIVE_EVENTS, """
                {"eventId":"%s","eventType":"%s","schemaVersion":1,"occurredAt":"2026-11-27T10:00:00.000Z",
                 "driveId":3,"roundId":7,"sessionId":%d,"date":"2026-12-07","startTime":"%s:00","endTime":"%s:00",
                 "room":"%s","status":"%s"}""".formatted(UUID.randomUUID(), type, id, start, end, room, status));
    }

    private String registerDevice(String id, String room) throws Exception {
        String body = mvc.perform(post("/devices").header("X-User-Id", "dir-1").header("X-User-Role", "DIRECTOR")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceId\":\"%s\",\"room\":\"%s\"}".formatted(id, room)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.apiKey").isString())
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(body).get("apiKey").asString();
    }

    private ResultActions enrol(String studentId, String uid) throws Exception {
        return mvc.perform(post("/cards").header("X-User-Id", "coord-1").header("X-User-Role", "COORDINATOR")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"studentId\":\"%s\",\"cardUid\":\"%s\"}".formatted(studentId, uid)));
    }

    private ResultActions tap(String key, String uid) throws Exception {
        var req = post("/checkins/tap").contentType(MediaType.APPLICATION_JSON)
                .content("{\"cardUid\":\"%s\"}".formatted(uid));
        if (key != null) {
            req.header("X-Device-Key", key);
        }
        return mvc.perform(req);
    }

    private ResultActions manual(long sessionId, String studentId, boolean present, String role) throws Exception {
        return mvc.perform(post("/sessions/" + sessionId + "/marks").header("X-User-Id", "coord-1")
                .header("X-User-Role", role).contentType(MediaType.APPLICATION_JSON)
                .content("{\"studentId\":\"%s\",\"present\":%s}".formatted(studentId, present)));
    }

    private List<JsonNode> published() {
        return outbox.findAll().stream().sorted(Comparator.comparing(OutboxEvent::getId))
                .map(e -> JSON.readTree(e.getPayload())).toList();
    }

    private String upload(long sessionId, String... studentIds) {
        String eventId = UUID.randomUUID().toString();
        send(Topics.IMPORT_EVENTS, """
                {"eventId":"%s","eventType":"ATTENDANCE_UPLOADED","uploadId":"u-%s","sessionId":%d,
                 "studentIds":[%s],"uploadedAt":"2026-12-07T11:48:00"}""".formatted(eventId, eventId, sessionId,
                String.join(",", java.util.Arrays.stream(studentIds).map(s -> "\"" + s + "\"").toList())));
        return eventId;
    }

    // ---------- taps ----------

    @Test
    void tapInTheDeviceRoomDuringTheSessionMarksPresentAndPublishes() throws Exception {
        tap(hallKey, "04a31b2c")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("MARKED"))
                .andExpect(jsonPath("$.studentId").value(A))
                .andExpect(jsonPath("$.sessionId").value(41))
                .andExpect(jsonPath("$.roundId").value(7))
                .andExpect(jsonPath("$.cardUid").value(CARD_A))
                .andExpect(jsonPath("$.markedAt").value("2026-12-07T04:15:00Z"));

        Mark m = marks.findAll().get(0);
        assertThat(m.getSource()).isEqualTo(Mark.Source.RFID);
        assertThat(m.isPresent()).isTrue();
        assertThat(m.getMarkedBy()).isEqualTo("R-HALL-A");
        assertThat(m.getVersion()).isEqualTo(1);

        List<OutboxEvent> rows = outbox.findAll();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getTopic()).isEqualTo(Topics.CHECKIN_EVENTS);
        assertThat(rows.get(0).getAggregateKey()).isEqualTo("41");
        JsonNode e = JSON.readTree(rows.get(0).getPayload());
        assertThat(e.get("eventType").asString()).isEqualTo("ATTENDANCE_MARKED");
        assertThat(e.get("schemaVersion").asInt()).isEqualTo(1);
        assertThat(e.get("eventId").asString()).hasSize(36);
        assertThat(e.get("occurredAt").asString()).isEqualTo("2026-12-07T04:15:00.000Z");
        assertThat(e.get("sessionId").asLong()).isEqualTo(41);
        assertThat(e.get("roundId").asLong()).isEqualTo(7);
        assertThat(e.get("studentId").asString()).isEqualTo(A);
        assertThat(e.get("present").asBoolean()).isTrue();
        assertThat(e.get("source").asString()).isEqualTo("RFID");
        assertThat(e.get("markedBy").asString()).isEqualTo("R-HALL-A");
        assertThat(e.get("markedAt").asString()).isEqualTo("2026-12-07T04:15:00.000Z");
        assertThat(e.get("markId").asString()).isEqualTo(m.getMarkId());
        assertThat(devices.findById("R-HALL-A").orElseThrow().getLastSeenAt()).isNotNull();
    }

    @Test
    void tapMatchesLocalTimeInTheConfiguredZoneAndInclusiveEdges() throws Exception {
        // 10:15 local = 04:45Z: still session 41 (end inclusive), and 41 is the Hall A session
        at("10:15");
        tap(hallKey, CARD_A).andExpect(status().isOk()).andExpect(jsonPath("$.sessionId").value(41));
        // 10:16 local: session 42 is running, but in Lab 2, not in Hall A
        at("10:16");
        tap(hallKey, CARD_B).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NO_ACTIVE_SESSION"))
                // the message names the local time the sessions are scheduled in, not the UTC instant
                .andExpect(jsonPath("$.message").value(
                        "No session is running in room Hall A at 10:16 on 2026-12-07 (Asia/Kolkata)"));
        // 09:29 local: before session 41
        at("09:29");
        tap(hallKey, CARD_B).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NO_ACTIVE_SESSION"));
    }

    @Test
    void readerInAnotherRoomFindsItsOwnSession() throws Exception {
        String labKey = registerDevice("R-LAB-2", "lab 2 "); // room compared without case and spaces
        at("10:30");
        tap(labKey, CARD_A).andExpect(status().isOk()).andExpect(jsonPath("$.sessionId").value(42));
        tap(hallKey, CARD_B).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NO_ACTIVE_SESSION"));
    }

    @Test
    void readerTappedAtIsUsedWithinTheAllowedSkew() throws Exception {
        mvc.perform(post("/checkins/tap").header("X-Device-Key", hallKey).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cardUid\":\"%s\",\"tappedAt\":\"2026-12-07T09:43:00+05:30\"}".formatted(CARD_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.markedAt").value("2026-12-07T04:13:00Z"));
        mvc.perform(post("/checkins/tap").header("X-Device-Key", hallKey).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cardUid\":\"%s\",\"tappedAt\":\"2026-12-07T09:00:00+05:30\"}".formatted(CARD_B)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TAPPED_AT_OUT_OF_RANGE"));
    }

    @Test
    void secondTapOfTheSameStudentIsAlreadyMarkedWithoutANewMark() throws Exception {
        String first = JSON.readTree(tap(hallKey, CARD_A).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("markId").asString();
        at("10:00");
        tap(hallKey, CARD_A)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("ALREADY_MARKED"))
                .andExpect(jsonPath("$.markId").value(first));

        assertThat(marks.count()).isEqualTo(1);
        assertThat(outbox.count()).isEqualTo(1);
    }

    @Test
    void tapAfterAManualAbsentDoesNotUndoIt() throws Exception {
        tap(hallKey, CARD_A).andExpect(status().isOk());
        manual(41, A, false, "COORDINATOR").andExpect(status().isCreated());
        tap(hallKey, CARD_A).andExpect(status().isOk()).andExpect(jsonPath("$.result").value("ALREADY_MARKED"));
        assertThat(marks.findFirstBySessionIdAndStudentIdOrderByVersionDesc(41L, A).orElseThrow().isPresent()).isFalse();
    }

    @Test
    void unknownCardIs404AndIsListedForEnrolment() throws Exception {
        tap(hallKey, "DE:AD:BE:EF").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("UNKNOWN_CARD"));
        at("09:50");
        tap(hallKey, "deadbeef").andExpect(status().isNotFound());

        assertThat(marks.count()).isZero();
        assertThat(outbox.count()).isZero();
        mvc.perform(get("/cards/unknown-taps"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].cardUid").value("DEADBEEF"))
                .andExpect(jsonPath("$[0].tapCount").value(2))
                .andExpect(jsonPath("$[0].lastDeviceId").value("R-HALL-A"))
                .andExpect(jsonPath("$[0].lastRoom").value("Hall A"))
                .andExpect(jsonPath("$[0].lastSeenAt").value("2026-12-07T04:20:00Z"));

        String c = "9900000103";
        enrol(c, "DEADBEEF").andExpect(status().isCreated());
        assertThat(unknownTaps.count()).isZero();
        tap(hallKey, "DEADBEEF").andExpect(status().isOk()).andExpect(jsonPath("$.studentId").value(c));
    }

    @Test
    void unknownCardIsRecordedEvenWithoutARunningSession() throws Exception {
        at("13:00");
        tap(hallKey, "DEADBEEF").andExpect(status().isNotFound());
        assertThat(unknownTaps.findById("DEADBEEF")).isPresent();
    }

    @Test
    void noSessionInTheRoomIs409() throws Exception {
        at("12:00");
        tap(hallKey, CARD_A).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NO_ACTIVE_SESSION"));
        assertThat(marks.count()).isZero();
    }

    @Test
    void cancelledSessionRejectsNewTapsButKeepsOldMarks() throws Exception {
        tap(hallKey, CARD_A).andExpect(status().isOk());
        session(41, "09:30", "10:15", "Hall A", "SESSION_CANCELLED", "CANCELLED");

        tap(hallKey, CARD_B).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SESSION_CANCELLED"));
        tap(hallKey, CARD_A).andExpect(status().isConflict());
        manual(41, B, true, "COORDINATOR").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SESSION_CANCELLED"));

        mvc.perform(get("/sessions/41/marks"))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].studentId").value(A))
                .andExpect(jsonPath("$[0].present").value(true));
        assertThat(outbox.count()).isEqualTo(1);
    }

    // ---------- device keys ----------

    @Test
    void missingOrWrongDeviceKeyIs401() throws Exception {
        tap(null, CARD_A).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_DEVICE_KEY"));
        tap("R-HALL-A.wrong-secret", CARD_A).andExpect(status().isUnauthorized());
        tap("R-NOWHERE." + hallKey.substring(hallKey.indexOf('.') + 1), CARD_A).andExpect(status().isUnauthorized());
        tap("no-dot-at-all", CARD_A).andExpect(status().isUnauthorized());
        // X-User headers do not replace the device key
        mvc.perform(post("/checkins/tap").header("X-User-Id", "dir-1").header("X-User-Role", "DIRECTOR")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"cardUid\":\"04A31B2C\"}"))
                .andExpect(status().isUnauthorized());
        assertThat(marks.count()).isZero();
        assertThat(unknownTaps.count()).isZero();
    }

    @Test
    void inactiveDeviceIs403AndWorksAgainWhenReactivated() throws Exception {
        tap(hallKey, CARD_A).andExpect(status().isOk()); // the key is now cached as verified
        mvc.perform(patch("/devices/R-HALL-A").header("X-User-Id", "dir-1").header("X-User-Role", "DIRECTOR")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));

        tap(hallKey, CARD_B).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("DEVICE_INACTIVE"));

        mvc.perform(patch("/devices/R-HALL-A").header("X-User-Id", "dir-1").header("X-User-Role", "DIRECTOR")
                .contentType(MediaType.APPLICATION_JSON).content("{\"active\":true}")).andExpect(status().isOk());
        tap(hallKey, CARD_B).andExpect(status().isOk());
    }

    @Test
    void rotatedKeyReplacesTheOldOne() throws Exception {
        tap(hallKey, CARD_A).andExpect(status().isOk());
        String body = mvc.perform(post("/devices/R-HALL-A/key").header("X-User-Id", "dir-1")
                        .header("X-User-Role", "DIRECTOR"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String newKey = JSON.readTree(body).get("apiKey").asString();

        tap(hallKey, CARD_B).andExpect(status().isUnauthorized());
        tap(newKey, CARD_B).andExpect(status().isOk());
    }

    @Test
    void onlyADirectorRegistersDevicesAndThePlainKeyIsNeverListed() throws Exception {
        mvc.perform(post("/devices").header("X-User-Id", "coord-1").header("X-User-Role", "COORDINATOR")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"deviceId\":\"R-X\",\"room\":\"Hall A\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/devices").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceId\":\"R-X\",\"room\":\"Hall A\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/devices").header("X-User-Id", "dir-1").header("X-User-Role", "DIRECTOR")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"deviceId\":\"R-HALL-A\",\"room\":\"Hall A\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DEVICE_EXISTS"));
        mvc.perform(post("/devices").header("X-User-Id", "dir-1").header("X-User-Role", "DIRECTOR")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"deviceId\":\"bad.id\",\"room\":\"Hall A\"}"))
                .andExpect(status().isBadRequest());

        String list = mvc.perform(get("/devices")).andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].deviceId").value("R-HALL-A"))
                .andReturn().getResponse().getContentAsString();
        assertThat(list).doesNotContain(hallKey.substring(hallKey.indexOf('.') + 1)).doesNotContain("apiKey");
        assertThat(devices.findById("R-HALL-A").orElseThrow().getApiKeyHash()).startsWith("$2");
    }

    @Test
    void tapAnswersWellWithin300Milliseconds() throws Exception {
        tap(hallKey, CARD_A).andExpect(status().isOk()); // first tap pays for BCrypt once
        long worst = 0;
        for (int i = 0; i < 20; i++) {
            long t = System.nanoTime();
            tap(hallKey, i % 2 == 0 ? CARD_A : CARD_B).andExpect(status().isOk());
            worst = Math.max(worst, (System.nanoTime() - t) / 1_000_000);
        }
        assertThat(worst).isLessThan(300);
    }

    // ---------- manual marks ----------

    @Test
    void manualMarksAreVersionedAndPublished() throws Exception {
        manual(41, A, true, "COORDINATOR").andExpect(status().isCreated())
                .andExpect(jsonPath("$.source").value("MANUAL"))
                .andExpect(jsonPath("$.markedBy").value("coord-1"))
                .andExpect(jsonPath("$.version").value(1));
        manual(41, A, true, "DIRECTOR").andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
        manual(41, A, false, "DIRECTOR").andExpect(status().isCreated()).andExpect(jsonPath("$.version").value(2));

        mvc.perform(get("/sessions/41/marks")).andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].present").value(false));
        mvc.perform(get("/sessions/41/marks?history=true")).andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].version").value(2))
                .andExpect(jsonPath("$[1].version").value(1));
        assertThat(published()).extracting(e -> e.get("present").asBoolean()).containsExactly(true, false);
        assertThat(published()).allSatisfy(e -> assertThat(e.get("source").asString()).isEqualTo("MANUAL"));
    }

    @Test
    void manualMarkNeedsHeadersAKnownSessionAndAValidStudent() throws Exception {
        mvc.perform(post("/sessions/41/marks").contentType(MediaType.APPLICATION_JSON)
                .content("{\"studentId\":\"" + A + "\",\"present\":true}")).andExpect(status().isUnauthorized());
        manual(41, A, true, "STUDENT").andExpect(status().isForbidden());
        manual(99, A, true, "COORDINATOR").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SESSION_NOT_FOUND"));
        manual(41, "12345", true, "COORDINATOR").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("studentId"));
        assertThat(marks.count()).isZero();
    }

    // ---------- FILE marks from import-events ----------

    @Test
    void attendanceUploadCreatesFileMarksOnlyWhereSomethingChanges() throws Exception {
        tap(hallKey, CARD_A).andExpect(status().isOk());
        at("11:48");
        String eventId = upload(41, A, B);

        List<Mark> file = marks.findAll().stream().filter(m -> m.getSource() == Mark.Source.FILE).toList();
        assertThat(file).hasSize(1);
        assertThat(file.get(0).getStudentId()).isEqualTo(B);
        assertThat(file.get(0).getMarkedBy()).isEqualTo("import-service");
        assertThat(file.get(0).getRoundId()).isEqualTo(7);
        assertThat(UUID.fromString(file.get(0).getMarkId())).isNotNull();

        JsonNode last = published().get(published().size() - 1);
        assertThat(last.get("source").asString()).isEqualTo("FILE");
        assertThat(last.get("studentId").asString()).isEqualTo(B);
        assertThat(last.get("present").asBoolean()).isTrue();
        assertThat(outbox.count()).isEqualTo(2);

        // redelivery of the same event changes nothing
        assertThat(handler.handle(Topics.IMPORT_EVENTS, JSON.readTree("""
                {"eventId":"%s","eventType":"ATTENDANCE_UPLOADED","uploadId":"u-%s","sessionId":41,
                 "studentIds":["%s"]}""".formatted(eventId, eventId, B)))).isEqualTo(Outcome.DUPLICATE);
        assertThat(marks.count()).isEqualTo(2);
    }

    @Test
    void uploadForAnUnknownSessionWaitsForTheSession() throws Exception {
        upload(77, A);
        assertThat(marks.count()).isZero();
        assertThat(pending.count()).isEqualTo(1);

        session(77, "14:00", "15:00", "Hall B", "SESSION_SCHEDULED", "SCHEDULED");

        assertThat(pending.count()).isZero();
        Mark m = marks.findAll().get(0);
        assertThat(m.getSessionId()).isEqualTo(77);
        assertThat(m.getSource()).isEqualTo(Mark.Source.FILE);
        assertThat(outbox.count()).isEqualTo(1);
    }

    @Test
    void uploadForACancelledSessionAddsNothing() {
        session(41, "09:30", "10:15", "Hall A", "SESSION_CANCELLED", "CANCELLED");
        upload(41, A);
        assertThat(marks.count()).isZero();
    }

    @Test
    void otherImportEventsAndDriveEventsAreIgnored() {
        assertThat(send(Topics.IMPORT_EVENTS, """
                {"eventId":"t-1","eventType":"TIMETABLE_UPLOADED","uploadId":"u","slots":[],"enrolments":[]}"""))
                .isEqualTo(Outcome.IGNORED);
        assertThat(send(Topics.DRIVE_EVENTS, """
                {"eventId":"r-1","eventType":"ROUND_CREATED","driveId":3,"roundId":8,"name":"HR","sequence":2}"""))
                .isEqualTo(Outcome.IGNORED);
    }

    // ---------- cards ----------

    @Test
    void cardInUseByAnotherStudentIs409AndReEnrolIsIdempotent() throws Exception {
        enrol(A, CARD_A).andExpect(status().isOk());
        enrol(B, CARD_A).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CARD_IN_USE"));
        enrol("123", CARD_A).andExpect(status().isBadRequest());
        enrol(A, "XYZ").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_CARD_UID"));
    }

    @Test
    void csvImportIsAllOrNothing() throws Exception {
        tap(hallKey, "11223344").andExpect(status().isNotFound());
        MockMultipartFile good = new MockMultipartFile("file", "cards.csv", "text/csv", """
                studentId,cardUid
                9900000103,11:22:33:44
                9900000104,AABBCCDD
                9900000101,04A31B2C
                """.getBytes());
        mvc.perform(multipart("/cards/import").file(good).header("X-User-Id", "coord-1")
                        .header("X-User-Role", "COORDINATOR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(2))
                .andExpect(jsonPath("$.unchanged").value(1))
                .andExpect(jsonPath("$.total").value(3));
        assertThat(unknownTaps.count()).isZero();

        MockMultipartFile bad = new MockMultipartFile("file", "cards.csv", "text/csv", """
                9900000105,55667788
                12345,99AABBCC
                9900000106,04A31B2C
                9900000107
                """.getBytes());
        mvc.perform(multipart("/cards/import").file(bad).header("X-User-Id", "coord-1")
                        .header("X-User-Role", "COORDINATOR"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CSV_INVALID"))
                .andExpect(jsonPath("$.errors", hasSize(3)))
                .andExpect(jsonPath("$.errors[0].field").value("line 2"));
        mvc.perform(get("/cards?studentId=9900000105")).andExpect(jsonPath("$", hasSize(0)));
    }
}
