# attendance-service

Will decide On-Duty (OD) attendance. For now it keeps a local copy of placement sessions, built
from the Kafka topic `drive-events`, in table `session_view`. Later prompts add the OD logic
on top of it. The service never calls another service directly.

| | |
|---|---|
| Port | 8082 |
| Stack | Java 17, Spring Boot 4.1.1, Spring Data JPA, spring-kafka |
| Database | MySQL `quorum_attendance` (created on first start, tables by Hibernate `ddl-auto=update`) |
| Consumes | `drive-events` (consumer group `attendance-service`) |
| Dead letters | `drive-events.DLT` (created on startup, 3 partitions) |

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

Optional: `quorum.kafka.retry.interval-ms` (1000) and `quorum.kafka.retry.attempts` (2), the
retries before a failing message goes to the DLT.

## Run and test

```bash
./mvnw spring-boot:run
./mvnw verify    # H2 + an in-JVM embedded Kafka broker; no MySQL or Docker needed
```

## Endpoints

None yet. When write endpoints arrive they use the **TEMPORARY** auth headers `X-User-Id` and
`X-User-Role` (COORDINATOR or DIRECTOR), answering 401/403 when missing or wrong, until proper login exists.

## Events consumed (`drive-events`)

Messages are read as plain JSON (`JsonNode`), not as typed classes. The listener switches on `eventType`:

| eventType | Effect |
|---|---|
| `SESSION_SCHEDULED`, `SESSION_UPDATED`, `SESSION_CANCELLED` | upsert `session_view` by `sessionId` |
| `DRIVE_SCHEDULED`, `ROUND_CREATED` | recorded as processed; nothing stored yet |
| anything else | skipped, not recorded |

Unknown fields are ignored. `schemaVersion` and `occurredAt` are optional. Ids may be numbers or
numeric strings.

### Idempotency

- `processed_event` (`event_id` primary key, `event_type`, `processed_at`) stores every applied eventId.
  A redelivered event finds its id there and changes nothing.
- `session_view` is upserted by `sessionId`, and the eventId row is written in the same transaction.

### Errors and the DLT

- The value deserializer is `ErrorHandlingDeserializer` delegating to `JacksonJsonDeserializer`, so a
  message that is not JSON never blocks the partition. It goes straight to `drive-events.DLT`
  with its original bytes and key.
- A message that is JSON but cannot be applied (missing `eventId`, `sessionId`, bad date, unknown
  status) raises `InvalidEventException` and goes straight to the DLT.
- Any other failure (e.g. the database is down) is retried 2 times, 1 s apart, then sent to the DLT.
- DLT records carry the failure in the `kafka_dlt-exception-*` headers.

```bash
docker exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic drive-events.DLT --from-beginning --property print.key=true --property print.headers=true
```

## Tables

`session_view`: `session_id` (PK), `round_id`, `drive_id`, `session_date`, `start_time`, `end_time`,
`room`, `status` (SCHEDULED | CANCELLED), `last_event_id`, `updated_at`.

`processed_event`: `event_id` (PK), `event_type`, `processed_at`.

```sql
SELECT * FROM quorum_attendance.session_view ORDER BY session_id;
```
