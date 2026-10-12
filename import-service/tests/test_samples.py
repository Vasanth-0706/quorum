"""The files in samples/ must keep behaving the way the README describes."""
from pathlib import Path

import parsing
import validation

SAMPLES = Path(__file__).parent.parent / "samples"


def student_ids(file_name):
    rows = parsing.read_rows((SAMPLES / file_name).read_bytes(), file_name)
    return validation.validate_student_ids(parsing.extract(rows, parsing.STUDENT_ID_COLUMNS))


def test_valid_shortlist():
    accepted, rejected = student_ids("shortlist_valid.xlsx")
    assert len(accepted) == 25 and rejected == []


def test_shortlist_with_errors():
    accepted, rejected = student_ids("shortlist_with_errors.xlsx")
    assert accepted == ["9900000001", "9900000002", "9900000003", "9900000008"]
    assert rejected == [
        {"row": 7, "value": "99000004", "reason": "NOT_10_DIGITS"},
        {"row": 8, "value": "99AB000005", "reason": "NOT_NUMERIC"},
        {"row": 9, "value": "", "reason": "EMPTY"},
        {"row": 11, "value": "9900000001", "reason": "DUPLICATE"},
    ]


def test_attendance():
    accepted, rejected = student_ids("attendance.csv")
    assert len(accepted) == 20 and rejected == []


def test_timetable():
    content = (SAMPLES / "timetable.xlsx").read_bytes()
    slots, rejected_slots = validation.validate_slots(parsing.extract(
        parsing.read_rows(content, "timetable.xlsx", "slots"), parsing.SLOT_COLUMNS))
    enrolments, rejected_enrolments = validation.validate_enrolments(
        parsing.extract(parsing.read_rows(content, "timetable.xlsx", "enrolments"),
                        parsing.ENROLMENT_COLUMNS),
        {slot["sectionCode"] for slot in slots})

    assert len(slots) == 4
    assert slots[1]["startTime"] == "11:00"  # stored as an Excel time cell
    assert [(r["row"], r["reason"]) for r in rejected_slots] == [
        (6, "INVALID_DAY"), (7, "START_NOT_BEFORE_END")]
    assert len(enrolments) == 20
    assert rejected_enrolments == [
        {"sheet": "enrolments", "row": 22, "value": "S99", "reason": "UNKNOWN_SECTION"}]
