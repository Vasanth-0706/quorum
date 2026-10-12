# import-service

Coordinators upload Excel/CSV files here. The service reads them, validates the
student IDs, saves an upload report in MySQL and publishes the accepted data to
the Kafka topic `import-events` for the Java services. It never calls another
service directly.

| | |
|---|---|
| Port | 8090 |
| Stack | Python 3.12+, FastAPI, pandas + openpyxl, confluent-kafka, SQLAlchemy + PyMySQL |
| Database | MySQL `quorum_import` (tables are created on startup) |
| Topic | `import-events` (created on startup with 3 partitions if missing) |

## Setup

```bash
cd import-service
python3 -m venv venv
source venv/bin/activate
pip install -r requirements-dev.txt     # requirements.txt alone is enough to run
```

The database itself must exist:

```sql
CREATE DATABASE IF NOT EXISTS quorum_import;
```

Kafka comes from the `docker-compose.yml` in the repository root:

```bash
docker compose up -d
```

## Configuration

Environment variables only.

| Variable | Default |
|---|---|
| `KAFKA_BOOTSTRAP` | `localhost:9092` |
| `DB_HOST` | `localhost` |
| `DB_PORT` | `3306` |
| `DB_USER` | `root` |
| `DB_NAME` | `quorum_import` |
| `DB_PASSWORD` | none, required; the service refuses to start without it |

## Run

```bash
export DB_PASSWORD='...'
uvicorn main:app --port 8090
```

Interactive API docs: http://localhost:8090/docs

If Kafka is down at startup the service still starts and logs a warning;
uploads then answer 503 (`PUBLISH_FAILED`) until it is back.

### Docker

```bash
docker build -t import-service .
docker run --rm -p 8090:8090 \
  -e DB_PASSWORD='...' -e DB_HOST=host.docker.internal \
  -e KAFKA_BOOTSTRAP=host.docker.internal:9092 \
  --add-host host.docker.internal:host-gateway \
  import-service
```

## Endpoints

```bash
# 1. Shortlist: one column of student IDs
curl -F file=@samples/shortlist_valid.xlsx -F roundId=12 http://localhost:8090/imports/shortlist

# 2. Attendance: one column of student IDs who were present
curl -F file=@samples/attendance.csv -F sessionId=7 http://localhost:8090/imports/attendance

# 3. Timetable: sheets `enrolments` and `slots` (.xlsx only)
curl -F file=@samples/timetable.xlsx -F semester=2026-ODD http://localhost:8090/imports/timetable

# 4. A saved report
curl http://localhost:8090/imports/<uploadId>

# 5. Blank template: shortlist | attendance | timetable
curl -o timetable_template.xlsx http://localhost:8090/imports/templates/timetable
```

| Status | Meaning |
|---|---|
| 200 | `PUBLISHED`: Kafka confirmed delivery |
| 400 | File refused (wrong type, over 5 MB, unreadable, header or sheet not found); nothing saved |
| 404 | Unknown upload id or template |
| 422 | `NOTHING_TO_PUBLISH`: no row was accepted; the report is saved |
| 503 | `PUBLISH_FAILED`: Kafka error; the report is saved |

### Report

Returned by every upload endpoint and by `GET /imports/{uploadId}`.

```json
{
  "uploadId": "0b8f6c0e-6f0e-4d0c-9d57-3c1f6f6d2a11",
  "type": "SHORTLIST",
  "targetId": "12",
  "fileName": "shortlist_with_errors.xlsx",
  "totalRows": 8,
  "acceptedCount": 4,
  "rejectedCount": 4,
  "rejected": [{"row": 7, "value": "99000004", "reason": "NOT_10_DIGITS"}],
  "status": "PUBLISHED",
  "uploadedAt": "2026-10-12T11:48:00"
}
```

`row` is the row number shown in Excel. For timetables, `totalRows` and the
counts cover both sheets and each rejected entry also has a
`"sheet": "slots" | "enrolments"` field.

## File rules

- `.xlsx` and `.csv` only, at most 5 MB.
- The header row is searched for in the first 10 rows, so title rows above it are fine.
- Headers are matched case-insensitively, ignoring spaces and underscores.
  The student ID column may be called `studentId`, `student id`, `id`,
  `reg no`, `registration number` or `roll no`.
- Fully empty rows are ignored.
- Single-column uploads read the first sheet of the workbook.

## Validation

Student IDs are normalised first (`9900000001.0` and `" 99000 00001 "` both
become `9900000001`) and must then be exactly 10 digits.

| Reason | Where | Meaning |
|---|---|---|
| `EMPTY` | student ID | no value |
| `NOT_NUMERIC` | student ID | contains something other than digits |
| `NOT_10_DIGITS` | student ID | digits, but not 10 of them |
| `DUPLICATE` | student ID | seen before; the first occurrence is kept. In `enrolments` a duplicate is a repeated (studentId, sectionCode) pair |
| `EMPTY_SECTION_CODE` | slots, enrolments | no sectionCode |
| `INVALID_DAY` | slots | dayOfWeek is not MONDAY..SATURDAY |
| `BAD_TIME_FORMAT` | slots | startTime / endTime is not HH:mm (`9:00` and Excel time cells are accepted and written as `09:00`) |
| `START_NOT_BEFORE_END` | slots | startTime >= endTime |
| `UNKNOWN_SECTION` | enrolments | sectionCode has no accepted row in the `slots` sheet |

## Kafka events

Topic `import-events`, JSON values, no type headers. The producer uses
`acks=all` and `enable.idempotence=true`, and an upload is only marked
`PUBLISHED` after the broker has confirmed every message.

| Event | Key |
|---|---|
| `SHORTLIST_UPLOADED` | roundId |
| `ATTENDANCE_UPLOADED` | sessionId |
| `TIMETABLE_UPLOADED` | semester |

```json
{"eventId":"uuid","eventType":"SHORTLIST_UPLOADED","uploadId":"uuid","roundId":12,"studentIds":["9900000001"],"uploadedAt":"2026-10-12T11:48:00"}
{"eventId":"uuid","eventType":"ATTENDANCE_UPLOADED","uploadId":"uuid","sessionId":7,"studentIds":["9900000001"],"uploadedAt":"2026-10-12T11:48:00"}
{"eventId":"uuid","eventType":"TIMETABLE_UPLOADED","uploadId":"uuid","semester":"2026-ODD","part":1,"totalParts":1,"slots":[{"sectionCode":"S12","courseName":"Cloud Native","dayOfWeek":"MONDAY","startTime":"10:00","endTime":"10:50","room":"C-301"}],"enrolments":[{"studentId":"9900000001","sectionCode":"S12"}],"uploadedAt":"2026-10-12T11:48:00"}
```

A timetable event that would exceed 900 KB is split into several messages with
the same `uploadId` and key (so they stay in order on one partition), numbered
`part` 1..`totalParts`. The slots are in part 1 only; later parts carry
`"slots": []`. Each part has its own `eventId`.

## Tests and samples

```bash
pytest
```

The tests need neither MySQL nor Kafka: the endpoint tests use SQLite and a
fake publisher.

`samples/` holds ready-made upload files (`python samples/generate_samples.py`
regenerates them). All IDs in them are made up; never commit real student data.

## Code layout

| File | Purpose |
|---|---|
| `main.py` | routes |
| `parsing.py` | read .xlsx/.csv, find the header row, keep Excel row numbers |
| `validation.py` | per-row rules and rejection reasons |
| `publisher.py` | event shapes, timetable splitting, Kafka producer |
| `models.py` | report table and queries |
| `config.py` | environment variables and limits |
