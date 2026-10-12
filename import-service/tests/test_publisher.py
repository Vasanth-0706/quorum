import json

import publisher

UPLOADED_AT = "2026-10-12T11:48:00"
SLOTS = [{"sectionCode": "S12", "courseName": "Cloud Native", "dayOfWeek": "MONDAY",
          "startTime": "10:00", "endTime": "10:50", "room": "C-301"}]


def enrolments(count):
    return [{"studentId": str(9900000000 + n), "sectionCode": "S12"} for n in range(count)]


def test_shortlist_event_shape():
    event = publisher.shortlist_event("upload-1", 12, ["9900000001"], UPLOADED_AT)
    assert list(event) == ["eventId", "eventType", "uploadId", "roundId", "studentIds", "uploadedAt"]
    assert event["eventType"] == "SHORTLIST_UPLOADED"
    assert event["roundId"] == 12
    assert event["studentIds"] == ["9900000001"]


def test_attendance_event_shape():
    event = publisher.attendance_event("upload-1", 7, ["9900000001"], UPLOADED_AT)
    assert list(event) == ["eventId", "eventType", "uploadId", "sessionId", "studentIds", "uploadedAt"]
    assert event["eventType"] == "ATTENDANCE_UPLOADED"


def test_small_timetable_is_a_single_event():
    events = publisher.timetable_events("upload-1", "2026-ODD", SLOTS, enrolments(3), UPLOADED_AT)
    assert len(events) == 1
    assert list(events[0]) == ["eventId", "eventType", "uploadId", "semester", "part", "totalParts",
                               "slots", "enrolments", "uploadedAt"]
    assert (events[0]["part"], events[0]["totalParts"]) == (1, 1)
    assert events[0]["slots"] == SLOTS
    assert events[0]["enrolments"] == enrolments(3)


def test_timetable_without_enrolments_still_carries_the_slots():
    events = publisher.timetable_events("upload-1", "2026-ODD", SLOTS, [], UPLOADED_AT)
    assert len(events) == 1
    assert events[0]["slots"] == SLOTS and events[0]["enrolments"] == []


def test_large_timetable_is_split_under_900_kb():
    everyone = enrolments(60_000)  # about 2.8 MB of enrolments
    events = publisher.timetable_events("upload-1", "2026-ODD", SLOTS, everyone, UPLOADED_AT)

    assert len(events) == 4
    assert all(len(publisher.encode(event)) <= 900 * 1024 for event in events)
    assert [event["part"] for event in events] == [1, 2, 3, 4]
    assert all(event["totalParts"] == 4 for event in events)
    assert all(event["uploadId"] == "upload-1" for event in events)
    assert len({event["eventId"] for event in events}) == 4
    assert events[0]["slots"] == SLOTS
    assert all(event["slots"] == [] for event in events[1:])
    # nothing lost, nothing repeated, order kept
    assert [e for event in events for e in event["enrolments"]] == everyone


def test_split_fills_each_part_right_up_to_the_limit():
    limit = 1000
    events = publisher.timetable_events(
        "upload-1", "2026-ODD", SLOTS, enrolments(100), UPLOADED_AT, max_bytes=limit)
    sizes = [len(publisher.encode(event)) for event in events]
    assert len(events) > 1
    assert max(sizes) <= limit
    # every part except the last one has no room left for another enrolment
    one_enrolment = len(publisher.encode(enrolments(1)[0])) + 1
    assert all(size + one_enrolment + 20 > limit for size in sizes[:-1])


def test_encoding_is_compact_utf8_json():
    event = publisher.timetable_events("upload-1", "2026-ODD", [dict(SLOTS[0], room="Café")], [],
                                       UPLOADED_AT)[0]
    raw = publisher.encode(event)
    assert "Café".encode("utf-8") in raw
    assert json.loads(raw) == event
