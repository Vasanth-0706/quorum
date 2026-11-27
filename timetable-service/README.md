# timetable-service

Knows every student's class timetable and works out which class slots clash with the placement
sessions of the rounds they are shortlisted for. The result goes to the Kafka topic `clash-events`,
where attendance-service picks it up. The service never calls another service directly.

| | |
|---|---|
| Port | 8083 |
| Stack | Java 17, Spring Boot 4.1.1, Spring Data JPA, spring-kafka |
| Database | MySQL `quorum_timetable` (created on first start, tables by Hibernate `ddl-auto=update`) |
| Consumes | `import-events` (TIMETABLE_UPLOADED, SHORTLIST_UPLOADED), `drive-events` (ROUND_CREATED, SESSION_*) |
| Produces | `clash-events` (CLASHES_COMPUTED, key = roundId) through a transactional outbox |
| Dead letters | `import-events.DLT`, `drive-events.DLT` |

## Setup

1. Kafka and MySQL (repository root): `docker compose up -d kafka kafka-init mysql`. The whole system
   in containers is one command, see "Run Quorum" in the root README.
2. MySQL 8 on `localhost:3306` (the compose one is published on `MYSQL_HOST_PORT`, default 3306).
3. Credentials: export them, or copy the root `.env.example` to `.env` (git-ignored). The service
   reads `.env` from its own folder or the repository root. Real environment variables win.

| Variable | Default | Notes |
|---|---|---|
| `DB_HOST` | `localhost` | |
| `DB_PORT` | `3306` | |
| `DB_USERNAME` | `root` | |
| `DB_PASSWORD` | none | Required. If it is missing, MySQL answers `Access denied` at startup. |
| `KAFKA_BOOTSTRAP` | `localhost:9092` | |

Optional (in `application.properties`): `quorum.outbox.relay-interval` (500ms), `quorum.outbox.send-timeout`
(10s), `quorum.outbox.batch-size` (100), `quorum.kafka.retry.interval-ms` (1000), `quorum.kafka.retry.attempts` (2).

## Run and test

```bash
./mvnw spring-boot:run
./mvnw verify     # H2 + an in-JVM embedded Kafka broker; no MySQL or Docker needed
```

The tests include the hand-checked oracle (`ClashCalculatorTest` with `TimetableFixture`: 3 sections,
6 slots, 5 students, a two-day drive) and the full flow on H2 (`TimetableFlowTest`).

## Temporary auth (TEMPORARY, until proper login)

Every endpoint here is a read, so none needs headers today. The same interceptor as placement-service
is installed: any future write (`POST`/`PUT`/...) must send `X-User-Id` and `X-User-Role`
(`COORDINATOR` or `DIRECTOR`), else 401 (missing) or 403 (wrong role).

## Endpoints (read-only)

| Method | Path | Success | Errors |
|---|---|---|---|
| GET | `/timetable/semesters` | 200, active first, then newest applied | |
| GET | `/timetable/students/{studentId}/slots?date=YYYY-MM-DD` | 200, the student's slots on that date's weekday in the active semester | 400 (studentId not 10 digits, date missing or not ISO) |
| GET | `/clashes?roundId=&date=` | 200, a list: one entry for `date`, or one per date the round has sessions on when `date` is left out | 400 (roundId missing or not positive, bad date) |
| GET | `/outbox?limit=20` | 200, `{pending, latest[]}` of the clash-events outbox | |

`/timetable/students/.../slots` with no active semester answers 200 with `"semester": null` and no slots.

`/clashes` is computed from the current data. `lastPublished` shows what was last queued on
clash-events for that roundId + date, and `current` says whether it still matches:

```json
[{"roundId":11,"date":"2026-12-04","semester":"2026-ODD","windowStart":"11:00:00","windowEnd":"12:30:00",
  "clashes":[{"studentId":"9900000101","sectionCode":"CS101","courseName":"Algorithms","dayOfWeek":"FRIDAY",
              "slotStart":"12:00:00","slotEnd":"13:00:00","room":"A-101"}],
  "lastPublished":{"eventId":"…","queuedAt":"2026-11-27T05:00:00Z","clashCount":1,"current":true}}]
```

Errors use the same JSON shape as placement-service: `{status, error, message, errors[{field, message}]}`.

## Events consumed

Messages are read as plain JSON. Unknown fields are ignored. `schemaVersion` and `occurredAt` are
optional (import-events has neither). Other event types (ATTENDANCE_UPLOADED, DRIVE_SCHEDULED, ...)
are skipped. Every applied eventId is stored in `processed_event`, so a redelivery changes nothing.

| Topic / eventType | Effect |
|---|---|
| import-events `TIMETABLE_UPLOADED` | Buffer the part in `timetable_part`. When all `totalParts` parts of the `uploadId` are in, in ONE transaction: replace that semester's slots and enrolments, make it the active semester, delete the buffered parts, recompute every round. A repeated part (same uploadId + part) or a part of an already applied upload is ignored. Slots are read from part 1 only. |
| import-events `SHORTLIST_UPLOADED` | Replace the round's shortlist (`shortlist_entry`) with `studentIds`, recompute that round |
| drive-events `SESSION_SCHEDULED` / `SESSION_UPDATED` / `SESSION_CANCELLED` | Upsert `session_view` by sessionId, recompute that round |
| drive-events `ROUND_CREATED` | Upsert `round_view` |

Both topics are read by one listener on one thread, so changes are applied one at a time and a
recomputation always sees the previous changes. Run a single instance of the service.

A message that is not JSON goes straight to `<topic>.DLT`. So does one that can never be applied:
a missing field, a student ID that is not 10 digits, an unknown day, a slot whose start is not before
its end, or a part number above totalParts. Other failures are retried 2 times, 1 s apart, then sent
to the DLT. The failure details are in the `kafka_dlt-exception-*` headers.

## Clash rule

For a roundId and a date:

- window = [earliest session start, latest session end] over **all** sessions of that round on that
  date, cancelled ones included;
- for each shortlisted student: their sections in the active semester, then the slots of those
  sections whose `dayOfWeek` is the date's weekday and that overlap the window:
  `slotStart < windowEnd AND windowStart < slotEnd`. Touching edges do not clash
  (10:00-11:00 vs a window from 11:00).

The logic is a pure function, `ClashCalculator`, with no database or Spring dependency.

## Event produced (`clash-events`, key = roundId)

```json
{"eventId":"…","eventType":"CLASHES_COMPUTED","schemaVersion":1,"occurredAt":"2026-11-27T05:00:00.000Z",
 "roundId":11,"date":"2026-12-04","windowStart":"11:00:00","windowEnd":"12:30:00","semester":"2026-ODD",
 "clashes":[{"studentId":"9900000101","sectionCode":"CS101","courseName":"Algorithms","dayOfWeek":"FRIDAY",
             "slotStart":"12:00:00","slotEnd":"13:00:00","room":"A-101"}]}
```

- It is a **full replacement** for that roundId + date: consumers drop what they had and keep this list.
- It is recomputed when the round's shortlist, any of its sessions, or the active timetable changes,
  and queued only if the result differs from the last one for that roundId + date (SHA-256 of
  semester, window and the sorted clash list, kept in `clash_result`).
- A first result with no clashes is not published, because it says nothing new.
- If the round no longer has a session on a date it was published for (a session moved to another
  day), an empty list with `windowStart`/`windowEnd` = null is published for the old date.
- `semester` is null when no timetable has been applied yet.
- Delivery uses the outbox: the event row is written in the same transaction as the change that
  caused it. A relay sends rows in id order every 500 ms with `acks=all` and idempotence, stopping
  at the first failure. Delivery is at-least-once, so consumers de-duplicate on eventId.

## Tables

`semester` (code PK, active, upload_id, slot_count, enrolment_count, applied_at), `timetable_slot`,
`enrolment` (unique semester + student + section), `timetable_upload` (BUFFERING | APPLIED),
`timetable_part` (unique upload_id + part), `shortlist_entry` (unique round + student),
`session_view`, `round_view`, `clash_result` (unique round + date), `processed_event`, `outbox_event`.
