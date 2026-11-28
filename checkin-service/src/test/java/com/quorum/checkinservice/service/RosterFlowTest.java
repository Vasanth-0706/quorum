package com.quorum.checkinservice.service;

import com.quorum.checkinservice.MutableClock;
import com.quorum.checkinservice.TestDatabase;
import com.quorum.checkinservice.config.Topics;
import com.quorum.checkinservice.repository.RosterEntryRepository;
import com.quorum.checkinservice.repository.StudentNameRepository;
import com.quorum.checkinservice.service.InboundEventHandler.Outcome;
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
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Round rosters and names from import-events, and the two read endpoints, on H2. Session 41 belongs
 * to round 7 and runs 09:30-11:30 on 2026-12-07 in C-301; the clock is 09:45 local.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(MutableClock.Config.class)
class RosterFlowTest {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final String TONY = "9900000001";
    static final String STEVE = "9900000002";
    static final String BRUCE = "9900000003";
    static final String PETER = "9900000004";

    @Autowired
    MockMvc mvc;
    @Autowired
    InboundEventHandler handler;
    @Autowired
    MutableClock clock;
    @Autowired
    RosterEntryRepository roster;
    @Autowired
    StudentNameRepository names;

    @BeforeEach
    void setUp(@Autowired JdbcTemplate jdbc) {
        TestDatabase.wipe(jdbc);
        clock.setLocal(LocalDateTime.parse("2026-12-07T09:45"));
        session("SESSION_SCHEDULED", "SCHEDULED");
    }

    private Outcome send(String json) {
        return handler.handle(Topics.IMPORT_EVENTS, JSON.readTree(json));
    }

    private void session(String type, String status) {
        handler.handle(Topics.DRIVE_EVENTS, JSON.readTree("""
                {"eventId":"%s","eventType":"%s","schemaVersion":1,"occurredAt":"2026-11-27T10:00:00.000Z",
                 "driveId":3,"roundId":7,"sessionId":41,"date":"2026-12-07","startTime":"09:30:00",
                 "endTime":"11:30:00","room":"C-301","status":"%s"}""".formatted(UUID.randomUUID(), type, status)));
    }

    /** The exact shape import-service produces since v0.2.1. */
    private static String shortlist(String eventId, String uploadId, long roundId, String studentsJson,
                                    String idsJson) {
        return """
                {"eventId":"%s","eventType":"SHORTLIST_UPLOADED","schemaVersion":1,"occurredAt":"2026-12-07T03:30:00.000Z",
                 "uploadId":"%s","roundId":%d,"studentIds":%s,"students":%s,"uploadedAt":"2026-12-07T09:00:00"}"""
                .formatted(eventId, uploadId, roundId, idsJson, studentsJson);
    }

    private ResultActions manual(String studentId, boolean present) throws Exception {
        return mvc.perform(post("/sessions/41/marks").header("X-User-Id", "coord-1").header("X-User-Role", "COORDINATOR")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"studentId\":\"%s\",\"present\":%s}".formatted(studentId, present)));
    }

    @Test
    void shortlistBuildsTheRosterWithNamesSortedById() throws Exception {
        assertThat(send(shortlist("e-1", "u-1", 7,
                "[{\"studentId\":\"%s\",\"name\":\"Bruce Wayne\"},{\"studentId\":\"%s\",\"name\":null},{\"studentId\":\"%s\",\"name\":\"Tony Stark\"}]"
                        .formatted(BRUCE, STEVE, TONY),
                "[\"%s\",\"%s\",\"%s\"]".formatted(BRUCE, STEVE, TONY)))).isEqualTo(Outcome.APPLIED);

        mvc.perform(get("/rounds/7/roster"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].studentId").value(TONY))
                .andExpect(jsonPath("$[0].name").value("Tony Stark"))
                .andExpect(jsonPath("$[1].studentId").value(STEVE))
                .andExpect(jsonPath("$[1].name").value(nullValue()))
                .andExpect(jsonPath("$[2].name").value("Bruce Wayne"));
        assertThat(names.findById(TONY)).get().extracting(n -> n.getName()).isEqualTo("Tony Stark");
        assertThat(names.findById(STEVE)).isEmpty();
    }

    @Test
    void aNewUploadForTheRoundReplacesItsRoster() {
        send(shortlist("e-1", "u-1", 7, "[]", "[\"%s\",\"%s\"]".formatted(TONY, STEVE)));
        send(shortlist("e-2", "u-2", 7, "[{\"studentId\":\"%s\",\"name\":\"Bruce Wayne\"}]".formatted(BRUCE),
                "[\"%s\",\"%s\"]".formatted(STEVE, BRUCE)));
        send(shortlist("e-3", "u-3", 8, "[]", "[\"%s\"]".formatted(PETER)));

        assertThat(roster.findByRoundIdOrderByStudentIdAsc(7L))
                .extracting(e -> e.getStudentId(), e -> e.getUploadId())
                .containsExactly(org.assertj.core.groups.Tuple.tuple(STEVE, "u-2"),
                        org.assertj.core.groups.Tuple.tuple(BRUCE, "u-2"));
        assertThat(roster.findByRoundIdOrderByStudentIdAsc(8L)).hasSize(1);
    }

    @Test
    void aRedeliveredShortlistChangesNothing() {
        String event = shortlist("e-1", "u-1", 7, "[]", "[\"%s\"]".formatted(TONY));
        assertThat(send(event)).isEqualTo(Outcome.APPLIED);
        send(shortlist("e-2", "u-2", 7, "[]", "[\"%s\"]".formatted(STEVE)));
        assertThat(send(event)).isEqualTo(Outcome.DUPLICATE);
        assertThat(roster.findByRoundIdOrderByStudentIdAsc(7L)).extracting(e -> e.getStudentId()).containsExactly(STEVE);
    }

    @Test
    void anOldShortlistWithoutStudentsStillWorks() throws Exception {
        send("""
                {"eventId":"e-old","eventType":"SHORTLIST_UPLOADED","uploadId":"u-old","roundId":7,
                 "studentIds":["%s"],"uploadedAt":"2026-12-07T09:00:00"}""".formatted(TONY));
        mvc.perform(get("/rounds/7/roster")).andExpect(jsonPath("$[0].studentId").value(TONY))
                .andExpect(jsonPath("$[0].name").value(nullValue()));
    }

    @Test
    void namesComeFromAttendanceToo_theNewestWins_andABlankNameNeverClearsOne() throws Exception {
        send(shortlist("e-1", "u-1", 7, "[{\"studentId\":\"%s\",\"name\":\"Tony Stark\"}]".formatted(TONY),
                "[\"%s\"]".formatted(TONY)));
        send("""
                {"eventId":"a-1","eventType":"ATTENDANCE_UPLOADED","schemaVersion":1,"occurredAt":"2026-12-07T06:18:00.000Z",
                 "uploadId":"ua-1","sessionId":41,"studentIds":["%s","%s"],
                 "students":[{"studentId":"%s","name":"  Anthony   Stark "},{"studentId":"%s","name":"Peter Parker"}],
                 "uploadedAt":"2026-12-07T11:48:00"}""".formatted(TONY, PETER, TONY, PETER));
        send(shortlist("e-2", "u-2", 9, "[{\"studentId\":\"%s\",\"name\":\"   \"}]".formatted(TONY),
                "[\"%s\"]".formatted(TONY)));

        assertThat(names.findById(TONY)).get().extracting(n -> n.getName()).isEqualTo("Anthony Stark");
        assertThat(names.findById(PETER)).get().extracting(n -> n.getName()).isEqualTo("Peter Parker");
        // round 9's roster row has no name of its own, so the stored name is shown
        mvc.perform(get("/rounds/9/roster")).andExpect(jsonPath("$[0].name").value("Anthony Stark"));
    }

    @Test
    void sheetMergesRosterAndMarks_offRosterStudentsLast() throws Exception {
        send(shortlist("e-1", "u-1", 7,
                "[{\"studentId\":\"%s\",\"name\":\"Tony Stark\"},{\"studentId\":\"%s\",\"name\":\"Steve Rogers\"},{\"studentId\":\"%s\",\"name\":\"Bruce Wayne\"}]"
                        .formatted(TONY, STEVE, BRUCE),
                "[\"%s\",\"%s\",\"%s\"]".formatted(TONY, STEVE, BRUCE)));
        send("""
                {"eventId":"a-1","eventType":"ATTENDANCE_UPLOADED","schemaVersion":1,"occurredAt":"2026-12-07T06:18:00.000Z",
                 "uploadId":"ua-1","sessionId":41,"studentIds":["%s","%s"],
                 "students":[{"studentId":"%s","name":"Tony Stark"},{"studentId":"%s","name":"Peter Parker"}],
                 "uploadedAt":"2026-12-07T11:48:00"}""".formatted(TONY, PETER, TONY, PETER));
        manual(STEVE, false).andExpect(status().isCreated());

        mvc.perform(get("/sessions/41/attendance-sheet"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(41))
                .andExpect(jsonPath("$.roundId").value(7))
                .andExpect(jsonPath("$.status").value("SCHEDULED"))
                .andExpect(jsonPath("$.room").value("C-301"))
                .andExpect(jsonPath("$.counts.present").value(2))
                .andExpect(jsonPath("$.counts.absent").value(1))
                .andExpect(jsonPath("$.counts.notMarked").value(1))
                .andExpect(jsonPath("$.counts.total").value(4))
                .andExpect(jsonPath("$.students[0].studentId").value(TONY))
                .andExpect(jsonPath("$.students[0].status").value("PRESENT"))
                .andExpect(jsonPath("$.students[0].source").value("FILE"))
                .andExpect(jsonPath("$.students[0].markedBy").value("import-service"))
                .andExpect(jsonPath("$.students[0].markedAt").isString())
                .andExpect(jsonPath("$.students[1].studentId").value(STEVE))
                .andExpect(jsonPath("$.students[1].status").value("ABSENT"))
                .andExpect(jsonPath("$.students[1].source").value("MANUAL"))
                .andExpect(jsonPath("$.students[1].markedBy").value("coord-1"))
                .andExpect(jsonPath("$.students[2].studentId").value(BRUCE))
                .andExpect(jsonPath("$.students[2].status").value("NOT_MARKED"))
                .andExpect(jsonPath("$.students[2].source").value(nullValue()))
                .andExpect(jsonPath("$.students[2].markedAt").value(nullValue()))
                .andExpect(jsonPath("$.students[2].onShortlist").value(true))
                .andExpect(jsonPath("$.students[3].studentId").value(PETER))
                .andExpect(jsonPath("$.students[3].name").value("Peter Parker"))
                .andExpect(jsonPath("$.students[3].onShortlist").value(false))
                .andExpect(jsonPath("$.students[3].status").value("PRESENT"));
    }

    @Test
    void cancelledSessionSheetKeepsItsMarks() throws Exception {
        send(shortlist("e-1", "u-1", 7, "[]", "[\"%s\",\"%s\"]".formatted(TONY, STEVE)));
        manual(TONY, true).andExpect(status().isCreated());
        session("SESSION_CANCELLED", "CANCELLED");

        mvc.perform(get("/sessions/41/attendance-sheet"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.counts.present").value(1))
                .andExpect(jsonPath("$.counts.notMarked").value(1));
        manual(STEVE, true).andExpect(status().isConflict());
    }

    @Test
    void sheetWithoutARosterIsEmptyAndRosterOfAnUnknownRoundIsAnEmptyList() throws Exception {
        mvc.perform(get("/sessions/41/attendance-sheet"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.students", hasSize(0)))
                .andExpect(jsonPath("$.counts.total").value(0));
        mvc.perform(get("/rounds/99/roster")).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void unknownSessionIs404() throws Exception {
        mvc.perform(get("/sessions/999/attendance-sheet"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SESSION_NOT_FOUND"));
    }

    @Test
    void readsNeedNoHeaders_manualMarksStillDo() throws Exception {
        mvc.perform(get("/rounds/7/roster")).andExpect(status().isOk());
        mvc.perform(get("/sessions/41/attendance-sheet")).andExpect(status().isOk());
        mvc.perform(post("/sessions/41/marks").contentType(MediaType.APPLICATION_JSON)
                .content("{\"studentId\":\"%s\",\"present\":true}".formatted(TONY))).andExpect(status().isUnauthorized());
    }
}
