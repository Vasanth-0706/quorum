# placement-service

Coordinators plan placement drives here: a **drive** has **rounds** (Online test, Technical, HR, ...)
and each round has **sessions** (date, time, room). Every change is published to the Kafka topic
`drive-events` through a transactional outbox. The service never calls another service directly.

| | |
|---|---|
| Port | 8081 |
| Stack | Java 17, Spring Boot 4.1.1, Spring Data JPA, spring-kafka, Bean Validation |
| Database | MySQL `quorum_placement` (created on first start, tables by Hibernate `ddl-auto=update`) |
| Topic | produces `drive-events` (key = driveId) |

## Setup

1. Kafka (repository root): `docker compose up -d`
2. MySQL 8 running on `localhost:3306`.
3. Credentials. Either export them or copy the root `.env.example` to `.env` (git-ignored):

```bash
cp ../.env.example ../.env     # then edit DB_PASSWORD
```

The service reads `.env` from its own folder or the repository root. Real environment variables win.

| Variable | Default | Notes |
|---|---|---|
| `DB_HOST` | `localhost` | |
| `DB_PORT` | `3306` | |
| `DB_USERNAME` | `root` | |
| `DB_PASSWORD` | none | Required. If it is missing, MySQL answers `Access denied` at startup. |
| `KAFKA_BOOTSTRAP` | `localhost:9092` | |

Optional tuning (in `application.properties`): `quorum.outbox.relay-interval` (500ms),
`quorum.outbox.send-timeout` (10s), `quorum.outbox.batch-size` (100), `quorum.outbox.relay.enabled` (true).

The service declares `drive-events` with 3 partitions. If a consumer started first and the broker
auto-created it with 1 partition, that still works; to get 3, create it before starting anything:

```bash
docker exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 \
  --create --if-not-exists --topic drive-events --partitions 3
```

## Run and test

```bash
./mvnw spring-boot:run
./mvnw verify          # unit + integration tests on in-memory H2; no MySQL, Kafka or Docker needed
```

## Temporary auth (TEMPORARY, until proper login)

Every write request (`POST`, `PUT`) must send:

- `X-User-Id`: any non-blank id (max 64 chars), else **401**
- `X-User-Role`: `COORDINATOR` or `DIRECTOR`; missing gives **401**, anything else **403**

`GET` requests need no headers. This is a placeholder and will be replaced by real login.

## Endpoints

| Method | Path | Body | Success | Errors |
|---|---|---|---|---|
| POST | `/drives` | `CreateDriveRequest` | 201 + `Location` | 400, 401, 403 |
| GET | `/drives` | | 200, newest first (id desc) | |
| GET | `/drives/{id}` | | 200, drive with rounds and their sessions | 400 (non-numeric id), 404 |
| POST | `/drives/{driveId}/rounds` | `CreateRoundRequest` | 201 | 400, 401, 403, 404 (drive), 409 (sequence taken) |
| GET | `/drives/{driveId}/rounds` | | 200, by sequence, each with sessions | 404 |
| POST | `/rounds/{roundId}/sessions` | `SessionRequest` | 201 | 400, 401, 403, 404 (round) |
| GET | `/rounds/{roundId}/sessions` | | 200, by date then start time | 404 |
| GET | `/sessions/{id}` | | 200 | 404 |
| PUT | `/sessions/{id}` | `SessionRequest` (reschedule) | 200 | 400, 401, 403, 404, 409 (cancelled) |
| POST | `/sessions/{id}/cancel` | | 200 | 401, 403, 404, 409 (already cancelled) |
| GET | `/outbox?limit=20` | | 200, `{pending, latest[]}` newest first (max 200) | |

Request bodies (times are `HH:mm` or `HH:mm:ss`, dates `YYYY-MM-DD`):

```jsonc
// CreateDriveRequest: all required; companyName and venue not blank (max 120); endTime after startTime
{"companyName":"Acme Corp","driveDate":"2026-12-01","startTime":"09:00","endTime":"17:00","venue":"Main Hall"}

// CreateRoundRequest: name not blank (max 60); sequence 1..99, unique within the drive
{"name":"Technical","sequence":2}

// SessionRequest: all required; room not blank (max 60); endTime after startTime.
// The date may differ from the drive date: a drive can span several days.
{"date":"2026-12-02","startTime":"09:30","endTime":"11:00","room":"Room 101"}
```

Session rules: a new session is `SCHEDULED`. A `CANCELLED` session cannot be rescheduled or
cancelled again (409). A reschedule that changes nothing returns 200 and publishes no event.

### Error format

Every error has the same JSON shape. `errors` lists field problems on a 400 and is empty otherwise.

```json
{"status":400,"error":"Bad Request",
 "message":"companyName must not be blank; endTime must be after startTime",
 "errors":[{"field":"companyName","message":"must not be blank"},
           {"field":"endTime","message":"must be after startTime"}]}
```

| Status | When |
|---|---|
| 400 | Bean Validation failed, malformed JSON, wrong value type (e.g. `"driveDate":"tomorrow"`), non-numeric id |
| 401 / 403 | Temporary auth headers missing / wrong role |
| 404 | Drive, round or session not found |
| 409 | Duplicate round sequence, or the session is cancelled |
| 500 | Unexpected error (logged); nothing is saved |

## Events (`drive-events`, key = driveId)

Compact JSON, no type headers. Common fields: `eventId` (UUID), `eventType`, `schemaVersion` (1),
`occurredAt` (ISO-8601 UTC, e.g. `2026-11-27T10:15:30.000Z`). Dates are `YYYY-MM-DD`, times `HH:mm:ss`.

| eventType | Written by | Fields |
|---|---|---|
| `DRIVE_SCHEDULED` | POST /drives | driveId, companyName, driveDate, startTime, endTime, venue |
| `ROUND_CREATED` | POST /drives/{id}/rounds | driveId, roundId, name, sequence |
| `SESSION_SCHEDULED` | POST /rounds/{id}/sessions | driveId, roundId, sessionId, date, startTime, endTime, room, status |
| `SESSION_UPDATED` | PUT /sessions/{id} | same as above |
| `SESSION_CANCELLED` | POST /sessions/{id}/cancel | same as above, status = `CANCELLED` |

Example:

```json
{"eventId":"f6f9b7c9-60c4-40ed-b19d-68edf74253c1","eventType":"SESSION_CANCELLED","schemaVersion":1,
 "occurredAt":"2026-11-27T04:19:57.437Z","driveId":1,"roundId":2,"sessionId":2,"date":"2026-12-01",
 "startTime":"11:00:00","endTime":"12:00:00","room":"Room 101","status":"CANCELLED"}
```

## Transactional outbox

- The entity change and its `outbox_event` row are written in **one** `@Transactional` method;
  if either fails, neither is saved. Services never call `KafkaTemplate` themselves.
- `OutboxRelay` runs every 500 ms (fixed delay). It reads unpublished rows in id order, sends each
  with its key and waits for the broker (`.get` with a 10 s timeout). On success it sets
  `published_at`; on failure it increments `attempts`, stores `last_error` and **stops the batch**,
  so no later event overtakes an earlier one. The next pass retries the same row.
- Producer: `acks=all`, `enable.idempotence=true`.
- Delivery is at-least-once (a row can be sent again if marking it as published fails), so
  consumers de-duplicate on `eventId`.
- The relay assumes one running instance of placement-service.

Table `outbox_event`: `id`, `aggregate_key` (driveId), `topic`, `event_type`, `payload` (the JSON
message), `created_at`, `published_at` (null until sent), `attempts`, `last_error`.

Check it with `GET /outbox` or in MySQL:

```sql
SELECT id, event_type, aggregate_key, published_at, attempts, last_error
FROM quorum_placement.outbox_event ORDER BY id DESC LIMIT 20;
```

## Tables

`drive`, `drive_round` (unique `drive_id + sequence`), `drive_session`, `outbox_event`.
