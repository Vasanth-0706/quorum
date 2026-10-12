"""End-to-end tests of the routes, with SQLite and a fake Kafka publisher."""
import io
import json

import openpyxl
import pytest
from fastapi.testclient import TestClient

import main
import models
import publisher
from helpers import xlsx

XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"


@pytest.fixture
def sent(monkeypatch):
    """Messages 'published' during the test, as (key, event) pairs."""
    messages = []
    monkeypatch.setattr(publisher, "ensure_topic", lambda: None)
    monkeypatch.setattr(
        publisher, "publish",
        lambda key, events: messages.extend((key, json.loads(publisher.encode(e))) for e in events))
    return messages


@pytest.fixture
def client(tmp_path, monkeypatch, sent):
    database = f"sqlite:///{tmp_path / 'reports.db'}"
    monkeypatch.setattr(models, "database_url", lambda: database)
    with TestClient(main.app) as test_client:
        yield test_client


def upload(client, path, content, file_name, **form):
    return client.post(path, files={"file": (file_name, content)}, data=form)


def test_shortlist_upload_publishes_and_saves_the_report(client, sent):
    content = xlsx({"Sheet1": [
        ["Acme Corp shortlist"],
        ["Reg No"],
        [9900000001],
        ["99000002"],
        [None],
        [9900000001.0],
        [9900000003],
    ]})
    response = upload(client, "/imports/shortlist", content, "acme.xlsx", roundId=12)

    assert response.status_code == 200
    report = response.json()
    assert report["type"] == "SHORTLIST"
    assert report["targetId"] == "12"
    assert report["fileName"] == "acme.xlsx"
    assert (report["totalRows"], report["acceptedCount"], report["rejectedCount"]) == (4, 2, 2)
    assert report["rejected"] == [
        {"row": 4, "value": "99000002", "reason": "NOT_10_DIGITS"},
        {"row": 6, "value": "9900000001", "reason": "DUPLICATE"},
    ]
    assert report["status"] == "PUBLISHED"

    [(key, event)] = sent
    assert key == 12
    assert event["eventType"] == "SHORTLIST_UPLOADED"
    assert event["uploadId"] == report["uploadId"]
    assert event["roundId"] == 12
    assert event["studentIds"] == ["9900000001", "9900000003"]
    assert event["uploadedAt"] == report["uploadedAt"]

    assert client.get(f"/imports/{report['uploadId']}").json() == report


def test_attendance_upload_from_csv(client, sent):
    response = upload(client, "/imports/attendance", b"roll no\n9900000001\n9900000002\n",
                      "present.csv", sessionId=7)
    assert response.status_code == 200
    assert response.json()["type"] == "ATTENDANCE"
    [(key, event)] = sent
    assert key == 7
    assert event["eventType"] == "ATTENDANCE_UPLOADED"
    assert event["sessionId"] == 7
    assert event["studentIds"] == ["9900000001", "9900000002"]


def test_timetable_upload(client, sent):
    content = xlsx({
        "enrolments": [["studentId", "sectionCode"],
                       [9900000001, "S12"], [9900000002, "S99"], [9900000003, "S13"]],
        "slots": [["sectionCode", "courseName", "dayOfWeek", "startTime", "endTime", "room"],
                  ["S12", "Cloud Native", "Monday", "10:00", "10:50", "C-301"],
                  ["S13", "Compilers", "SUNDAY", "10:00", "10:50", "C-302"]],
    })
    response = upload(client, "/imports/timetable", content, "tt.xlsx", semester="2026-ODD")

    assert response.status_code == 200
    report = response.json()
    assert report["type"] == "TIMETABLE"
    assert report["targetId"] == "2026-ODD"
    assert (report["totalRows"], report["acceptedCount"], report["rejectedCount"]) == (5, 2, 3)
    assert report["rejected"] == [
        {"sheet": "slots", "row": 3, "value": "SUNDAY", "reason": "INVALID_DAY"},
        {"sheet": "enrolments", "row": 3, "value": "S99", "reason": "UNKNOWN_SECTION"},
        {"sheet": "enrolments", "row": 4, "value": "S13", "reason": "UNKNOWN_SECTION"},
    ]

    [(key, event)] = sent
    assert key == "2026-ODD"
    assert (event["part"], event["totalParts"]) == (1, 1)
    assert event["slots"] == [{"sectionCode": "S12", "courseName": "Cloud Native",
                               "dayOfWeek": "MONDAY", "startTime": "10:00", "endTime": "10:50",
                               "room": "C-301"}]
    assert event["enrolments"] == [{"studentId": "9900000001", "sectionCode": "S12"}]


def test_nothing_accepted_returns_422_and_publishes_nothing(client, sent):
    response = upload(client, "/imports/shortlist", b"studentId\n123\nabc\n", "bad.csv", roundId=1)
    assert response.status_code == 422
    report = response.json()
    assert report["status"] == "NOTHING_TO_PUBLISH"
    assert report["acceptedCount"] == 0
    assert sent == []
    assert client.get(f"/imports/{report['uploadId']}").json() == report


def test_kafka_failure_returns_503_and_keeps_the_report(client, monkeypatch):
    def fail(key, events):
        raise publisher.PublishError("broker down")

    monkeypatch.setattr(publisher, "publish", fail)
    response = upload(client, "/imports/shortlist", b"studentId\n9900000001\n", "ok.csv", roundId=1)
    assert response.status_code == 503
    report = response.json()
    assert report["status"] == "PUBLISH_FAILED"
    assert report["acceptedCount"] == 1
    assert client.get(f"/imports/{report['uploadId']}").json() == report


@pytest.mark.parametrize("file_name, content, message", [
    ("list.pdf", b"studentId\n9900000001\n", "only .xlsx and .csv"),
    ("list.csv", b"x" * (5 * 1024 * 1024 + 1), "maximum size is 5 MB"),
    ("list.csv", b"name\nsomeone\n", "Header row not found"),
    ("list.xlsx", b"not a workbook", "not a valid .xlsx"),
])
def test_unusable_files_return_400(client, sent, file_name, content, message):
    response = upload(client, "/imports/shortlist", content, file_name, roundId=1)
    assert response.status_code == 400
    assert message in response.json()["detail"]
    assert sent == []


def test_timetable_without_slots_sheet_returns_400(client):
    content = xlsx({"enrolments": [["studentId", "sectionCode"]]})
    response = upload(client, "/imports/timetable", content, "tt.xlsx", semester="2026-ODD")
    assert response.status_code == 400
    assert "Sheet 'slots' not found" in response.json()["detail"]


def test_unknown_upload_returns_404(client):
    assert client.get("/imports/does-not-exist").status_code == 404


@pytest.mark.parametrize("template, sheets", [
    ("shortlist", {"shortlist": ["studentId"]}),
    ("attendance", {"attendance": ["studentId"]}),
    ("timetable", {"enrolments": ["studentId", "sectionCode"],
                   "slots": ["sectionCode", "courseName", "dayOfWeek", "startTime", "endTime",
                             "room"]}),
])
def test_templates(client, template, sheets):
    response = client.get(f"/imports/templates/{template}")
    assert response.status_code == 200
    assert response.headers["content-type"] == XLSX
    workbook = openpyxl.load_workbook(io.BytesIO(response.content))
    assert {s.title: [cell.value for cell in s[1]] for s in workbook} == sheets


def test_unknown_template_returns_404(client):
    assert client.get("/imports/templates/payroll").status_code == 404
