package com.quorum.timetableservice.service;

import com.quorum.timetableservice.service.ClashCalculator.SlotInfo;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The small fixed timetable the expectations are checked against by hand.
 * All student IDs are made up.
 *
 * <pre>
 * Section  Course      Slot                      Students
 * CS101    Algorithms  FRI 10:00-11:00  (A)       9900000101, 9900000103
 *                      FRI 12:00-13:00  (B)
 *                      SAT 10:30-11:30  (C)
 * CS102    Networks    FRI 12:30-13:30  (D)       9900000102, 9900000103
 *                      SAT 08:00-09:30  (E)
 * CS103    Databases   MON 11:00-12:00  (F)       9900000104
 * (none)                                          9900000105
 *
 * Drive over two days, one round, two sessions:
 *   Fri 2026-12-04  11:00-12:30   window [11:00, 12:30]
 *   Sat 2026-12-05  09:00-11:00   window [09:00, 11:00]   (the next day)
 * </pre>
 */
final class TimetableFixture {

    static final LocalDate FRIDAY = LocalDate.of(2026, 12, 4);
    static final LocalDate SATURDAY = LocalDate.of(2026, 12, 5);
    static final LocalDate SUNDAY = LocalDate.of(2026, 12, 6);
    static final LocalDate MONDAY = LocalDate.of(2026, 12, 7);

    static final String S101 = "9900000101";
    static final String S102 = "9900000102";
    static final String S103 = "9900000103";
    static final String S104 = "9900000104";
    static final String S105 = "9900000105";
    static final List<String> SHORTLIST = List.of(S101, S102, S103, S104, S105);

    static final Map<String, Set<String>> SECTIONS_BY_STUDENT = Map.of(
            S101, Set.of("CS101"),
            S102, Set.of("CS102"),
            S103, Set.of("CS101", "CS102"),
            S104, Set.of("CS103"));

    static final Map<String, List<SlotInfo>> SLOTS_BY_SECTION = Map.of(
            "CS101", List.of(
                    slot("CS101", "Algorithms", DayOfWeek.FRIDAY, "10:00", "11:00", "A-101"),
                    slot("CS101", "Algorithms", DayOfWeek.FRIDAY, "12:00", "13:00", "A-101"),
                    slot("CS101", "Algorithms", DayOfWeek.SATURDAY, "10:30", "11:30", "Lab-1")),
            "CS102", List.of(
                    slot("CS102", "Networks", DayOfWeek.FRIDAY, "12:30", "13:30", "B-202"),
                    slot("CS102", "Networks", DayOfWeek.SATURDAY, "08:00", "09:30", "B-202")),
            "CS103", List.of(
                    slot("CS103", "Databases", DayOfWeek.MONDAY, "11:00", "12:00", "C-303")));

    static final List<ClashCalculator.SessionTime> FRIDAY_SESSIONS =
            List.of(new ClashCalculator.SessionTime(t("11:00"), t("12:30")));
    static final List<ClashCalculator.SessionTime> SATURDAY_SESSIONS =
            List.of(new ClashCalculator.SessionTime(t("09:00"), t("11:00")));

    /** The same timetable as a TIMETABLE_UPLOADED payload (slots, enrolments). */
    static final String SLOTS_JSON = """
            [{"sectionCode":"CS101","courseName":"Algorithms","dayOfWeek":"FRIDAY","startTime":"10:00","endTime":"11:00","room":"A-101"},
             {"sectionCode":"CS101","courseName":"Algorithms","dayOfWeek":"FRIDAY","startTime":"12:00","endTime":"13:00","room":"A-101"},
             {"sectionCode":"CS101","courseName":"Algorithms","dayOfWeek":"SATURDAY","startTime":"10:30","endTime":"11:30","room":"Lab-1"},
             {"sectionCode":"CS102","courseName":"Networks","dayOfWeek":"FRIDAY","startTime":"12:30","endTime":"13:30","room":"B-202"},
             {"sectionCode":"CS102","courseName":"Networks","dayOfWeek":"SATURDAY","startTime":"08:00","endTime":"09:30","room":"B-202"},
             {"sectionCode":"CS103","courseName":"Databases","dayOfWeek":"MONDAY","startTime":"11:00","endTime":"12:00","room":"C-303"}]""";
    static final String ENROLMENTS_PART_1 = """
            [{"studentId":"9900000101","sectionCode":"CS101"},
             {"studentId":"9900000102","sectionCode":"CS102"},
             {"studentId":"9900000103","sectionCode":"CS101"}]""";
    static final String ENROLMENTS_PART_2 = """
            [{"studentId":"9900000103","sectionCode":"CS102"},
             {"studentId":"9900000104","sectionCode":"CS103"}]""";

    private TimetableFixture() {
    }

    static LocalTime t(String hhmm) {
        return LocalTime.parse(hhmm);
    }

    static SlotInfo slot(String section, String course, DayOfWeek day, String start, String end, String room) {
        return new SlotInfo(section, course, day, t(start), t(end), room);
    }

    static ClashCalculator.Clash clash(String student, String section, String course, DayOfWeek day,
                                       String start, String end, String room) {
        return new ClashCalculator.Clash(student, section, course, day, t(start), t(end), room);
    }
}
