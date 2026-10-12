"""Row-by-row validation. Every function returns (accepted, rejected)."""
import datetime
import re

DAYS = ("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY")


def cell_text(value):
    """Raw cell -> trimmed text, undoing what Excel does to numbers and times."""
    if value is None:
        return ""
    if isinstance(value, float):
        if value != value:  # NaN
            return ""
        if value.is_integer():
            return str(int(value))
    if isinstance(value, (datetime.time, datetime.datetime)):
        return value.strftime("%H:%M")
    return str(value).strip()


def normalise_student_id(value):
    """9900000001.0 -> '9900000001', ' 99000 00001 ' -> '9900000001'."""
    text = re.sub(r"\s+", "", cell_text(value))
    return re.sub(r"\.0+$", "", text) if re.fullmatch(r"\d+\.0+", text) else text


def student_id_problem(student_id):
    """Reason a normalised student ID is invalid, or None if it is fine."""
    if not student_id:
        return "EMPTY"
    if not re.fullmatch(r"[0-9]+", student_id):
        return "NOT_NUMERIC"
    if len(student_id) != 10:
        return "NOT_10_DIGITS"
    return None


def validate_student_ids(rows):
    """rows: [(row_number, {"studentId": cell})] -> (student_ids, rejected)."""
    accepted, rejected, seen = [], [], set()
    for row_number, record in rows:
        student_id = normalise_student_id(record["studentId"])
        reason = student_id_problem(student_id)
        if reason is None and student_id in seen:
            reason = "DUPLICATE"
        if reason:
            rejected.append({"row": row_number, "value": student_id, "reason": reason})
            continue
        seen.add(student_id)
        accepted.append(student_id)
    return accepted, rejected


def parse_time(value):
    """'9:05', '09:05' or '09:05:00' -> '09:05'; None if it is not a time."""
    match = re.fullmatch(r"(\d{1,2}):(\d{2})(?::\d{2})?", cell_text(value))
    if not match:
        return None
    hour, minute = int(match.group(1)), int(match.group(2))
    if hour > 23 or minute > 59:
        return None
    return f"{hour:02d}:{minute:02d}"


def validate_slots(rows):
    """rows from the `slots` sheet -> (slots, rejected)."""
    accepted, rejected = [], []

    def reject(row_number, value, reason):
        rejected.append({"sheet": "slots", "row": row_number, "value": value, "reason": reason})

    for row_number, record in rows:
        section_code = cell_text(record["sectionCode"])
        day = cell_text(record["dayOfWeek"]).upper()
        start, end = parse_time(record["startTime"]), parse_time(record["endTime"])

        if not section_code:
            reject(row_number, "", "EMPTY_SECTION_CODE")
        elif day not in DAYS:
            reject(row_number, cell_text(record["dayOfWeek"]), "INVALID_DAY")
        elif start is None:
            reject(row_number, cell_text(record["startTime"]), "BAD_TIME_FORMAT")
        elif end is None:
            reject(row_number, cell_text(record["endTime"]), "BAD_TIME_FORMAT")
        elif start >= end:
            reject(row_number, f"{start}-{end}", "START_NOT_BEFORE_END")
        else:
            accepted.append({
                "sectionCode": section_code,
                "courseName": cell_text(record["courseName"]),
                "dayOfWeek": day,
                "startTime": start,
                "endTime": end,
                "room": cell_text(record["room"]),
            })
    return accepted, rejected


def validate_enrolments(rows, known_sections):
    """rows from the `enrolments` sheet -> (enrolments, rejected).

    A student may be in several sections, so a duplicate is a repeated
    (studentId, sectionCode) pair.
    """
    accepted, rejected, seen = [], [], set()

    def reject(row_number, value, reason):
        rejected.append({"sheet": "enrolments", "row": row_number, "value": value, "reason": reason})

    for row_number, record in rows:
        student_id = normalise_student_id(record["studentId"])
        section_code = cell_text(record["sectionCode"])
        reason = student_id_problem(student_id)

        if reason:
            reject(row_number, student_id, reason)
        elif not section_code:
            reject(row_number, "", "EMPTY_SECTION_CODE")
        elif section_code not in known_sections:
            reject(row_number, section_code, "UNKNOWN_SECTION")
        elif (student_id, section_code) in seen:
            reject(row_number, f"{student_id} {section_code}", "DUPLICATE")
        else:
            seen.add((student_id, section_code))
            accepted.append({"studentId": student_id, "sectionCode": section_code})
    return accepted, rejected
