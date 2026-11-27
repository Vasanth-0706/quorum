# Quorum

Quorum grants On-Duty (OD) attendance to students for the class slots that clash with the campus
placement-drive sessions they attended. No faculty is in the loop: the system decides OD itself.

Independent services talk only through Kafka topics and each owns its own MySQL database:

| Service | Stack | Port | Database | Does |
|---|---|---|---|---|
| placement-service | Java 17, Spring Boot | 8081 | `quorum_placement` | drives, rounds, sessions → `drive-events` |
| attendance-service | Java 17, Spring Boot | 8082 | `quorum_attendance` | decides OD, corrections → `od-events` |
| timetable-service | Java 17, Spring Boot | 8083 | `quorum_timetable` | timetables, shortlists, clashes → `clash-events` |
| checkin-service | Java 17, Spring Boot | 8084 | `quorum_checkin` | RFID readers, cards, marks → `checkin-events` |
| import-service | Python 3.12, FastAPI | 8090 | `quorum_import` | Excel/CSV uploads → `import-events` |
| frontend | React + Vite, served by nginx | 5173 | | placement-office console |

Each service has its own README with endpoints, events and status codes.
[docs/live-verification.md](docs/live-verification.md) is a recorded end-to-end run of the whole system.

## Run Quorum

### Prerequisites

- Docker Engine 24+ with Compose v2 (`docker compose version`). Nothing else: Java, Maven, Python
  and Node are only needed to develop a service, not to run the system.
- About 4 GB of free RAM and 3 GB of disk for the images.
- Free host ports 5173, 8081–8084, 8090, 9092 and 3306 (MySQL's host port can be moved, below).

### Start

```bash
cp .env.example .env          # then set DB_PASSWORD to a password of your choice
docker compose up -d --build  # first build takes a few minutes (Maven and npm downloads)
docker compose ps             # wait until every service says (healthy)
```

`.env` is git-ignored; never commit it. `DB_PASSWORD` becomes MySQL's root password when the
`mysql-data` volume is first created, and every service reads it from the environment.
If a local MySQL already uses port 3306, add `MYSQL_HOST_PORT=3307` to `.env`; the services
themselves talk to MySQL inside the compose network, so nothing else changes.

Start-up order is handled by healthchecks: Kafka and MySQL → `kafka-init` (a one-shot container that
creates `drive-events`, `import-events`, `clash-events`, `checkin-events`, `od-events` with 3 partitions,
each with its `.DLT` topic, then exits 0) → the five services → the frontend. Every container runs
with `TZ=Asia/Kolkata`; attendance- and checkin-service also default `quorum.time-zone` to
Asia/Kolkata (override with `QUORUM_TIME_ZONE`).

| Host port | What |
|---|---|
| 5173 | the UI (nginx; `/api/<service>/…` is proxied to that service) |
| 8081 / 8082 / 8083 / 8084 | placement / attendance / timetable / checkin-service |
| 8090 | import-service (Swagger UI at http://localhost:8090/docs) |
| 9092 | Kafka for clients on the host (containers use `kafka:29092`) |
| 3306 (`MYSQL_HOST_PORT`) | MySQL, user `root`, password `DB_PASSWORD` |

Stop with `docker compose down` (data is kept in the `kafka-data` and `mysql-data` volumes) or
`docker compose down -v` to start again from empty. Logs: `docker compose logs -f checkin-service`.

### Open the UI

Open http://localhost:5173. The sidebar shows whether every service answers. At the bottom,
"Signed in as" picks Coordinator or Director and a user id; this is **TEMPORARY** (sent as the
`X-User-Id` / `X-User-Role` headers until real login). Readers can only be registered by a Director.

Demo path: Create drive → add rounds and sessions → Excel import (timetable, shortlist) →
Clashes → a session's Check-in page (mark by hand) → OD records → Corrections → Readers & cards.
Sample files with made-up student IDs are in `import-service/samples/`.

### Point the ESP32 reader at the laptop

The reader must reach checkin-service over Wi-Fi, so it needs the laptop's LAN address, not `localhost`.

1. Put the laptop and the ESP32 on the same 2.4 GHz Wi-Fi (a phone hotspot works).
2. Find the laptop's IP: `hostname -I | awk '{print $1}'` (e.g. `192.168.43.17`).
3. Check from a phone on that Wi-Fi: `http://<IP>:8084/devices` must answer with JSON. Docker
   publishes 8084 on all interfaces; if it still does not answer, allow it in the firewall
   (`sudo ufw allow 8084/tcp`).
4. Register the reader as Director (UI: Readers & cards → Register reader, room = the session's room)
   and copy the key; it is shown once.
5. In `hardware/esp32-rc522-reader/secrets.h` (copy of `secrets.example.h`, git-ignored) set
   `SERVER_URL "http://<IP>:8084"` and `DEVICE_KEY "<the key>"`, then upload the sketch.
   See [hardware/esp32-rc522-reader/README.md](hardware/esp32-rc522-reader/README.md).

### Smoke test (10 lines)

```bash
H='-H X-User-Id:coord-1 -H X-User-Role:COORDINATOR -H Content-Type:application/json'
curl -s $H -d '{"companyName":"Smoke","driveDate":"2026-11-30","startTime":"09:00","endTime":"17:00","venue":"Hall"}' localhost:8081/drives
curl -s $H -d '{"name":"Aptitude","sequence":1}' localhost:8081/drives/1/rounds          # use the ids returned above
curl -s $H -d '{"date":"2026-11-30","startTime":"09:30","endTime":"10:15","room":"Hall A"}' localhost:8081/rounds/1/sessions
curl -s -F file=@import-service/samples/timetable.xlsx -F semester=2026-ODD localhost:8090/imports/timetable
curl -s -F file=@import-service/samples/shortlist_valid.xlsx -F roundId=1 localhost:8090/imports/shortlist
sleep 3; curl -s 'localhost:8083/clashes?roundId=1&date=2026-11-30'                    # 10 clashes, S12 10:00-10:50
curl -s -F file=@import-service/samples/attendance.csv -F sessionId=1 localhost:8090/imports/attendance
sleep 3; curl -s 'localhost:8082/od?date=2026-11-30'                                     # 10 GRANTED OD records
curl -s 'localhost:8082/outbox?limit=1'; curl -s 'localhost:8084/sessions/1/marks' | head -c 300
```

## Repository layout

`placement-service/`, `attendance-service/`, `timetable-service/`, `checkin-service/`, `import-service/`,
`frontend/`, `hardware/` (ESP32 + RC522 reader firmware), `mysql/init/` (creates the five databases),
`sbom/` (CycloneDX SBOMs), `docs/`. CI: `.github/workflows/ci.yml`.

## Temporary auth

Write endpoints read `X-User-Id` and `X-User-Role` (COORDINATOR or DIRECTOR) and answer 401/403 when they
are missing or wrong; RFID readers send `X-Device-Key`. This is **TEMPORARY** until proper login.
