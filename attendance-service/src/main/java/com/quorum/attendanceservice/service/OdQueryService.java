package com.quorum.attendanceservice.service;

import com.quorum.attendanceservice.dto.MarkResponse;
import com.quorum.attendanceservice.dto.OdRecordResponse;
import com.quorum.attendanceservice.entity.OdRecord;
import com.quorum.attendanceservice.entity.OdRecord.Status;
import com.quorum.attendanceservice.repository.AttendanceMarkRepository;
import com.quorum.attendanceservice.repository.OdRecordRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Read side: OD records, marks and the CSV export. */
@Service
@Transactional(readOnly = true)
public class OdQueryService {

    private static final LocalDate MIN_DATE = LocalDate.of(1900, 1, 1);
    private static final LocalDate MAX_DATE = LocalDate.of(9999, 12, 31);

    private final OdRecordRepository records;
    private final AttendanceMarkRepository marks;

    public OdQueryService(OdRecordRepository records, AttendanceMarkRepository marks) {
        this.records = records;
        this.marks = marks;
    }

    public List<OdRecordResponse> byDate(LocalDate date, boolean includeRevoked) {
        return map(records.findByDateAndStatusInOrderByStudentIdAscSlotStartAsc(date, statuses(includeRevoked)));
    }

    public List<OdRecordResponse> byStudent(String studentId, LocalDate from, LocalDate to, boolean includeRevoked) {
        LocalDate start = from == null ? MIN_DATE : from;
        LocalDate end = to == null ? MAX_DATE : to;
        if (start.isAfter(end)) {
            throw new BadRequestException("from must not be after to");
        }
        return map(records.findByStudentIdAndDateBetweenAndStatusInOrderByDateAscSlotStartAsc(studentId, start, end,
                statuses(includeRevoked)));
    }

    public List<OdRecordResponse> bySession(long sessionId, boolean includeRevoked) {
        return map(records.findBySessionIdAndStatusInOrderByStudentIdAscSlotStartAsc(sessionId,
                statuses(includeRevoked)));
    }

    /** The latest mark per student, or with history every version, oldest first. */
    public List<MarkResponse> marks(long sessionId, boolean history) {
        return (history ? marks.findBySessionIdOrderByStudentIdAscVersionAsc(sessionId)
                : marks.findLatestBySession(sessionId)).stream().map(MarkResponse::from).toList();
    }

    /** Granted OD of the date as CSV: studentId, date, course, section, slotStart, slotEnd, sessionId, reason. */
    public String exportCsv(LocalDate date) {
        StringBuilder csv = new StringBuilder("studentId,date,course,section,slotStart,slotEnd,sessionId,reason\r\n");
        for (OdRecord r : records.findByDateAndStatusInOrderByStudentIdAscSlotStartAsc(date, Set.of(Status.GRANTED))) {
            csv.append(String.join(",", cell(r.getStudentId()), cell(r.getDate().toString()), cell(r.getCourseName()),
                    cell(r.getSectionCode()), cell(r.getSlotStart().toString()), cell(r.getSlotEnd().toString()),
                    cell(String.valueOf(r.getSessionId())), cell(r.getReason()))).append("\r\n");
        }
        return csv.toString();
    }

    /**
     * RFC 4180 quoting. Course names come from uploaded files, so a cell that a spreadsheet
     * would run as a formula (= + - @ tab CR) is prefixed with an apostrophe.
     */
    static String cell(String value) {
        if (value == null) {
            return "";
        }
        String v = !value.isEmpty() && "=+-@\t\r".indexOf(value.charAt(0)) >= 0 ? "'" + value : value;
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }

    private static Set<Status> statuses(boolean includeRevoked) {
        return includeRevoked ? EnumSet.allOf(Status.class) : EnumSet.of(Status.GRANTED);
    }

    private static List<OdRecordResponse> map(List<OdRecord> rows) {
        return rows.stream().map(OdRecordResponse::from).toList();
    }
}
