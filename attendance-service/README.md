# attendance-service

Decides On-Duty (OD) attendance. It keeps local copies of the placement sessions (`drive-events`),
the clash lists computed by timetable-service (`clash-events`) and the attendance marks from
checkin-service (`checkin-events`: RFID taps, manual marks and file uploads), and turns them into OD records for
the class slots a student missed. Every grant and revoke is published to `od-events`.
No faculty in the loop: Quorum decides OD directly. The service never calls another service.

| | |
|---|---|
| Port | 8082 |
| Stack | Java 17, Spring Boot 4.1.1, Spring Data JPA, spring-kafka, Bean Validation |
| Database | MySQL `quorum_attendance` (created on first start, tables by Hibernate `ddl-auto=update`) |
| Consumes | `drive-events`, `clash-events`, `checkin-events`; `import-events` only if the TEMPORARY file bridge is switched on (off by default); consumer group `attendance-service` |
| Produces | `od-events` (OD_GRANTED / OD_REVOKED, key = studentId) through a transactional outbox |
| Dead letters | `drive-events.DLT`, `clash-events.DLT`, `checkin-events.DLT` (and `import-events.DLT` with the bridge on) |

## Setup

1. Kafka and MySQL (repository root): `docker compose up -d kafka kafka-init mysql`. The whole system
   in containers is one command, see "Run Quorum" in the root README.
2. MySQL 8 on `localhost:3306` (the compose one is published on `MYSQL_HOST_PORT`, default 3306).
3. Credentials. Either export them or copy the root `.env.example` to `.env` (git-ignored).
   The service reads `.env` from its own folder or the repository root. Real environment variables win.

| Variable | Default | Notes |
|---|---|---|
| `DB_HOST` | `localhost` | |
| `DB_PORT` | `3306` | |
| `DB_USERNAME` | `root` | |
| `DB_PASSWORD` | none | Required. If it is missing, MySQL answers `Access denied` at startup. |
| `KAFKA_BOOTSTRAP` | `localhost:9092` | |

Optional (in `application.properties`):

| Property | Default | |
|---|---|---|
| `quorum.file-attendance-bridge.enabled` | `false` | TEMPORARY direct import-events path, see below. Leave off when checkin-service runs |
| `quorum.time-zone` (env `QUORUM_TIME_ZONE`) | `Asia/Kolkata` | decides "today" for the 14-day correction rule |
| `quorum.outbox.relay-interval` / `send-timeout` / `batch-size` | `500ms` / `10s` / `100` | |
| `quorum.kafka.retry.interval-ms` / `attempts` | `1000` / `2` | retries before a message goes to the DLT |

## Run and test

```bash
./mvnw spring-boot:run
./mvnw verify    # H2 + an in-JVM embedded Kafka broker; no MySQL or Docker needed
```

| Test | Covers |
|---|---|
| `OdCalculatorTest` | the pure decision: overlap only, absent, touching edges, waiting time, cancelled sessions, two rounds on one slot, revoke reasons |
| `OdFlowTest` | the whole service on H2 (with the opt-in file bridge on): no clash result, empty replacement, changed clash list, cancelled and moved sessions, corrections (day 14 / day 15 / director day 30), replaying every event, read API and CSV |
| `OdKafkaEndToEndTest` | embedded Kafka: sessions, clashes and checkin-service marks (a FILE mark delivered twice) in, one OD_GRANTED out, bad messages to `clash-events.DLT` / `checkin-events.DLT` |
| `CheckinHandoverTest` | default settings: a FILE mark from checkin-events grants OD; the same markId under a new eventId is stored once; RFID + FILE for one student give one OD |
| `FileBridgeDisabledTest` | by default import-events is neither subscribed nor applied |
| `DriveEventHandlerTest`, `DriveEventsKafkaTest`, `OutboxRelayTest` | session_view, drive-events DLT, relay ordering |

## Temporary auth (TEMPORARY, until proper login)

Write requests (`POST /corrections`) must send:

- `X-User-Id`: any non-blank id (max 64 chars), else **401**
- `X-User-Role`: `COORDINATOR` or `DIRECTOR`; missing gives **401**, anything else **403**

`GET` requests need no headers. This is a placeholder and will be replaced by real login.
(`X-Device-Key` is for RFID readers and belongs to checkin-service.)

## The OD decision

For one student, one round and one date:

1. The student is **present** if their **latest** mark in ANY session of that round on that date is present.
   There is no check-in time window. Cancelled sessions count: they keep their marks and therefore their OD.
2. **OD window** = [earliest session start of that round on that date (cancelled sessions included),
   end of the session the student attended]. Present in several sessions: the one that ends last.
   So a student who attended the second session also gets the time they waited.
3. OD is granted for every clash slot of that student in the **stored** clash result for that round +
   date that overlaps the window: `slotStart < windowEnd AND windowStart < slotEnd`, and is on the
   date's weekday. Touching edges do not overlap (a slot from 11:00 vs a window ending 11:00).
4. No stored clash result means no clashes, so no OD. (timetable-service does not publish a first
   result that has no clashes.)

The rule is a pure function, `OdCalculator`, with no database, Spring or clock.

An OD record is unique on **(studentId, date, sectionCode, slotStart)**. That key has no round, so
the service recomputes one **student + date** at a time, over every round that clashes with that
student that day. If two rounds justify the same slot, the lower roundId wins.

Recomputation compares what is justified now with what is stored:

- newly justified → **grant** (insert, or re-grant a revoked record)
- stored as granted but no longer justified → **revoke**, with the reason (no longer in the clash
  list / no longer marked present / no longer overlaps the window)
- justified with different details (another session, another slotEnd) → granted again with the new details
- unchanged → nothing, no event. Recomputing twice changes nothing.

Each grant/revoke bumps `od_record.version`, writes an `od_record_history` row and queues
OD_GRANTED / OD_REVOKED in the outbox, in the same transaction as the input that caused it.

What triggers a recomputation (only the affected students and date):

| Input | Recomputed |
|---|---|
| CLASHES_COMPUTED for round R, date D | every student in the old or new list, or holding OD from R on D |
| a mark or correction for session S, student X | X on S's date (nothing until S is known; done when S arrives) |
| SESSION_* for session S | every student clashing with S's round on its date, and on its old date if it moved |

## Endpoints

| Method | Path | Success | Errors |
|---|---|---|---|
| POST | `/corrections` | 201, the correction | 400, 401, 403 (role, or coordinator past 14 days), 404 (session unknown) |
| GET | `/corrections?sessionId=` | 200, newest first | 400 (sessionId missing / not positive) |
| GET | `/marks/sessions/{sessionId}?history=false` | 200, the latest mark per student; `history=true` lists every version | 400 |
| GET | `/od?date=YYYY-MM-DD` | 200, granted OD on that date, by student then slot | 400 (date missing / not ISO) |
| GET | `/od/students/{studentId}?from=&to=` | 200, the student's granted OD, by date then slot; both dates optional and inclusive | 400 (id not 10 digits, bad date, from after to) |
| GET | `/od/sessions/{sessionId}` | 200, granted OD based on that session | 400 |
| GET | `/od/export?date=` | 200 `text/csv` attachment `od-<date>.csv` | 400 |
| GET | `/outbox?limit=20` | 200, `{pending, latest[]}` of the od-events outbox | |

The three `/od` lists accept `includeRevoked=true` to also show REVOKED records.

```jsonc
// POST /corrections (headers X-User-Id, X-User-Role). All fields required; reason not blank, max 500.
{"sessionId":41,"studentId":"9900000001","present":false,"reason":"Left before the round started"}

// 201
{"id":1,"sessionId":41,"studentId":"9900000001","oldPresent":true,"newPresent":false,
 "reason":"Left before the round started","correctedBy":"coord-1","role":"COORDINATOR",
 "correctedAt":"2026-12-08T05:00:00Z","markId":"CORRECTION-…","markVersion":2}

// GET /od?date=2026-12-07
[{"studentId":"9900000001","date":"2026-12-07","sectionCode":"S12","courseName":"Cloud Native",
  "slotStart":"10:00:00","slotEnd":"10:50:00","sessionId":41,"roundId":7,
  "reason":"Attended session 41 (09:30-10:15) of placement round 7","status":"GRANTED","version":1,
  "updatedAt":"2026-12-07T04:30:00Z"}]
```

CSV (`/od/export`), RFC 4180, CRLF line ends; a cell starting with `= + - @` gets a leading `'` so a
spreadsheet does not run it as a formula:

```
studentId,date,course,section,slotStart,slotEnd,sessionId,reason
9900000001,2026-12-07,Cloud Native,S12,10:00,10:50,41,Attended session 41 (09:30-10:15) of placement round 7
```

Errors use the same JSON shape as the other services: `{status, error, message, errors[{field, message}]}`.

| Status | When |
|---|---|
| 400 | Bean Validation failed, malformed JSON, bad date or id, from after to |
| 401 / 403 | Temporary auth headers missing / wrong role |
| 403 | COORDINATOR correcting more than 14 days after the session date (message names the last allowed day) |
| 404 | Correction for a session attendance-service has not received |
| 500 | Unexpected error (logged); nothing is saved |

### Corrections (REQ-F12)

- No approval step. A **COORDINATOR** may correct while `today <= session date + 14 days`
  (day 14 allowed, day 15 refused); a **DIRECTOR** at any time. "Today" comes from the injected
  `Clock` in zone `quorum.time-zone`.
- A correction never overwrites: it adds a new `attendance_mark` version (source `CORRECTION`,
  `marked_by` = the X-User-Id), which becomes the latest mark, and a `correction` row with the old
  value (null if there was no mark), new value, actor, role, time and reason. OD is recomputed in
  the same transaction, so a correction to absent revokes the OD and publishes OD_REVOKED.
- A correction of a cancelled session is allowed: cancelled sessions keep their marks.

## Events consumed

Messages are read as plain JSON. Unknown fields are ignored; `schemaVersion` and `occurredAt` are
optional. Other event types are skipped. Every applied eventId is stored in `processed_event`, so
a redelivery changes nothing.

| Topic / eventType | Effect |
|---|---|
| drive-events `SESSION_SCHEDULED` / `SESSION_UPDATED` / `SESSION_CANCELLED` | upsert `session_view` by sessionId, recompute |
| drive-events `DRIVE_SCHEDULED`, `ROUND_CREATED` | recorded as processed; nothing stored |
| clash-events `CLASHES_COMPUTED` | REPLACE the clash list of roundId + date (`clash_entry`, header in `clash_result`), recompute. An empty list or `windowStart`/`windowEnd` = null means no clashes. |
| checkin-events `ATTENDANCE_MARKED` | append a mark (`markId` unique, a repeated markId is skipped), recompute. `source` must be RFID, MANUAL or FILE; `markedAt` ISO-8601 (no offset = UTC). |
| import-events `ATTENDANCE_UPLOADED` | only with the TEMPORARY bridge on (off by default), below |

**Latest mark wins** = the mark that arrived last (highest `version` per session + student), not
the largest `markedAt`: the sources do not share a clock, and import-service's `uploadedAt` has no
offset. checkin-events is keyed by sessionId, so marks of one session arrive in order.

### File uploads come from checkin-service (TEMPORARY bridge off by default)

checkin-service reads import-events `ATTENDANCE_UPLOADED` and publishes one `ATTENDANCE_MARKED`
(`source` = `FILE`, `markedBy` = `import-service`) per listed student whose latest mark is not
already present. attendance-service therefore no longer reads import-events:
`quorum.file-attendance-bridge.enabled` defaults to **false**.

Nothing is counted twice:

- A mark is stored once per `markId` (unique column, checked before insert). A redelivery with the
  same eventId is skipped by `processed_event`; the same mark under a new eventId (an outbox resend)
  is skipped by markId (`CheckinHandoverTest`, `OdKafkaEndToEndTest`).
- checkin-service's FILE markId is a name-based UUID of `FILE:<uploadId>:<studentId>`, so replaying
  an upload there produces the same markId.
- OD is a set of slots per student and date, recomputed from the latest marks. Two present marks
  for the same student (an RFID tap and a FILE row) are two versions, but still one OD per slot and
  one OD_GRANTED.

The old direct path, `FileAttendanceBridge`, is still there for running attendance-service without
checkin-service: set `quorum.file-attendance-bridge.enabled=true` and import-events is subscribed
again, every listed student counts as present (source `FILE`, `marked_by` = `import-service`,
`markId` = `FILE-<uploadId>-<studentId>`). **Do not switch it on while checkin-service runs**: its
markIds differ from checkin-service's, so each uploaded student would get two mark rows (still one
OD, but a confusing history). Delete the bridge once nobody needs that mode.

### Ordering and concurrency

All topics are read by one listener on one thread, and every write (events and corrections) runs
under one in-process lock taken outside its transaction. A correction and a clash recomputation for
the same student therefore never decide on each other's stale data. Run a single instance.

### Errors and the DLT

- The value deserializer is `ErrorHandlingDeserializer` delegating to `JacksonJsonDeserializer`, so a
  message that is not JSON goes straight to `<topic>.DLT` with its original bytes and key.
- A message that is JSON but can never be applied (missing `eventId` or `roundId`, a student ID that
  is not 10 digits, bad date or time, unknown day or source, `present` not a boolean) raises
  `InvalidEventException` and goes straight to the DLT. The whole message is refused; nothing of it is stored.
- Any other failure (e.g. the database is down) is retried 2 times, 1 s apart, then sent to the DLT.
- DLT records carry the failure in the `kafka_dlt-exception-*` headers.
- timetable-service and checkin-service also read import-events, so a malformed import-events
  message appears in `import-events.DLT` once per consumer group that reads it.

```bash
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic clash-events.DLT --from-beginning --property print.key=true --property print.headers=true
```

## Event produced (`od-events`, key = studentId)

```json
{"eventId":"…","eventType":"OD_GRANTED","schemaVersion":1,"occurredAt":"2026-12-07T04:30:00.000Z",
 "studentId":"9900000001","date":"2026-12-07","sectionCode":"S12","courseName":"Cloud Native",
 "slotStart":"10:00:00","slotEnd":"10:50:00","sessionId":41,
 "reason":"Attended session 41 (09:30-10:15) of placement round 7"}
```

`OD_REVOKED` has the same fields; `reason` says why it was revoked, `sessionId` is the session the
grant was based on. A re-grant with changed details is another OD_GRANTED for the same key.

The outbox works like placement-service's: the row is written in the same transaction as the OD
change, a relay sends rows in id order every 500 ms (`acks=all`, idempotence), stopping at the
first failure. Delivery is at-least-once, so consumers de-duplicate on eventId.

## Tables

| Table | Content |
|---|---|
| `session_view` | sessions from drive-events (PK session_id) |
| `clash_result` | latest CLASHES_COMPUTED header per round + date (unique), window, semester, clash_count |
| `clash_entry` | the clash list of each round + date, replaced as a whole |
| `attendance_mark` | every mark, never updated: unique mark_id, unique session + student + version |
| `correction` | who, when, old value, new value, reason, and the mark it created |
| `od_record` | current OD per (student_id, od_date, section_code, slot_start) (unique), status GRANTED / REVOKED, version |
| `od_record_history` | one immutable row per grant / revoke, with the outbox eventId |
| `processed_event`, `outbox_event` | idempotency and the od-events outbox |

```sql
SELECT student_id, od_date, section_code, slot_start, status, version, reason
FROM quorum_attendance.od_record ORDER BY od_date, student_id, slot_start;
```
