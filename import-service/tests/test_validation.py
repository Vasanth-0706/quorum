import datetime

import pytest

import validation
from helpers import student_rows


@pytest.mark.parametrize("raw, expected", [
    (9900000001, "9900000001"),
    (9900000001.0, "9900000001"),
    ("9900000001.0", "9900000001"),
    (" 99000 00001 ", "9900000001"),
    ("9900000001 ", "9900000001"),
    (None, ""),
    (float("nan"), ""),
    ("   ", ""),
])
def test_normalise_student_id(raw, expected):
    assert validation.normalise_student_id(raw) == expected


def test_numeric_ids_from_excel_are_accepted():
    accepted, rejected = validation.validate_student_ids(
        student_rows([9900000001, 9900000002.0, "9900000003.0", " 9900000004 "]))
    assert accepted == ["9900000001", "9900000002", "9900000003", "9900000004"]
    assert rejected == []


def test_rejection_reasons():
    accepted, rejected = validation.validate_student_ids(
        student_rows([None, "99000001", 99000000011, "99AB000001", "9900000001.5", 9900000001]))
    assert accepted == ["9900000001"]
    assert rejected == [
        {"row": 2, "value": "", "reason": "EMPTY"},
        {"row": 3, "value": "99000001", "reason": "NOT_10_DIGITS"},
        {"row": 4, "value": "99000000011", "reason": "NOT_10_DIGITS"},
        {"row": 5, "value": "99AB000001", "reason": "NOT_NUMERIC"},
        {"row": 6, "value": "9900000001.5", "reason": "NOT_NUMERIC"},
    ]


def test_duplicates_keep_the_first_occurrence():
    accepted, rejected = validation.validate_student_ids(
        student_rows([9900000001, "9900000002", 9900000001.0, " 9900000002"]))
    assert accepted == ["9900000001", "9900000002"]
    assert rejected == [
        {"row": 4, "value": "9900000001", "reason": "DUPLICATE"},
        {"row": 5, "value": "9900000002", "reason": "DUPLICATE"},
    ]


def slot(**changes):
    record = {"sectionCode": "S12", "courseName": "Cloud Native", "dayOfWeek": "MONDAY",
              "startTime": "10:00", "endTime": "10:50", "room": "C-301"}
    record.update(changes)
    return record


def test_valid_slot_is_normalised():
    rows = [(2, slot(dayOfWeek=" monday ", startTime=datetime.time(9, 0), endTime="9:50:00"))]
    accepted, rejected = validation.validate_slots(rows)
    assert rejected == []
    assert accepted == [{"sectionCode": "S12", "courseName": "Cloud Native", "dayOfWeek": "MONDAY",
                         "startTime": "09:00", "endTime": "09:50", "room": "C-301"}]


@pytest.mark.parametrize("changes, value, reason", [
    ({"sectionCode": None}, "", "EMPTY_SECTION_CODE"),
    ({"sectionCode": "  "}, "", "EMPTY_SECTION_CODE"),
    ({"dayOfWeek": "SUNDAY"}, "SUNDAY", "INVALID_DAY"),
    ({"dayOfWeek": "Mon"}, "Mon", "INVALID_DAY"),
    ({"dayOfWeek": None}, "", "INVALID_DAY"),
    ({"startTime": "10.00"}, "10.00", "BAD_TIME_FORMAT"),
    ({"startTime": "25:00"}, "25:00", "BAD_TIME_FORMAT"),
    ({"endTime": "10:60"}, "10:60", "BAD_TIME_FORMAT"),
    ({"endTime": "ten"}, "ten", "BAD_TIME_FORMAT"),
    ({"endTime": None}, "", "BAD_TIME_FORMAT"),
    ({"startTime": "10:50", "endTime": "10:00"}, "10:50-10:00", "START_NOT_BEFORE_END"),
    ({"startTime": "10:00", "endTime": "10:00"}, "10:00-10:00", "START_NOT_BEFORE_END"),
])
def test_bad_slots_are_rejected(changes, value, reason):
    accepted, rejected = validation.validate_slots([(7, slot(**changes))])
    assert accepted == []
    assert rejected == [{"sheet": "slots", "row": 7, "value": value, "reason": reason}]


def test_enrolments_with_unknown_sections_are_rejected():
    rows = [
        (2, {"studentId": 9900000001, "sectionCode": "S12"}),
        (3, {"studentId": 9900000002, "sectionCode": "S99"}),
        (4, {"studentId": 9900000003, "sectionCode": None}),
        (5, {"studentId": "990000", "sectionCode": "S12"}),
        (6, {"studentId": 9900000001.0, "sectionCode": " S12 "}),
        (7, {"studentId": 9900000001, "sectionCode": "S13"}),
    ]
    accepted, rejected = validation.validate_enrolments(rows, {"S12", "S13"})
    assert accepted == [
        {"studentId": "9900000001", "sectionCode": "S12"},
        {"studentId": "9900000001", "sectionCode": "S13"},
    ]
    assert rejected == [
        {"sheet": "enrolments", "row": 3, "value": "S99", "reason": "UNKNOWN_SECTION"},
        {"sheet": "enrolments", "row": 4, "value": "", "reason": "EMPTY_SECTION_CODE"},
        {"sheet": "enrolments", "row": 5, "value": "990000", "reason": "NOT_10_DIGITS"},
        {"sheet": "enrolments", "row": 6, "value": "9900000001 S12", "reason": "DUPLICATE"},
    ]
