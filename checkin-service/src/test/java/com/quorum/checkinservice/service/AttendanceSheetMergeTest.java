package com.quorum.checkinservice.service;

import com.quorum.checkinservice.dto.AttendanceSheetResponse;
import com.quorum.checkinservice.dto.AttendanceSheetResponse.Counts;
import com.quorum.checkinservice.dto.AttendanceSheetResponse.Row;
import com.quorum.checkinservice.dto.AttendanceSheetResponse.Status;
import com.quorum.checkinservice.entity.Mark;
import com.quorum.checkinservice.entity.Mark.Source;
import com.quorum.checkinservice.entity.RosterEntry;
import com.quorum.checkinservice.entity.SessionView;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The merge of roster + latest marks + known names, without Spring or a database. */
class AttendanceSheetMergeTest {

    static final Instant T = Instant.parse("2026-11-30T04:10:00Z");

    static SessionView session(String status) {
        SessionView s = new SessionView();
        s.setSessionId(41L);
        s.setRoundId(7L);
        s.setDriveId(3L);
        s.setDate(LocalDate.parse("2026-11-30"));
        s.setStartTime(LocalTime.parse("09:30"));
        s.setEndTime(LocalTime.parse("11:30"));
        s.setRoom("C-301");
        s.setStatus(status);
        return s;
    }

    static RosterEntry onRoster(String id, String name) {
        return new RosterEntry(7L, id, name, "u-1", T);
    }

    static Mark mark(String id, boolean present, Source source, String by) {
        return new Mark("m-" + id, 41L, 7L, id, 1, present, source, by, T, T);
    }

    @Test
    void rosterRowsComeFirstByIdWithTheirLatestMarkOrNotMarked() {
        AttendanceSheetResponse sheet = AttendanceSheetService.merge(session("SCHEDULED"),
                List.of(onRoster("9900000003", "Bruce Wayne"), onRoster("9900000001", "Tony Stark"),
                        onRoster("9900000002", "Steve Rogers")),
                List.of(mark("9900000001", true, Source.FILE, "import-service"),
                        mark("9900000002", false, Source.MANUAL, "coord-1")),
                Map.of());

        assertThat(sheet.students()).extracting(Row::studentId)
                .containsExactly("9900000001", "9900000002", "9900000003");
        assertThat(sheet.students().get(0)).isEqualTo(
                new Row("9900000001", "Tony Stark", true, Status.PRESENT, "FILE", T, "import-service"));
        assertThat(sheet.students().get(1)).isEqualTo(
                new Row("9900000002", "Steve Rogers", true, Status.ABSENT, "MANUAL", T, "coord-1"));
        assertThat(sheet.students().get(2)).isEqualTo(
                new Row("9900000003", "Bruce Wayne", true, Status.NOT_MARKED, null, null, null));
        assertThat(sheet.counts()).isEqualTo(new Counts(1, 1, 1, 3));
        assertThat(sheet.sessionId()).isEqualTo(41);
        assertThat(sheet.roundId()).isEqualTo(7);
        assertThat(sheet.status()).isEqualTo("SCHEDULED");
    }

    @Test
    void markedStudentsNotOnTheRosterFollowAtTheBottom() {
        AttendanceSheetResponse sheet = AttendanceSheetService.merge(session("SCHEDULED"),
                List.of(onRoster("9900000005", null)),
                List.of(mark("9900000009", true, Source.RFID, "R-C301"),
                        mark("9900000001", true, Source.RFID, "R-C301")),
                Map.of("9900000009", "Peter Parker", "9900000005", "Natasha Romanoff"));

        assertThat(sheet.students()).extracting(Row::studentId, Row::onShortlist, Row::name).containsExactly(
                org.assertj.core.groups.Tuple.tuple("9900000005", true, "Natasha Romanoff"),
                org.assertj.core.groups.Tuple.tuple("9900000001", false, null),
                org.assertj.core.groups.Tuple.tuple("9900000009", false, "Peter Parker"));
        assertThat(sheet.counts()).isEqualTo(new Counts(2, 0, 1, 3));
    }

    @Test
    void theRosterNameWinsOverTheStoredName() {
        AttendanceSheetResponse sheet = AttendanceSheetService.merge(session("SCHEDULED"),
                List.of(onRoster("9900000001", "Tony Stark")), List.of(), Map.of("9900000001", "Anthony Stark"));
        assertThat(sheet.students().get(0).name()).isEqualTo("Tony Stark");
    }

    @Test
    void aCancelledSessionKeepsItsMarksAndSaysCancelled() {
        AttendanceSheetResponse sheet = AttendanceSheetService.merge(session("CANCELLED"),
                List.of(onRoster("9900000001", "Tony Stark"), onRoster("9900000002", "Steve Rogers")),
                List.of(mark("9900000001", true, Source.RFID, "R-C301")), Map.of());
        assertThat(sheet.status()).isEqualTo("CANCELLED");
        assertThat(sheet.counts()).isEqualTo(new Counts(1, 0, 1, 2));
        assertThat(sheet.students().get(0).status()).isEqualTo(Status.PRESENT);
    }

    @Test
    void noRosterAndNoMarksGivesAnEmptySheet() {
        AttendanceSheetResponse sheet = AttendanceSheetService.merge(session("SCHEDULED"), List.of(), List.of(),
                Map.of());
        assertThat(sheet.students()).isEmpty();
        assertThat(sheet.counts()).isEqualTo(new Counts(0, 0, 0, 0));
    }

    @Test
    void namesAreTrimmedAndBlankNamesAreNoName() {
        assertThat(StudentList.clean("  Tony   Stark ")).isEqualTo("Tony Stark");
        assertThat(StudentList.clean("   ")).isNull();
        assertThat(StudentList.clean(null)).isNull();
        assertThat(StudentList.clean("x".repeat(200))).hasSize(120);
    }
}
