package com.quorum.attendanceservice.service;

import com.quorum.attendanceservice.service.OdCalculator.ClashSlot;
import com.quorum.attendanceservice.service.OdCalculator.Grant;
import com.quorum.attendanceservice.service.OdCalculator.Key;
import com.quorum.attendanceservice.service.OdCalculator.RoundDay;
import com.quorum.attendanceservice.service.OdCalculator.Session;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hand-checked cases. 2026-12-07 is a MONDAY. Round 7 has two sessions that day:
 * 41 09:30-10:15 and 42 10:15-11:00. The student's Monday slots:
 * S11 08:00-09:00, S12 10:00-10:50, S13 10:30-11:20, S14 11:00-11:50 (touches 11:00), S15 09:00-09:30 (touches 09:30).
 */
class OdCalculatorTest {

    private static final LocalDate MONDAY = LocalDate.of(2026, 12, 7);

    private static LocalTime t(String s) {
        return LocalTime.parse(s);
    }

    private static Session session(long id, String start, String end) {
        return new Session(id, t(start), t(end), false);
    }

    private static ClashSlot slot(String section, String start, String end) {
        return new ClashSlot(section, "Course " + section, DayOfWeek.MONDAY, t(start), t(end));
    }

    private static final List<Session> SESSIONS = List.of(session(41, "09:30", "10:15"), session(42, "10:15", "11:00"));
    private static final List<ClashSlot> SLOTS = List.of(
            slot("S11", "08:00", "09:00"),
            slot("S12", "10:00", "10:50"),
            slot("S13", "10:30", "11:20"),
            slot("S14", "11:00", "11:50"),
            slot("S15", "09:00", "09:30"));

    private static RoundDay day(Set<Long> present, List<Session> sessions, List<ClashSlot> clashes) {
        return new RoundDay(7, MONDAY, sessions, present, clashes);
    }

    private static List<String> sections(List<Grant> grants) {
        return grants.stream().map(Grant::sectionCode).toList();
    }

    @Test
    void presentInFirstSessionGetsOdOnlyForSlotsOverlappingItsWindow() {
        // window = [09:30, 10:15]
        List<Grant> grants = OdCalculator.decide(day(Set.of(41L), SESSIONS, SLOTS));

        assertThat(sections(grants)).containsExactly("S12");
        Grant g = grants.get(0);
        assertThat(g.slotStart()).isEqualTo(t("10:00"));
        assertThat(g.slotEnd()).isEqualTo(t("10:50"));
        assertThat(g.courseName()).isEqualTo("Course S12");
        assertThat(g.sessionId()).isEqualTo(41);
        assertThat(g.roundId()).isEqualTo(7);
        assertThat(g.reason()).isEqualTo("Attended session 41 (09:30-10:15) of placement round 7");
    }

    @Test
    void waitingTimeCountsWhenTheStudentAttendedTheSecondSession() {
        // window = [09:30 (earliest start of the round that day), 11:00 (end of session 42)]
        List<Grant> grants = OdCalculator.decide(day(Set.of(42L), SESSIONS, SLOTS));

        assertThat(sections(grants)).containsExactly("S12", "S13");
        assertThat(grants).allSatisfy(g -> assertThat(g.sessionId()).isEqualTo(42));
        assertThat(OdCalculator.window(day(Set.of(42L), SESSIONS, SLOTS))).get()
                .satisfies(w -> {
                    assertThat(w.start()).isEqualTo(t("09:30"));
                    assertThat(w.end()).isEqualTo(t("11:00"));
                });
    }

    @Test
    void slotsThatOnlyTouchTheWindowGetNoOd() {
        List<Grant> grants = OdCalculator.decide(day(Set.of(42L), SESSIONS, SLOTS));

        assertThat(sections(grants)).doesNotContain("S14", "S15", "S11");
        assertThat(OdCalculator.overlaps(t("11:00"), t("11:50"), t("09:30"), t("11:00"))).isFalse();
        assertThat(OdCalculator.overlaps(t("09:00"), t("09:30"), t("09:30"), t("11:00"))).isFalse();
        assertThat(OdCalculator.overlaps(t("10:59"), t("11:50"), t("09:30"), t("11:00"))).isTrue();
    }

    @Test
    void absentOrUnmarkedStudentGetsNothing() {
        assertThat(OdCalculator.decide(day(Set.of(), SESSIONS, SLOTS))).isEmpty();
        assertThat(OdCalculator.window(day(Set.of(), SESSIONS, SLOTS))).isEmpty();
        // present in a session of ANOTHER round does not count here
        assertThat(OdCalculator.decide(day(Set.of(99L), SESSIONS, SLOTS))).isEmpty();
    }

    @Test
    void noStoredClashesMeansNoOd() {
        assertThat(OdCalculator.decide(day(Set.of(41L, 42L), SESSIONS, List.of()))).isEmpty();
    }

    @Test
    void noKnownSessionMeansNoOd() {
        assertThat(OdCalculator.decide(day(Set.of(41L), List.of(), SLOTS))).isEmpty();
    }

    @Test
    void cancelledSessionsStillStartTheWindowAndKeepTheirOd() {
        List<Session> sessions = List.of(new Session(40, t("08:30"), t("09:15"), true),
                session(41, "09:30", "10:15"));

        // a cancelled earlier session moves the start of the waiting window to 08:30
        assertThat(sections(OdCalculator.decide(day(Set.of(41L), sessions, SLOTS)))).containsExactly("S11", "S12", "S15");
        // the student marked present in the cancelled session keeps OD for [08:30, 09:15]
        assertThat(sections(OdCalculator.decide(day(Set.of(40L), sessions, SLOTS)))).containsExactly("S11", "S15");
    }

    @Test
    void presentInBothSessionsUsesTheOneThatEndsLast() {
        List<Grant> grants = OdCalculator.decide(day(Set.of(41L, 42L), SESSIONS, SLOTS));
        assertThat(sections(grants)).containsExactly("S12", "S13");
        assertThat(grants).allSatisfy(g -> assertThat(g.sessionId()).isEqualTo(42));
    }

    @Test
    void slotOnAnotherWeekdayIsIgnored() {
        ClashSlot tuesday = new ClashSlot("S20", "Other", DayOfWeek.TUESDAY, t("10:00"), t("10:50"));
        assertThat(OdCalculator.decide(day(Set.of(41L), SESSIONS, List.of(tuesday)))).isEmpty();
    }

    @Test
    void sameSlotFromTwoRoundsIsGrantedOnceByTheLowerRound() {
        RoundDay round9 = new RoundDay(9, MONDAY, List.of(session(90, "09:45", "10:30")), Set.of(90L),
                List.of(slot("S12", "10:00", "10:50")));
        RoundDay round7 = day(Set.of(41L), SESSIONS, SLOTS);

        Map<Key, Grant> merged = OdCalculator.decideDay(List.of(round9, round7));

        assertThat(merged).hasSize(1);
        assertThat(merged.get(new Key("S12", t("10:00"))).roundId()).isEqualTo(7);
        assertThat(OdCalculator.decideDay(List.of(round7, round9))).isEqualTo(merged);
    }

    @Test
    void revokeReasonSaysWhatChanged() {
        assertThat(OdCalculator.revokeReason(7, "S12", t("10:00"), null))
                .isEqualTo("Slot is no longer in the clash list of round 7");
        assertThat(OdCalculator.revokeReason(7, "S12", t("10:00"), day(Set.of(41L), SESSIONS, List.of())))
                .isEqualTo("Slot is no longer in the clash list of round 7");
        assertThat(OdCalculator.revokeReason(7, "S12", t("10:00"), day(Set.of(), SESSIONS, SLOTS)))
                .isEqualTo("No longer marked present in any session of round 7");
        assertThat(OdCalculator.revokeReason(7, "S14", t("11:00"), day(Set.of(42L), SESSIONS, SLOTS)))
                .isEqualTo("Slot no longer overlaps the OD window 09:30-11:00 of round 7");
    }
}
