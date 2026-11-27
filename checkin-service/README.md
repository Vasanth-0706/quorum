# checkin-service

Records who attended a placement session. Three kinds of mark: an **RFID tap** on a reader fixed in
a room, a **manual** present/absent by a coordinator or director, and a **file** upload of attendance
from import-service. Every new mark is published as `ATTENDANCE_MARKED` on `checkin-events`;
attendance-service turns the marks into OD. The service never calls another service.

| | |
|---|---|
| Port | 8084 |
| Stack | Java 17, Spring Boot 4.1.1, Spring Data JPA, spring-kafka, Bean Validation, spring-security-crypto (BCrypt only) |
| Database | MySQL `quorum_checkin` (created on first start, tables by Hibernate `ddl-auto=update`) |
| Consumes | `drive-events` (sessions), `import-events` (ATTENDANCE_UPLOADED); consumer group `checkin-service` |
| Produces | `checkin-events` (ATTENDANCE_MARKED, key = sessionId, 3 partitions) through a transactional outbox |
| Dead letters | `drive-events.DLT`, `import-events.DLT` |
| Reader firmware | [`hardware/esp32-rc522-reader`](../hardware/esp32-rc522-reader/README.md) |

## Setup

1. Kafka (repository root): `docker compose up -d`
2. MySQL 8 running on `localhost:3306`.
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
| `quorum.time-zone` | `UTC` | zone in which session dates and times are read, e.g. `Asia/Kolkata`. Set it to the zone placement-service's times are in, or taps will not find their session |
| `quorum.checkin.max-clock-skew` | `5m` | a reader's own `tappedAt` may be at most this far from the server time |
| `quorum.outbox.relay-interval` / `send-timeout` / `batch-size` | `200ms` / `10s` / `100` | |
| `quorum.kafka.retry.interval-ms` / `attempts` | `1000` / `2` | retries before a message goes to the DLT |

Readers on the LAN reach the service at `http://<laptop-LAN-IP>:8084`; Spring Boot listens on all
interfaces by default. Allow port 8084 through the laptop's firewall if the reader gets no answer.

## Run and test

```bash
./mvnw spring-boot:run
./mvnw verify    # H2 + an in-JVM embedded Kafka broker; no MySQL or Docker needed
```

| Test | Covers |
|---|---|
| `SessionMatcherTest` | pure rule: inside the session, inclusive start/end, cancelled sessions, overlapping and consecutive sessions |
| `CardUidsTest` | UID normalisation (case, `:` `-` spaces) and 4/7/10-byte length |
| `CheckinFlowTest` | the whole service on H2 with a clock in Asia/Kolkata: tap matching by room + local time, reader `tappedAt`, duplicate tap, tap after a manual absent, unknown card + enrolment from the unknown-tap list, no session, cancelled session keeps old marks but rejects new taps, missing/wrong/rotated/inactive device key, director-only devices, manual marks, FILE marks (already present, replay, session not known yet, cancelled), CSV import, outbox rows, 20 taps under 300 ms |
| `CheckinKafkaEndToEndTest` | embedded Kafka: session in on drive-events → tap → ATTENDANCE_MARKED read by a consumer configured like attendance-service's and checked with its rules; upload → FILE mark; non-JSON to `import-events.DLT` |
| `OutboxRelayTest` | relay ordering, stop at first failure, retries |

## Temporary auth (TEMPORARY, until proper login)

**People.** Write requests (`POST`, `PATCH`) except the tap must send:

- `X-User-Id`: any non-blank id (max 64 chars), else **401**
- `X-User-Role`: `COORDINATOR` or `DIRECTOR`; missing gives **401**, anything else **403**
- Device endpoints (`POST /devices`, `PATCH /devices/{id}`, `POST /devices/{id}/key`) need `DIRECTOR`, else **403**

`GET` requests need no headers. This is a placeholder and will be replaced by real login.

**Readers.** `POST /checkins/tap` ignores the X-User headers and needs `X-Device-Key`:

- The key is `<deviceId>.<secret>` and is shown **once**, by `POST /devices` (or `POST /devices/{id}/key`).
- Only a BCrypt hash of the secret is stored (`device.api_key_hash`). The deviceId part finds the
  row, so a tap costs at most one BCrypt check; after a successful check the SHA-256 of the secret
  is kept in memory, so later taps need no BCrypt (that keeps a tap far below 300 ms; measured
  ~100 ms for the first tap, ~15 ms after). A new key or a restart clears the memory.
- Missing, malformed or wrong key → **401** `INVALID_DEVICE_KEY`. Right key of a switched-off device → **403** `DEVICE_INACTIVE`.

## The tap

`POST /checkins/tap` with `X-Device-Key` and `{"cardUid":"04A31B2C"}` (optional `"tappedAt"`, ISO-8601 with offset):

1. Find the device by its key → its **room**.
2. Normalise the UID: upper-case hex, no `:` `-` or spaces, 4, 7 or 10 bytes (8, 14 or 20 hex digits).
3. Tap time = `tappedAt` if it is within `quorum.checkin.max-clock-skew` of the server clock, else **400**;
   without `tappedAt` it is the server time (the bundled firmware sends none). It is converted to
   `quorum.time-zone` → local date + time.
4. Card not enrolled → **404** `UNKNOWN_CARD`, and the UID is stored in `unknown_tap` (one row per UID
   with first/last seen, count, last device and room) for enrolment. This happens even without a session.
5. Find the session in that room (compared without case and surrounding spaces) on that local date
   whose `[startTime, endTime]` (both inclusive) contains the local time. No check-in window: any
   moment of the session counts. Several running → the one that started first.
   - none → **409** `NO_ACTIVE_SESSION`
   - only a CANCELLED one → **409** `SESSION_CANCELLED` (a cancelled session keeps its old marks)
6. The student already has a present latest mark, or has an earlier RFID tap in this session →
   **200** `ALREADY_MARKED`, no new mark, no event. (So a tap never undoes a coordinator's manual absent.)
7. Otherwise store a present `RFID` mark (`markedBy` = deviceId, `markedAt` = tap time), queue
   ATTENDANCE_MARKED → **200** `MARKED`.

Every write (tap, manual mark, consumed event) runs under one in-process lock, so two taps of the
same card a few ms apart give MARKED and ALREADY_MARKED, never two marks. Run a single instance.

## Endpoints

| Method | Path | Auth | Success | Errors |
|---|---|---|---|---|
| POST | `/checkins/tap` | X-Device-Key | 200 `MARKED` / `ALREADY_MARKED` | 400 (`cardUid` missing/bad, `tappedAt` out of range), 401, 403, 404 `UNKNOWN_CARD`, 409 `NO_ACTIVE_SESSION` / `SESSION_CANCELLED` |
| POST | `/sessions/{sessionId}/marks` | COORDINATOR / DIRECTOR | 201 new mark; 200 if the latest mark already says the same (nothing stored) | 400, 401, 403, 404 `SESSION_NOT_FOUND`, 409 `SESSION_CANCELLED` |
| GET | `/sessions/{sessionId}/marks?history=false` | | 200, latest mark per student by studentId; `history=true` every version, newest first per student | 400 |
| GET | `/sessions?date=YYYY-MM-DD` | | 200, sessions known from drive-events (date optional) | 400 |
| POST | `/cards` | COORDINATOR / DIRECTOR | 201 enrolled; 200 already enrolled to this student | 400 `INVALID_CARD_UID` / bad studentId, 401, 403, 409 `CARD_IN_USE` |
| POST | `/cards/import` | COORDINATOR / DIRECTOR | 200 `{imported, unchanged, total}` | 400 `CSV_INVALID` with every bad line in `errors[]` (nothing stored), 401, 403 |
| GET | `/cards?studentId=` | | 200, enrolled cards | |
| GET | `/cards/unknown-taps` | | 200, unenrolled UIDs, most recent first | |
| POST | `/devices` | DIRECTOR | 201 with the plain `apiKey` (shown once) | 400, 401, 403, 409 `DEVICE_EXISTS` |
| POST | `/devices/{deviceId}/key` | DIRECTOR | 200 with a new `apiKey`; the old key stops working | 401, 403, 404 `DEVICE_NOT_FOUND` |
| PATCH | `/devices/{deviceId}` | DIRECTOR | 200, `{"active":false}` switches a reader off, `{"room":"..."}` moves it | 400, 401, 403, 404 |
| GET | `/devices` | | 200, devices without keys, with `lastSeenAt` | |
| GET | `/outbox?limit=20` | | 200, `{pending, latest[]}` of the checkin-events outbox | |

```jsonc
// POST /devices   (X-User-Id, X-User-Role: DIRECTOR)
{"deviceId":"R-HALL-A","room":"Hall A"}
// 201
{"deviceId":"R-HALL-A","room":"Hall A","active":true,"apiKey":"R-HALL-A.EXAMPLEexampleEXAMPLEexample12",
 "note":"Store this key now; it is not shown again. Send it as the X-Device-Key header."}

// POST /checkins/tap   (X-Device-Key)  →  200
{"result":"MARKED","cardUid":"04A31B2C","studentId":"9900000001","sessionId":41,"roundId":7,"room":"Hall A",
 "deviceId":"R-HALL-A","markId":"0b8e…","markedAt":"2026-12-07T04:15:00Z","tappedAt":"2026-12-07T04:15:00Z"}

// 404
{"status":404,"error":"Not Found","code":"UNKNOWN_CARD","message":"Card DEADBEEF is not enrolled; it is listed in GET /cards/unknown-taps","errors":[]}

// POST /sessions/41/marks   (X-User-Id, X-User-Role)
{"studentId":"9900000001","present":false}

// POST /cards/import   multipart/form-data, part "file":
studentId,cardUid
9900000001,04:A3:1B:2C
9900000002,04A31B2C5D6E80
```

The CSV header line is optional; blank lines are skipped; UIDs are normalised like the tap. A card
already enrolled to the same student counts as `unchanged`; to another student it is an error.

Errors share one JSON shape, like the other services plus a `code`:
`{status, error, code, message, errors[{field, message}]}`. `code` is the specific code from the
tables above, or the status name (`BAD_REQUEST`, `UNAUTHORIZED` …) when there is none.

| Status | When |
|---|---|
| 400 | Bean Validation failed, malformed JSON, bad UID, studentId not 10 digits, bad CSV, `tappedAt` out of range |
| 401 | X-User headers missing (writes), X-Device-Key missing or wrong (tap) |
| 403 | wrong role, non-director on device endpoints, inactive device |
| 404 | `UNKNOWN_CARD`, `SESSION_NOT_FOUND`, `DEVICE_NOT_FOUND` |
| 409 | `NO_ACTIVE_SESSION`, `SESSION_CANCELLED`, `CARD_IN_USE`, `DEVICE_EXISTS` |
| 500 | Unexpected error (logged); nothing is saved |

## Marks

`mark` rows are never updated. Each new mark for a session + student gets the next `version`; the
highest version is the latest. Each has a UUID `markId` and is published exactly once (at least
once on the wire, see the outbox) as ATTENDANCE_MARKED.

| Source | Created by | markedBy | markedAt |
|---|---|---|---|
| `RFID` | a tap | deviceId | tap time |
| `MANUAL` | `POST /sessions/{id}/marks` | X-User-Id | server time |
| `FILE` | import-events ATTENDANCE_UPLOADED | `import-service` | time stored (uploadedAt has no offset) |

Corrections with the 14-day coordinator rule and reasons belong to attendance-service (`POST /corrections`).

## Events consumed

Messages are read as plain JSON. Unknown fields are ignored; `schemaVersion` and `occurredAt` are
optional (import-events has neither). Other event types are skipped. Every applied eventId is stored
in `processed_event`, so a redelivery changes nothing.

| Topic / eventType | Effect |
|---|---|
| drive-events `SESSION_SCHEDULED` / `SESSION_UPDATED` / `SESSION_CANCELLED` | upsert `session_view` by sessionId (room, date, times, status), then apply uploads waiting for that session |
| drive-events `DRIVE_SCHEDULED`, `ROUND_CREATED` | skipped |
| import-events `ATTENDANCE_UPLOADED` | FILE marks, below |
| import-events `SHORTLIST_UPLOADED`, `TIMETABLE_UPLOADED` | skipped |

**FILE marks.** For each listed student (10-digit IDs; one bad ID refuses the whole message):

- markId = name-based UUID of `FILE:<uploadId>:<studentId>`, so the same upload never stores a mark twice.
- latest mark already present → no new mark (nothing would change). Students not listed are left as they are.
- session CANCELLED → the upload is skipped (logged): a cancelled session takes no new marks.
- session not received yet → the upload waits in `pending_file_upload` and is applied when the session arrives.

### Errors and the DLT

- Value deserializer: `ErrorHandlingDeserializer` → `JacksonJsonDeserializer` (JsonNode, no type headers).
  A message that is not JSON goes straight to `<topic>.DLT` with its original bytes and key.
- JSON that can never be applied (missing `eventId` or `sessionId`, student ID not 10 digits, bad
  date or time, unknown status) raises `InvalidEventException` and goes straight to the DLT.
- Any other failure (e.g. the database is down) is retried 2 times, 1 s apart, then sent to the DLT.
- DLT records carry the failure in the `kafka_dlt-exception-*` headers.

## Event produced (`checkin-events`, key = sessionId)

```json
{"eventId":"2f0c…","eventType":"ATTENDANCE_MARKED","schemaVersion":1,"occurredAt":"2026-12-07T04:15:00.012Z",
 "sessionId":41,"roundId":7,"studentId":"9900000001","present":true,"source":"RFID","markedBy":"R-HALL-A",
 "markedAt":"2026-12-07T04:15:00.000Z","markId":"0b8e5d7c-…"}
```

Keyed by sessionId, so the marks of one session stay in order. The outbox works like the other
services': the row is written in the same transaction as the mark, a relay sends rows in id order
every 200 ms (`acks=all`, idempotence) and stops at the first failure. Delivery is at-least-once;
attendance-service de-duplicates on eventId and on markId.

```bash
docker exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic checkin-events --from-beginning --property print.key=true
```

## Tables

| Table | Content |
|---|---|
| `device` | device_id (PK), room, api_key_hash (BCrypt), active, created_by/at, last_seen_at |
| `card` | card_uid (PK, upper-case hex), student_id, enrolled_by/at |
| `unknown_tap` | card_uid (PK), first/last seen, tap_count, last device and room; deleted when the card is enrolled |
| `mark` | every mark, never updated: unique mark_id, unique session + student + version |
| `session_view` | sessions from drive-events (PK session_id), index on date + room |
| `pending_file_upload` | uploads waiting for their session |
| `processed_event`, `outbox_event` | idempotency and the checkin-events outbox |

```sql
SELECT session_id, student_id, version, present, source, marked_by, marked_at
FROM quorum_checkin.mark ORDER BY session_id, student_id, version;
```

## Limits

- Sessions that cross midnight are not matched (placement sessions are within one day).
- A lost card is not blocked: enrol the new card; the old UID still maps to the student.
