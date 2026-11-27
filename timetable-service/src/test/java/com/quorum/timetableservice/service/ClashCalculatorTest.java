package com.quorum.timetableservice.service;

import com.quorum.timetableservice.service.ClashCalculator.Result;
import com.quorum.timetableservice.service.ClashCalculator.SessionTime;
import com.quorum.timetableservice.service.ClashCalculator.Window;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static com.quorum.timetableservice.service.TimetableFixture.*;
import static org.assertj.core.api.Assertions.assertThat;

/** The hand-checked oracle: every expected list below was worked out on paper from TimetableFixture. */
class ClashCalculatorTest {

    private static Result run(java.time.LocalDate date, List<SessionTime> sessions, List<String> shortlist) {
        return ClashCalculator.compute(date, sessions, shortlist, SECTIONS_BY_STUDENT, SLOTS_BY_SECTION);
    }

    @Test
    void fridayWindowElevenToHalfTwelve() {
        Result r = run(FRIDAY, FRIDAY_SESSIONS, SHORTLIST);

        assertThat(r.window()).contains(new Window(t("11:00"), t("12:30")));
        // A  FRI 10:00-11:00 ends exactly at window start 11:00  -> no clash (touching edge)
        // B  FRI 12:00-13:00 overlaps [11:00,12:30]              -> clash for 101 and 103
        // D  FRI 12:30-13:30 starts exactly at window end 12:30  -> no clash (touching edge)
        // F  MON slot on a Friday                                -> no clash (104)
        // 105 has no enrolments                                  -> nothing
        assertThat(r.clashes()).containsExactly(
                clash(S101, "CS101", "Algorithms", DayOfWeek.FRIDAY, "12:00", "13:00", "A-101"),
                clash(S103, "CS101", "Algorithms", DayOfWeek.FRIDAY, "12:00", "13:00", "A-101"));
    }

    @Test
    void saturdayIsTheNextDayOfTheDriveAndHasItsOwnWindow() {
        Result r = run(SATURDAY, SATURDAY_SESSIONS, SHORTLIST);

        assertThat(r.window()).contains(new Window(t("09:00"), t("11:00")));
        // C  SAT 10:30-11:30 overlaps [09:00,11:00]  -> 101 and 103
        // E  SAT 08:00-09:30 overlaps [09:00,11:00]  -> 102 and 103
        // 103 is in two sections and clashes with both, ordered by slot start
        assertThat(r.clashes()).containsExactly(
                clash(S101, "CS101", "Algorithms", DayOfWeek.SATURDAY, "10:30", "11:30", "Lab-1"),
                clash(S102, "CS102", "Networks", DayOfWeek.SATURDAY, "08:00", "09:30", "B-202"),
                clash(S103, "CS102", "Networks", DayOfWeek.SATURDAY, "08:00", "09:30", "B-202"),
                clash(S103, "CS101", "Algorithms", DayOfWeek.SATURDAY, "10:30", "11:30", "Lab-1"));
    }

    @Test
    void windowSpansEarliestStartToLatestEndOfAllSessionsThatDay() {
        // Two Friday sessions with a gap: window is [09:30, 12:15] and includes slot A (10:00-11:00)
        List<SessionTime> sessions = List.of(new SessionTime(t("11:45"), t("12:15")), new SessionTime(t("09:30"), t("10:15")));
        Result r = run(FRIDAY, sessions, List.of(S101));

        assertThat(r.window()).contains(new Window(t("09:30"), t("12:15")));
        assertThat(r.clashes()).containsExactly(
                clash(S101, "CS101", "Algorithms", DayOfWeek.FRIDAY, "10:00", "11:00", "A-101"),
                clash(S101, "CS101", "Algorithms", DayOfWeek.FRIDAY, "12:00", "13:00", "A-101"));
    }

    @Test
    void onlyShortlistedStudentsCount() {
        assertThat(run(FRIDAY, FRIDAY_SESSIONS, List.of(S103)).clashes())
                .extracting(ClashCalculator.Clash::studentId).containsExactly(S103);
        assertThat(run(FRIDAY, FRIDAY_SESSIONS, List.of()).clashes()).isEmpty();
    }

    @Test
    void studentWithNoEnrolmentsOrUnknownToTheTimetableHasNoClashes() {
        assertThat(run(SATURDAY, SATURDAY_SESSIONS, List.of(S105, "9900000999")).clashes()).isEmpty();
    }

    @Test
    void dayWithoutSlotsOrRoundWithoutSessions() {
        assertThat(run(SUNDAY, List.of(new SessionTime(t("08:00"), t("18:00"))), SHORTLIST).clashes()).isEmpty();
        Result none = run(FRIDAY, List.of(), SHORTLIST);
        assertThat(none.window()).isEmpty();
        assertThat(none.clashes()).isEmpty();
    }

    @Test
    void mondaySlotClashesOnlyOnAMonday() {
        assertThat(run(MONDAY, List.of(new SessionTime(t("11:30"), t("13:00"))), SHORTLIST).clashes())
                .containsExactly(clash(S104, "CS103", "Databases", DayOfWeek.MONDAY, "11:00", "12:00", "C-303"));
    }

    @ParameterizedTest(name = "slot {0}-{1} vs window 11:00-12:30 -> {2}")
    @CsvSource({
            "10:00, 11:00, false",  // ends at window start
            "12:30, 13:30, false",  // starts at window end
            "10:59, 11:01, true",   // one minute inside at the start
            "12:29, 13:00, true",   // one minute inside at the end
            "11:15, 12:00, true",   // inside
            "10:00, 13:00, true",   // covers the window
            "08:00, 09:00, false"   // before
    })
    void overlapRule(String start, String end, boolean expected) {
        assertThat(ClashCalculator.overlaps(t(start), t(end), new Window(t("11:00"), t("12:30")))).isEqualTo(expected);
    }

    @Test
    void resultAndHashDoNotDependOnInputOrder() {
        List<String> shuffled = new ArrayList<>(SHORTLIST);
        Collections.reverse(shuffled);
        Result a = run(SATURDAY, SATURDAY_SESSIONS, SHORTLIST);
        Result b = run(SATURDAY, SATURDAY_SESSIONS, shuffled);

        assertThat(b.clashes()).isEqualTo(a.clashes());
        assertThat(ClashCalculator.hash("2026-ODD", b)).isEqualTo(ClashCalculator.hash("2026-ODD", a));
    }

    @Test
    void hashChangesWithWindowSemesterOrClashes() {
        Result base = run(FRIDAY, FRIDAY_SESSIONS, SHORTLIST);
        String h = ClashCalculator.hash("2026-ODD", base);

        assertThat(ClashCalculator.hash("2026-EVEN", base)).isNotEqualTo(h);
        assertThat(ClashCalculator.hash("2026-ODD", run(FRIDAY, FRIDAY_SESSIONS, List.of(S101)))).isNotEqualTo(h);
        // same clashes, wider window -> different hash (consumers see the window)
        Result wider = ClashCalculator.compute(FRIDAY, List.of(new SessionTime(t("11:00"), t("12:45"))),
                SHORTLIST, SECTIONS_BY_STUDENT, Map.of("CS101", SLOTS_BY_SECTION.get("CS101")));
        assertThat(wider.clashes()).isEqualTo(base.clashes());
        assertThat(ClashCalculator.hash("2026-ODD", wider)).isNotEqualTo(h);
    }
}
