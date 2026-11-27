# Live verification

Date: **2026-11-27** (IST, UTC+05:30), branch `chore/compose-ci`, on the developer laptop (Ubuntu, Docker 29.1.3).

Everything below was run against the whole system started with `docker compose up -d --build`.
The command lines and outputs are copied from the terminal; long lines are cut and marked
"(trimmed)", Java stack frames are collapsed and binary Kafka header values are shown as <binary>.
Nothing here is expected output that was not observed.
All student IDs are made up (99000000xx). MySQL is published on host port 3307 on this laptop
(`MYSQL_HOST_PORT=3307` in `.env`) because a native MySQL already uses 3306.

The X-User headers appear expanded in every command: `-H X-User-Id:coord-1 -H X-User-Role:COORDINATOR`
(coordinator) or `-H X-User-Id:dir-1 -H X-User-Role:DIRECTOR` (director). Device keys are shown as
`$KEY` or `<secret redacted>`.

## Bugs found during the live run

| # | Where | Bug | Fix |
|---|---|---|---|
| 1 | checkin-service | `409 NO_ACTIVE_SESSION` said "No session is running in room Hall Z at 2026-11-27T12:54:49Z": the UTC instant, while sessions are scheduled in local time (18:24 IST). | `30b00e3`: the message gives the local time and zone, "at 18:27 on 2026-11-27 (Asia/Kolkata)"; reproduced first by an assertion in `CheckinFlowTest`. Re-checked live at the end of section d. |
| 2 | service READMEs | `docker exec kafka …` no longer works: the compose container is `odattendence-kafka-1`. | `b81dc9a`: `docker compose exec kafka …`, and the setup steps point at the compose stack. |

The front end sent and read every request in the shape the services use; no request/response mismatch was found (section e).

## Environment and startup

`docker compose up -d --build` from a clean state; every container healthy, `kafka-init` exited 0 after creating the topics. (The topic list shows each name twice because `kafka-init` ran again when attendance-service was restarted for test e10; `--if-not-exists` keeps that harmless.)

```text
$ date -Is
2026-11-27T18:35:32+05:30

$ docker --version; docker compose version
Docker version 29.1.3, build 29.1.3-0ubuntu4.1
Docker Compose version 2.40.3+ds1-0ubuntu1

$ docker compose ps --format 'table {{.Service}}\t{{.Image}}\t{{.Status}}\t{{.Ports}}'
SERVICE              IMAGE                             STATUS                    PORTS
attendance-service   odattendence-attendance-service   Up 36 seconds (healthy)   0.0.0.0:8082->8082/tcp, [::]:8082->8082/tcp
checkin-service      odattendence-checkin-service      Up 8 minutes (healthy)    0.0.0.0:8084->8084/tcp, [::]:8084->8084/tcp
frontend             odattendence-frontend             Up 16 minutes (healthy)   0.0.0.0:5173->5173/tcp, [::]:5173->5173/tcp, 8080/tcp
import-service       odattendence-import-service       Up 16 minutes (healthy)   0.0.0.0:8090->8090/tcp, [::]:8090->8090/tcp
kafka                apache/kafka:4.0.0                Up 17 minutes (healthy)   0.0.0.0:9092->9092/tcp, [::]:9092->9092/tcp
mysql                mysql:8.4                         Up 17 minutes (healthy)   33060/tcp, 0.0.0.0:3307->3306/tcp, [::]:3307->3306/tcp
placement-service    odattendence-placement-service    Up 16 minutes (healthy)   0.0.0.0:8081->8081/tcp, [::]:8081->8081/tcp
timetable-service    odattendence-timetable-service    Up 16 minutes (healthy)   0.0.0.0:8083->8083/tcp, [::]:8083->8083/tcp

$ docker compose ps -a --format '{{.Service}} {{.State}} {{.Status}}' kafka-init
kafka-init exited Exited (0) 37 seconds ago

$ docker compose logs kafka-init 2>/dev/null | grep -v WARNING | sed 's/^kafka-init-1  | //' | sort | tr '\n' ' '
checkin-events checkin-events checkin-events checkin-events.DLT checkin-events.DLT checkin-events.DLT clash-events clash-events clash-events clash-events.DLT clash-events.DLT clash-events.DLT __consumer_offsets __consumer_offsets Created topic checkin-events. Created topic checkin-events.DLT. Created topic clash-events. Created topic clash-events.DLT. Created topic drive-events. Created topic drive-events.DLT. Created topic import-events. Created topic import-events.DLT. Created topic od-events. Created topic od-events.DLT. drive-events drive-events drive-events drive-events.DLT drive-events.DLT drive-events.DLT import-events import-events import-events import-events.DLT import-events.DLT im … (trimmed)
$ for s in placement-service attendance-service timetable-service checkin-service import-service frontend; do echo "$s: user=$(docker compose exec -T $s id -un) tz=$(docker compose exec -T $s date +%Z)"; done
placement-service: user=app tz=IST
attendance-service: user=app tz=IST
timetable-service: user=app tz=IST
checkin-service: user=app tz=IST
import-service: user=appuser tz=IST
frontend: user=nginx tz=IST
```

## a) placement-service

Drive, rounds, sessions, reschedule, cancel, error codes, outbox drained and every event on `drive-events`.

```text
$ date -Is
2026-11-27T18:20:04+05:30

$ curl -s -w ' HTTP %{http_code}\n' -H X-User-Id:coord-1 -H X-User-Role:COORDINATOR -H Content-Type:application/json -d '{"companyName":"Acme Corp","driveDate":"2026-11-30","startTime":"09:00","endTime":"17:00","venue":"Main Block"}' localhost:8081/drives
{"id":1,"companyName":"Acme Corp","driveDate":"2026-11-30","startTime":"09:00:00","endTime":"17:00:00","venue":"Main Block"} HTTP 201

$ curl -s -w ' HTTP %{http_code}\n' -H Content-Type:application/json -d '{"companyName":"X","driveDate":"2026-11-30","startTime":"09:00","endTime":"17:00","venue":"V"}' localhost:8081/drives
{"status":401,"error":"Unauthorized","message":"Missing or invalid X-User-Id header","errors":[]} HTTP 401

$ curl -s -w ' HTTP %{http_code}\n' -H X-User-Id:u1 -H X-User-Role:STUDENT -H Content-Type:application/json -d '{}' localhost:8081/drives
{"status":403,"error":"Forbidden","message":"X-User-Role must be COORDINATOR or DIRECTOR","errors":[]} HTTP 403

$ curl -s -w ' HTTP %{http_code}\n' -H X-User-Id:coord-1 -H X-User-Role:COORDINATOR -H Content-Type:application/json -d '{"companyName":"","driveDate":"tomorrow","startTime":"09:00","endTime":"08:00","venue":"V"}' localhost:8081/drives
{"status":400,"error":"Bad Request","message":"driveDate has an invalid value","errors":[{"field":"driveDate","message":"has an invalid value"}]} HTTP 400

$ curl -s -w ' HTTP %{http_code}\n' -H X-User-Id:coord-1 -H X-User-Role:COORDINATOR -H Content-Type:application/json -d '{"name":"Aptitude","sequence":1}' localhost:8081/drives/1/rounds
{"id":1,"driveId":1,"name":"Aptitude","sequence":1,"sessions":[]} HTTP 201

$ curl -s -w ' HTTP %{http_code}\n' -H X-User-Id:coord-1 -H X-User-Role:COORDINATOR -H Content-Type:application/json -d '{"name":"Technical","sequence":2}' localhost:8081/drives/1/rounds
{"id":2,"driveId":1,"name":"Technical","sequence":2,"sessions":[]} HTTP 201

$ curl -s -w ' HTTP %{http_code}\n' -H X-User-Id:coord-1 -H X-User-Role:COORDINATOR -H Content-Type:application/json -d '{"name":"Dup","sequence":2}' localhost:8081/drives/1/rounds
{"status":409,"error":"Conflict","message":"Drive 1 already has a round with sequence 2","errors":[]} HTTP 409

$ curl -s -w ' HTTP %{http_code}\n' -H X-User-Id:coord-1 -H X-User-Role:COORDINATOR -H Content-Type:application/json -d '{"date":"2026-11-30","startTime":"09:30","endTime":"10:15","room":"Hall A"}' localhost:8081/rounds/1/sessions
{"id":1,"roundId":1,"driveId":1,"date":"2026-11-30","startTime":"09:30:00","endTime":"10:15:00","room":"Hall A","status":"SCHEDULED"} HTTP 201

$ curl -s -w ' HTTP %{http_code}\n' -H X-User-Id:coord-1 -H X-User-Role:COORDINATOR -H Content-Type:application/json -d '{"date":"2026-11-30","startTime":"10:30","endTime":"11:15","room":"Hall A"}' localhost:8081/rounds/1/sessions
{"id":2,"roundId":1,"driveId":1,"date":"2026-11-30","startTime":"10:30:00","endTime":"11:15:00","room":"Hall A","status":"SCHEDULED"} HTTP 201

$ curl -s -w ' HTTP %{http_code}\n' -H X-User-Id:coord-1 -H X-User-Role:COORDINATOR -H Content-Type:application/json -d '{"date":"2026-11-30","startTime":"14:00","endTime":"15:00","room":"Room 101"}' localhost:8081/rounds/2/sessions
{"id":3,"roundId":2,"driveId":1,"date":"2026-11-30","startTime":"14:00:00","endTime":"15:00:00","room":"Room 101","status":"SCHEDULED"} HTTP 201

$ curl -s -w ' HTTP %{http_code}\n' -H X-User-Id:coord-1 -H X-User-Role:COORDINATOR -H Content-Type:application/json -X PUT -d '{"date":"2026-11-30","startTime":"14:30","endTime":"15:30","room":"Room 101"}' localhost:8081/sessions/3
{"id":3,"roundId":2,"driveId":1,"date":"2026-11-30","startTime":"14:30:00","endTime":"15:30:00","room":"Room 101","status":"SCHEDULED"} HTTP 200

$ curl -s -w ' HTTP %{http_code}\n' -H X-User-Id:coord-1 -H X-User-Role:COORDINATOR -X POST localhost:8081/sessions/3/cancel
{"id":3,"roundId":2,"driveId":1,"date":"2026-11-30","startTime":"14:30:00","endTime":"15:30:00","room":"Room 101","status":"CANCELLED"} HTTP 200

$ curl -s -w ' HTTP %{http_code}\n' -H X-User-Id:coord-1 -H X-User-Role:COORDINATOR -X POST localhost:8081/sessions/3/cancel
{"status":409,"error":"Conflict","message":"Session 3 is already cancelled","errors":[]} HTTP 409

$ curl -s -w ' HTTP %{http_code}\n' -H X-User-Id:coord-1 -H X-User-Role:COORDINATOR -H Content-Type:application/json -X PUT -d '{"date":"2026-11-30","startTime":"14:30","endTime":"15:30","room":"Room 101"}' localhost:8081/sessions/3
{"status":409,"error":"Conflict","message":"Session 3 is cancelled and cannot be rescheduled","errors":[]} HTTP 409

$ curl -s -w ' HTTP %{http_code}\n' localhost:8081/sessions/999
{"status":404,"error":"Not Found","message":"Session 999 not found","errors":[]} HTTP 404

$ curl -s localhost:8081/drives/1
{"id":1,"companyName":"Acme Corp","driveDate":"2026-11-30","startTime":"09:00:00","endTime":"17:00:00","venue":"Main Block","rounds":[{"id":1,"driveId":1,"name":"Aptitude","sequence":1,"sessions":[{"id":1,"roundId":1,"driveId":1,"date":"2026-11-30","startTime":"09:30:00","endTime":"10:15:00","room":"Hall A","status":"SCHEDULED"},{"id":2,"roundId":1,"driveId":1,"date":"2026-11-30","startTime":"10:30:00","endTime":"11:15:00","room":"Hall A","status":"SCHEDULED"}]},{"id":2,"driveId":1,"name":"Technical","sequence":2,"sessions":[{"id":3,"roundId":2,"driveId":1,"date":"2026-11-30","startTime":"14:30:00","endTime":"15:30:00","room":"Room 101","status":"CANCELLED"}]}]}
$ curl -s 'localhost:8081/outbox?limit=10' | python3 -c 'import json,sys; o=json.load(sys.stdin); print("pending", o["pending"]); [print(r) for r in o["latest"]]'
pending 0
{'id': 8, 'topic': 'drive-events', 'aggregateKey': '1', 'eventType': 'SESSION_CANCELLED', 'createdAt': '2026-11-27T12:50:17.141728Z', 'publishedAt': '2026-11-27T12:50:17.568586Z', 'attempts': 1, 'lastError': None}
{'id': 7, 'topic': 'drive-events', 'aggregateKey': '1', 'eventType': 'SESSION_UPDATED', 'createdAt': '2026-11-27T12:50:17.101288Z', 'publishedAt': '2026-11-27T12:50:17.540922Z', 'attempts': 1, 'lastError': None}
{'id': 6, 'topic': 'drive-events', 'aggregateKey': '1', 'eventType': 'SESSION_SCHEDULED', 'createdAt': '2026-11-27T12:50:17.056916Z', 'publishedAt': '2026-11-27T12:50:17.509226Z', 'attempts': 1, 'lastError': None}
{'id': 5, 'topic': 'drive-events', 'aggregateKey': '1', 'eventType': 'SESSION_SCHEDULED', 'createdAt': '2026-11-27T12:50:17.013734Z', 'publishedAt': '2026-11-27T12:50:17.480123Z', 'attempts': 1, 'lastError': None}
{'id': 4, 'topic': 'drive-events', 'aggregateKey': '1', 'eventType': 'SESSION_SCHEDULED', 'createdAt': '2026-11-27T12:50:16.969506Z', 'publishedAt': '2026-11-27T12:50:17.452551Z', 'attempts': 1, 'lastError': None}
{'id': 3, 'topic': 'drive-events', 'aggregateKey': '1', 'eventType': 'ROUND_CREATED', 'createdAt': '2026-11-27T12:50:16.863543Z', 'publishedAt': '2026-11-27T12:50:16.918373Z', 'attempts': 1, 'lastError': None}
{'id': 2, 'topic': 'drive-events', 'aggregateKey': '1', 'eventType': 'ROUND_CREATED', 'createdAt': '2026-11-27T12:50:16.808108Z', 'publishedAt': '2026-11-27T12:50:16.889977Z', 'attempts': 1, 'lastError': None}
{'id': 1, 'topic': 'drive-events', 'aggregateKey': '1', 'eventType': 'DRIVE_SCHEDULED', 'createdAt': '2026-11-27T12:50:04.557511Z', 'publishedAt': '2026-11-27T12:50:05.250549Z', 'attempts': 1, 'lastError': None}

$ docker compose exec -T kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic drive-events --from-beginning --property print.key=true --timeout-ms 5000 2>/dev/null
1	{"eventId":"54fdc9cf-5da9-41e3-88ae-35eadb2e0f69","eventType":"DRIVE_SCHEDULED","schemaVersion":1,"occurredAt":"2026-11-27T12:50:04.550Z","driveId":1,"companyName":"Acme Corp","driveDate":"2026-11-30","startTime":"09:00:00","endTime":"17:00:00","venue":"Main Block"}
1	{"eventId":"9e97f190-9ef1-4382-89a3-3807b7b53410","eventType":"ROUND_CREATED","schemaVersion":1,"occurredAt":"2026-11-27T12:50:16.804Z","driveId":1,"roundId":1,"name":"Aptitude","sequence":1}
1	{"eventId":"51e765f9-3ccd-4eef-8658-9325d5c427b5","eventType":"ROUND_CREATED","schemaVersion":1,"occurredAt":"2026-11-27T12:50:16.862Z","driveId":1,"roundId":2,"name":"Technical","sequence":2}
1	{"eventId":"1ca99dd3-3522-4f7c-8434-2f40f1ac14e4","eventType":"SESSION_SCHEDULED","schemaVersion":1,"occurredAt":"2026-11-27T12:50:16.966Z","driveId":1,"roundId":1,"sessionId":1,"date":"2026-11-30","startTime":"09:30:00","endTime":"10:15:00","room":"Hall A","status":"SCHEDULED"}
1	{"eventId":"4384921f-bc17-4ce9-8d80-36f7a47a5bf1","eventType":"SESSION_SCHEDULED","schemaVersion":1,"occurredAt":"2026-11-27T12:50:17.013Z","driveId":1,"roundId":1,"sessionId":2,"date":"2026-11-30","startTime":"10:30:00","endTime":"11:15:00","room":"Hall A","status":"SCHEDULED"}
1	{"eventId":"cfc2ef67-6d42-43e1-8213-289890829832","eventType":"SESSION_SCHEDULED","schemaVersion":1,"occurredAt":"2026-11-27T12:50:17.056Z","driveId":1,"roundId":2,"sessionId":3,"date":"2026-11-30","startTime":"14:00:00","endTime":"15:00:00","room":"Room 101","status":"SCHEDULED"}
1	{"eventId":"75ee1594-2d50-4c7f-b7bd-939699e85ded","eventType":"SESSION_UPDATED","schemaVersion":1,"occurredAt":"2026-11-27T12:50:17.100Z","driveId":1,"roundId":2,"sessionId":3,"date":"2026-11-30","startTime":"14:30:00","endTime":"15:30:00","room":"Room 101","status":"SCHEDULED"}
1	{"eventId":"bfe1c490-3c19-4fdf-820f-b13a850f6a93","eventType":"SESSION_CANCELLED","schemaVersion":1,"occurredAt":"2026-11-27T12:50:17.141Z","driveId":1,"roundId":2,"sessionId":3,"date":"2026-11-30","startTime":"14:30:00","endTime":"15:30:00","room":"Room 101","status":"CANCELLED"}
```

## b) timetable-service with the import-service samples

`samples/timetable.xlsx` (3 rows rejected as documented), `shortlist_valid.xlsx` for round 1 and `shortlist_with_errors.xlsx` for round 2. Expected by the clash rule: window 09:30–11:15 on Monday 30 Nov; only S12 has a Monday slot (10:00–10:50), so students …01–…10 clash. Round 2 has only a cancelled session and no Monday slot overlaps 14:30–15:30, so its list is empty and, being a first empty result, is not published.

```text
$ curl -s -w ' HTTP %{http_code}\n' -F file=@samples/timetable.xlsx -F semester=2026-ODD localhost:8090/imports/timetable
{"uploadId":"5c5b0b63-ab54-4358-84db-4ec05228f30b","type":"TIMETABLE","targetId":"2026-ODD","fileName":"timetable.xlsx","totalRows":27,"acceptedCount":24,"rejectedCount":3,"rejected":[{"row":6,"sheet":"slots","value":"SUNDAY","reason":"INVALID_DAY"},{"row":7,"sheet":"slots","value":"12:00-11:00","reason":"START_NOT_BEFORE_END"},{"row":22,"sheet":"enrolments","value":"S99","reason":"UNKNOWN_SECTION"}],"status":"PUBLISHED","uploadedAt":"2026-11-27T18:20:39"} HTTP 200

$ curl -s -w ' HTTP %{http_code}\n' -F file=@samples/shortlist_valid.xlsx -F roundId=1 localhost:8090/imports/shortlist | cut -c1-400
{"uploadId":"14b57107-8332-45f8-b673-6bfce5b7ccf5","type":"SHORTLIST","targetId":"1","fileName":"shortlist_valid.xlsx","totalRows":25,"acceptedCount":25,"rejectedCount":0,"rejected":[],"status":"PUBLISHED","uploadedAt":"2026-11-27T18:20:39"} HTTP 200

$ curl -s -w ' HTTP %{http_code}\n' -F file=@samples/shortlist_with_errors.xlsx -F roundId=2 localhost:8090/imports/shortlist
{"uploadId":"832bdccc-a5cc-4671-9976-3c04d1f40b2a","type":"SHORTLIST","targetId":"2","fileName":"shortlist_with_errors.xlsx","totalRows":8,"acceptedCount":4,"rejectedCount":4,"rejected":[{"row":7,"value":"99000004","reason":"NOT_10_DIGITS"},{"row":8,"value":"99AB000005","reason":"NOT_NUMERIC"},{"row":9,"value":"","reason":"EMPTY"},{"row":11,"value":"9900000001","reason":"DUPLICATE"}],"status":"PUBLISHED","uploadedAt":"2026-11-27T18:20:39"} HTTP 200

$ curl -s localhost:8083/timetable/semesters
[{"semester":"2026-ODD","active":true,"uploadId":"5c5b0b63-ab54-4358-84db-4ec05228f30b","slotCount":4,"enrolmentCount":20,"appliedAt":"2026-11-27T12:50:39.582657Z"}]
$ curl -s 'localhost:8083/timetable/students/9900000006/slots?date=2026-11-30'
{"studentId":"9900000006","date":"2026-11-30","dayOfWeek":"MONDAY","semester":"2026-ODD","slots":[{"sectionCode":"S12","courseName":"Cloud Native","dayOfWeek":"MONDAY","startTime":"10:00:00","endTime":"10:50:00","room":"C-301"}]}
$ curl -s 'localhost:8083/clashes?roundId=1&date=2026-11-30'
[{"roundId":1,"date":"2026-11-30","semester":"2026-ODD","windowStart":"09:30:00","windowEnd":"11:15:00","clashes":[{"studentId":"9900000001","sectionCode":"S12","courseName":"Cloud Native","dayOfWeek":"MONDAY","slotStart":"10:00:00","slotEnd":"10:50:00","room":"C-301"},{"studentId":"9900000002","sectionCode":"S12","courseName":"Cloud Native","dayOfWeek":"MONDAY","slotStart":"10:00:00","slotEnd":"10:50:00","room":"C-301"},{"studentId":"9900000003","sectionCode":"S12","courseName":"Cloud Native","dayOfWeek":"MONDAY","slotStart":"10:00:00","slotEnd":"10:50:00","room":"C-301"},{"studentId":"9900000004","sectionCode":"S12","courseName":"Cloud Native","dayOfWeek":"MONDAY","slotStart":"10:00:00","s … (trimmed)
$ curl -s 'localhost:8083/clashes?roundId=2'
[{"roundId":2,"date":"2026-11-30","semester":"2026-ODD","windowStart":"14:30:00","windowEnd":"15:30:00","clashes":[],"lastPublished":null}]
$ curl -s -w ' HTTP %{http_code}\n' 'localhost:8083/timetable/students/12345/slots?date=2026-11-30'
{"status":400,"error":"Bad Request","message":"studentId must be exactly 10 digits","errors":[{"field":"studentId","message":"must be exactly 10 digits"}]} HTTP 400

$ curl -s -w ' HTTP %{http_code}\n' 'localhost:8083/clashes?roundId=0'
{"status":400,"error":"Bad Request","message":"roundId must be greater than 0","errors":[{"field":"roundId","message":"must be greater than 0"}]} HTTP 400

$ docker compose exec -T kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic clash-events --from-beginning --property print.key=true --timeout-ms 5000 2>/dev/null | cut -c1-330
1	{"eventId":"dc442820-aa57-41b6-a5fa-929d4d06eaa7","eventType":"CLASHES_COMPUTED","schemaVersion":1,"occurredAt":"2026-11-27T12:50:39.897Z","roundId":1,"date":"2026-11-30","windowStart":"09:30:00","windowEnd":"11:15:00","semester":"2026-ODD","clashes":[{"studentId":"9900000001","sectionCode":"S12","courseName":"Cloud Native","d

$ curl -s 'localhost:8083/outbox?limit=5'
{"pending":0,"latest":[{"id":1,"topic":"clash-events","aggregateKey":"1","eventType":"CLASHES_COMPUTED","createdAt":"2026-11-27T12:50:39.907339Z","publishedAt":"2026-11-27T12:50:40.054627Z","attempts":1,"lastError":null}]}
```

## c) attendance-service end to end

Attendance file → checkin FILE marks → attendance → OD; coordinator correction revokes; director re-grants; 14-day rule; waiting time (absent in session 1, present in session 2 → OD based on session 2); cancelled session keeps OD; CSV export; dead-letter topics.

```text
$ curl -s -w ' HTTP %{http_code}\n' -F file=@import-service/samples/attendance.csv -F sessionId=1 localhost:8090/imports/attendance | cut -c1-300
{"uploadId":"1dbf9812-fc06-4c14-9803-6e20a41cdceb","type":"ATTENDANCE","targetId":"1","fileName":"attendance.csv","totalRows":20,"acceptedCount":20,"rejectedCount":0,"rejected":[],"status":"PUBLISHED","uploadedAt":"2026-11-27T18:21:14"} HTTP 200

$ curl -s 'localhost:8084/sessions/1/marks' | python3 -c 'import json,sys; m=json.load(sys.stdin); print(len(m), "marks"); print(m[0])'
20 marks
{'markId': 'c58afe5e-0759-3dcb-9081-6ca866ba4bf0', 'sessionId': 1, 'roundId': 1, 'studentId': '9900000001', 'version': 1, 'present': True, 'source': 'FILE', 'markedBy': 'import-service', 'markedAt': '2026-11-27T12:51:14.439334Z', 'createdAt': '2026-11-27T12:51:14.464059Z'}

$ curl -s 'localhost:8082/marks/sessions/1' | python3 -c 'import json,sys; m=json.load(sys.stdin); print(len(m), "marks"); print(m[0])'
20 marks
{'markId': 'c58afe5e-0759-3dcb-9081-6ca866ba4bf0', 'sessionId': 1, 'studentId': '9900000001', 'version': 1, 'present': True, 'source': 'FILE', 'markedBy': 'import-service', 'markedAt': '2026-11-27T12:51:14.439Z', 'receivedAt': '2026-11-27T12:51:15.026535Z'}

$ curl -s 'localhost:8082/od?date=2026-11-30' | python3 -c 'import json,sys; o=json.load(sys.stdin); print(len(o), "OD records"); print(sorted({r["studentId"] for r in o})); print(o[0])'
10 OD records
['9900000001', '9900000002', '9900000003', '9900000004', '9900000005', '9900000006', '9900000007', '9900000008', '9900000009', '9900000010']
{'studentId': '9900000001', 'date': '2026-11-30', 'sectionCode': 'S12', 'courseName': 'Cloud Native', 'slotStart': '10:00:00', 'slotEnd': '10:50:00', 'sessionId': 1, 'roundId': 1, 'reason': 'Attended session 1 (09:30-10:15) of placement round 1', 'status': 'GRANTED', 'version': 1, 'updatedAt': '2026-11-27T12:51:15.060579Z'}

$ curl -s 'localhost:8082/outbox?limit=1'
{"pending":0,"latest":[{"id":10,"topic":"od-events","aggregateKey":"9900000010","eventType":"OD_GRANTED","createdAt":"2026-11-27T12:51:15.572928Z","publishedAt":"2026-11-27T12:51:16.219543Z","attempts":1,"lastError":null}]}
$ curl -s -w ' HTTP %{http_code}\n' -H X-User-Id:coord-1 -H X-User-Role:COORDINATOR -H Content-Type:application/json -d '{"sessionId":1,"studentId":"9900000001","present":false,"reason":"Left before the round started"}' localhost:8082/corrections
{"id":1,"sessionId":1,"studentId":"9900000001","oldPresent":true,"newPresent":false,"reason":"Left before the round started","correctedBy":"coord-1","role":"COORDINATOR","correctedAt":"2026-11-27T12:51:30.374957300Z","markId":"CORRECTION-9caaa5e8-a719-491c-95ef-83422434f12f","markVersion":2} HTTP 201

$ curl -s 'localhost:8082/od/students/9900000001?includeRevoked=true'
[{"studentId":"9900000001","date":"2026-11-30","sectionCode":"S12","courseName":"Cloud Native","slotStart":"10:00:00","slotEnd":"10:50:00","sessionId":1,"roundId":1,"reason":"No longer marked present in any session of round 1","status":"REVOKED","version":2,"updatedAt":"2026-11-27T12:51:30.393869Z"}]
$ curl -s -w ' HTTP %{http_code}\n' -H X-User-Id:dir-1 -H X-User-Role:DIRECTOR -H Content-Type:application/json -d '{"sessionId":1,"studentId":"9900000001","present":true,"reason":"Seen on CCTV in Hall A"}' localhost:8082/corrections
{"id":2,"sessionId":1,"studentId":"9900000001","oldPresent":false,"newPresent":true,"reason":"Seen on CCTV in Hall A","correctedBy":"dir-1","role":"DIRECTOR","correctedAt":"2026-11-27T12:51:31.483246967Z","markId":"CORRECTION-e9b5b2d0-eda4-4706-bfa8-42727f448a58","markVersion":3} HTTP 201

$ curl -s 'localhost:8082/od/students/9900000001?includeRevoked=true'
[{"studentId":"9900000001","date":"2026-11-30","sectionCode":"S12","courseName":"Cloud Native","slotStart":"10:00:00","slotEnd":"10:50:00","sessionId":1,"roundId":1,"reason":"Attended session 1 (09:30-10:15) of placement round 1","status":"GRANTED","version":3,"updatedAt":"2026-11-27T12:51:31.499408Z"}]
$ curl -s 'localhost:8082/corrections?sessionId=1'
[{"id":2,"sessionId":1,"studentId":"9900000001","oldPresent":false,"newPresent":true,"reason":"Seen on CCTV in Hall A","correctedBy":"dir-1","role":"DIRECTOR","correctedAt":"2026-11-27T12:51:31.483247Z","markId":"CORRECTION-e9b5b2d0-eda4-4706-bfa8-42727f448a58","markVersion":3},{"id":1,"sessionId":1,"studentId":"9900000001","oldPresent":true,"newPresent":false,"reason":"Left before the round started","correctedBy":"coord-1","role":"COORDINATOR","correctedAt":"2026-11-27T12:51:30.374957Z","markId":"CORRECTION-9caaa5e8-a719-491c-95ef-83422434f12f","markVersion":2}]
$ curl -s 'localhost:8082/marks/sessions/1?history=true' | python3 -c 'import json,sys; [print(m) for m in json.load(sys.stdin) if m["studentId"]=="9900000001"]'
{'markId': 'c58afe5e-0759-3dcb-9081-6ca866ba4bf0', 'sessionId': 1, 'studentId': '9900000001', 'version': 1, 'present': True, 'source': 'FILE', 'markedBy': 'import-service', 'markedAt': '2026-11-27T12:51:14.439Z', 'receivedAt': '2026-11-27T12:51:15.026535Z'}
{'markId': 'CORRECTION-9caaa5e8-a719-491c-95ef-83422434f12f', 'sessionId': 1, 'studentId': '9900000001', 'version': 2, 'present': False, 'source': 'CORRECTION', 'markedBy': 'coord-1', 'markedAt': '2026-11-27T12:51:30.374957Z', 'receivedAt': '2026-11-27T12:51:30.378674Z'}
{'markId': 'CORRECTION-e9b5b2d0-eda4-4706-bfa8-42727f448a58', 'sessionId': 1, 'studentId': '9900000001', 'version': 3, 'present': True, 'source': 'CORRECTION', 'markedBy': 'dir-1', 'markedAt': '2026-11-27T12:51:31.483247Z', 'receivedAt': '2026-11-27T12:51:31.485579Z'}

$ curl -s -w ' HTTP %{http_code}\n' -H Content-Type:application/json -d '{"sessionId":1,"studentId":"9900000001","present":false,"reason":"x"}' localhost:8082/corrections
{"status":401,"error":"Unauthorized","message":"Missing or invalid X-User-Id header","errors":[]} HTTP 401

$ curl -s -w ' HTTP %{http_code}\n' -H X-User-Id:coord-1 -H X-User-Role:COORDINATOR -H Content-Type:application/json -d '{"sessionId":999,"studentId":"9900000001","present":false,"reason":"x"}' localhost:8082/corrections
{"status":404,"error":"Not Found","message":"Session 999 is not known to attendance-service","errors":[]} HTTP 404

$ curl -s -w ' HTTP %{http_code}\n' -H X-User-Id:coord-1 -H X-User-Role:COORDINATOR -H Content-Type:application/json -d '{"sessionId":1,"studentId":"123","present":false,"reason":""}' localhost:8082/corrections
{"status":400,"error":"Bad Request","message":"reason must not be blank; studentId must be exactly 10 digits","errors":[{"field":"reason","message":"must not be blank"},{"field":"studentId","message":"must be exactly 10 digits"}]} HTTP 400

$ curl -s -w ' HTTP %{http_code}\n' -H X-User-Id:coord-1 -H X-User-Role:COORDINATOR -H Content-Type:application/json -d '{"date":"2026-11-02","startTime":"09:00","endTime":"10:00","room":"Room 101"}' localhost:8081/rounds/2/sessions
{"id":4,"roundId":2,"driveId":1,"date":"2026-11-02","startTime":"09:00:00","endTime":"10:00:00","room":"Room 101","status":"SCHEDULED"} HTTP 201

$ curl -s -w ' HTTP %{http_code}\n' -H X-User-Id:coord-1 -H X-User-Role:COORDINATOR -H Content-Type:application/json -d '{"sessionId":4,"studentId":"9900000001","present":true,"reason":"Late entry"}' localhost:8082/corrections
{"status":403,"error":"Forbidden","message":"A COORDINATOR may correct attendance only up to 14 days after the session date (session on 2026-11-02, last day 2026-11-16, today 2026-11-27). Ask a DIRECTOR.","errors":[]} HTTP 403

$ curl -s -w ' HTTP %{http_code}\n' -H X-User-Id:dir-1 -H X-User-Role:DIRECTOR -H Content-Type:application/json -d '{"sessionId":4,"studentId":"9900000001","present":true,"reason":"Late entry"}' localhost:8082/corrections
{"id":3,"sessionId":4,"studentId":"9900000001","oldPresent":null,"newPresent":true,"reason":"Late entry","correctedBy":"dir-1","role":"DIRECTOR","correctedAt":"2026-11-27T12:51:45.393565819Z","markId":"CORRECTION-fd24a1fd-21d5-4289-b8b5-3e2604368604","markVersion":1} HTTP 201

--- waiting time
$ curl -s -w ' HTTP %{http_code}\n' -H X-User-Id:coord-1 -H X-User-Role:COORDINATOR -H Content-Type:application/json -d '{"sessionId":1,"studentId":"9900000002","present":false,"reason":"Was in session 2, not 1"}' localhost:8082/corrections
{"id":4,"sessionId":1,"studentId":"9900000002","oldPresent":true,"newPresent":false,"reason":"Was in session 2, not 1","correctedBy":"coord-1","role":"COORDINATOR","correctedAt":"2026-11-27T12:51:45.447180129Z","markId":"CORRECTION-d4b20ab9-3dec-4bb6-bf9b-ca42704b9f25","markVersion":2} HTTP 201

$ curl -s -w ' HTTP %{http_code}\n' -H X-User-Id:coord-1 -H X-User-Role:COORDINATOR -H Content-Type:application/json -d '{"studentId":"9900000002","present":true}' localhost:8084/sessions/2/marks
{"markId":"6ccc6fa9-06f3-4269-9230-3128b050826a","sessionId":2,"roundId":1,"studentId":"9900000002","version":1,"present":true,"source":"MANUAL","markedBy":"coord-1","markedAt":"2026-11-27T12:51:45.586608318Z","createdAt":"2026-11-27T12:51:45.588235112Z"} HTTP 201

$ curl -s 'localhost:8082/od/students/9900000002?includeRevoked=true'
[{"studentId":"9900000002","date":"2026-11-30","sectionCode":"S12","courseName":"Cloud Native","slotStart":"10:00:00","slotEnd":"10:50:00","sessionId":2,"roundId":1,"reason":"Attended session 2 (10:30-11:15) of placement round 1","status":"GRANTED","version":3,"updatedAt":"2026-11-27T12:51:45.768300Z"}]
--- cancel keeps OD
$ curl -s -w ' HTTP %{http_code}\n' -H X-User-Id:coord-1 -H X-User-Role:COORDINATOR -X POST localhost:8081/sessions/1/cancel
{"id":1,"roundId":1,"driveId":1,"date":"2026-11-30","startTime":"09:30:00","endTime":"10:15:00","room":"Hall A","status":"CANCELLED"} HTTP 200

$ curl -s 'localhost:8082/od?date=2026-11-30' | python3 -c 'import json,sys; o=json.load(sys.stdin); print(len(o), "GRANTED"); [print(r["studentId"], r["sessionId"], r["status"], r["reason"]) for r in o]'
10 GRANTED
9900000001 1 GRANTED Attended session 1 (09:30-10:15) of placement round 1
9900000002 2 GRANTED Attended session 2 (10:30-11:15) of placement round 1
9900000003 1 GRANTED Attended session 1 (09:30-10:15) of placement round 1
9900000004 1 GRANTED Attended session 1 (09:30-10:15) of placement round 1
9900000005 1 GRANTED Attended session 1 (09:30-10:15) of placement round 1
9900000006 1 GRANTED Attended session 1 (09:30-10:15) of placement round 1
9900000007 1 GRANTED Attended session 1 (09:30-10:15) of placement round 1
9900000008 1 GRANTED Attended session 1 (09:30-10:15) of placement round 1
9900000009 1 GRANTED Attended session 1 (09:30-10:15) of placement round 1
9900000010 1 GRANTED Attended session 1 (09:30-10:15) of placement round 1

$ curl -s 'localhost:8083/clashes?roundId=1&date=2026-11-30' | python3 -c 'import json,sys; r=json.load(sys.stdin)[0]; print(r["windowStart"], r["windowEnd"], len(r["clashes"]), "clashes", r["lastPublished"])'
09:30:00 11:15:00 10 clashes {'eventId': 'dc442820-aa57-41b6-a5fa-929d4d06eaa7', 'queuedAt': '2026-11-27T12:50:39.912149Z', 'clashCount': 10, 'current': True}

$ curl -s -D - 'localhost:8082/od/export?date=2026-11-30' | head -8
HTTP/1.1 200 
Content-Disposition: attachment; filename="od-2026-11-30.csv"
Content-Type: text/csv;charset=UTF-8
Content-Length: 1146
Date: Fri, 27 Nov 2026 12:51:50 GMT

studentId,date,course,section,slotStart,slotEnd,sessionId,reason
9900000001,2026-11-30,Cloud Native,S12,10:00,10:50,1,Attended session 1 (09:30-10:15) of placement round 1

$ echo '1|this is not json' | docker compose exec -T kafka /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 --topic clash-events --property parse.key=true --property key.separator='|'

$ echo '1|{"eventId":"6a1d3c9e-0000-4000-8000-000000000001","eventType":"ATTENDANCE_MARKED","sessionId":1,"roundId":1,"studentId":"12AB","present":true,"source":"RFID","markedBy":"x","markedAt":"2026-11-30T04:00:00Z","markId":"m-bad-1"}' | docker compose exec -T kafka /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 --topic checkin-events --property parse.key=true --property key.separator='|'

$ docker compose exec -T kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic clash-events.DLT --from-beginning --property print.key=true --property print.headers=true --timeout-ms 5000 2>/dev/null | sed -E 's/kafka_dlt-exception-stacktrace:[^,]*(,|$)//' | cut -c1-600
kafka_dlt-exception-fqcn:org.springframework.kafka.support.serializer.DeserializationException,kafka_dlt-exception-cause-fqcn:org.springframework.kafka.support.serializer.DeserializationException,kafka_dlt-exception-message:failed to deserialize,
    … (stack trace frames trimmed)
Caused by: org.apache.kafka.common.errors.SerializationException: Can't deserialize data  from topic [clash-events]
    … (stack trace frames trimmed)
Caused by: tools.jackson.core.exc.StreamReadException: Unrecognized token 'this': was expecting (JSON String, Number, Array, Object or token 'null', 'true' or 'false')
    … (stack trace frames trimmed)
,kafka_dlt-original-topic:clash-events,kafka_dlt-original-partition:<binary>,kafka_dlt-original-offset:<binary>,kafka_dlt-original-timestamp:<binary>,kafka_dlt-original-timestamp-type:CreateTime,kafka_dlt-original-consumer-group:attendance-service	1	this is not json

$ docker compose exec -T kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic checkin-events.DLT --from-beginning --property print.key=true --property print.headers=true --timeout-ms 5000 2>/dev/null | grep -o 'kafka_dlt-exception-message:[^,]*\|kafka_dlt-original-consumer-group:[^,]*\|{"eventId.*' | cut -c1-300

--- consumers still progress after the bad messages
$ curl -s -w ' HTTP %{http_code}\n' -H X-User-Id:coord-1 -H X-User-Role:COORDINATOR -H Content-Type:application/json -d '{"studentId":"9900000003","present":false}' localhost:8084/sessions/2/marks | cut -c1-120
{"markId":"d4bfe675-3c7c-435c-bb02-3859536e8e02","sessionId":2,"roundId":1,"studentId":"9900000003","version":1,"present

$ curl -s 'localhost:8082/marks/sessions/2' | python3 -c 'import json,sys; [print(m["studentId"], m["present"], m["source"]) for m in json.load(sys.stdin)]'
9900000002 True MANUAL
9900000003 False MANUAL

$ docker compose exec -T kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group attendance-service 2>/dev/null | awk '{print $2, $3, $4, $5, $6}' | column -t
TOPIC           PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG
clash-events    0          2               2               0
drive-events    0          10              10              0
drive-events    1          -               0               -
clash-events    2          -               0               -
clash-events    1          -               0               -
checkin-events  2          2               2               0
checkin-events  1          -               0               -
checkin-events  0          21              21              0
drive-events    2          -               0               -

$ docker compose exec -T kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic checkin-events.DLT --from-beginning --property print.headers=true --timeout-ms 5000 2>/dev/null | tr ',' '\n' | grep -E 'exception-message|original-topic|original-consumer-group|eventId'

$ docker compose exec -T kafka /opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server localhost:9092 --topic 'checkin-events.DLT|clash-events.DLT'
checkin-events.DLT:0:1
checkin-events.DLT:1:0
checkin-events.DLT:2:0
clash-events.DLT:0:1
clash-events.DLT:1:0
clash-events.DLT:2:0

$ docker compose exec -T kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic checkin-events.DLT --partition 0 --offset earliest --max-messages 1 --timeout-ms 10000 --property print.headers=true 2>&1 | grep -E 'Caused by|original-consumer-group|eventId' | grep -oE 'Caused by.*|kafka_dlt-original-consumer-group:[a-z-]+|\{"eventId.*'
Caused by: com.quorum.attendanceservice.event.InvalidEventException: Student ID must be exactly 10 digits: 12AB
```

## d) checkin-service steps 0–7 with curl taps

Today (Friday 27 Nov, IST) a session 17:00–23:00 in Lab 1, and a timetable (made-up IDs) that keeps the sample sections and adds S15 on Friday 18:00–19:00. The device key printed by `POST /devices` is redacted here.

```text
--- step 0: a session running now in Lab 1, a clash for it
$ date '+%F %T %Z (%A)'
2026-11-27 18:24:09 IST (Friday)

$ curl -s -w ' HTTP %{http_code}\n' -H X-User-Id:coord-1 -H X-User-Role:COORDINATOR -H Content-Type:application/json -d '{"companyName":"Globex","driveDate":"2026-11-27","startTime":"17:00","endTime":"23:00","venue":"Lab Block"}' localhost:8081/drives
{"id":2,"companyName":"Globex","driveDate":"2026-11-27","startTime":"17:00:00","endTime":"23:00:00","venue":"Lab Block"} HTTP 201

$ curl -s -w ' HTTP %{http_code}\n' -H X-User-Id:coord-1 -H X-User-Role:COORDINATOR -H Content-Type:application/json -d '{"name":"Coding test","sequence":1}' localhost:8081/drives/2/rounds
{"id":3,"driveId":2,"name":"Coding test","sequence":1,"sessions":[]} HTTP 201

$ curl -s -w ' HTTP %{http_code}\n' -H X-User-Id:coord-1 -H X-User-Role:COORDINATOR -H Content-Type:application/json -d '{"date":"2026-11-27","startTime":"17:00","endTime":"23:00","room":"Lab 1"}' localhost:8081/rounds/3/sessions
{"id":5,"roundId":3,"driveId":2,"date":"2026-11-27","startTime":"17:00:00","endTime":"23:00:00","room":"Lab 1","status":"SCHEDULED"} HTTP 201

$ curl -s -w ' HTTP %{http_code}\n' -F file=@/tmp/claude-1000/-mnt-newvolume-capstone-OD-Attendence/ed954ce1-13b8-4821-9e11-6678c3ccb2ce/scratchpad/data/timetable_live.xlsx -F semester=2026-ODD localhost:8090/imports/timetable | cut -c1-260
{"uploadId":"1c3de97e-3f62-4996-9661-e5b7a462196f","type":"TIMETABLE","targetId":"2026-ODD","fileName":"timetable_live.xlsx","totalRows":27,"acceptedCount":27,"rejectedCount":0,"rejected":[],"status":"PUBLISHED","uploadedAt":"2026-11-27T18:24:09"} HTTP 200

$ curl -s -w ' HTTP %{http_code}\n' -F file=@/tmp/claude-1000/-mnt-newvolume-capstone-OD-Attendence/ed954ce1-13b8-4821-9e11-6678c3ccb2ce/scratchpad/data/shortlist_r3.csv -F roundId=3 localhost:8090/imports/shortlist | cut -c1-200
{"uploadId":"761086dc-6574-44cf-97c4-eac8e06a365d","type":"SHORTLIST","targetId":"3","fileName":"shortlist_r3.csv","totalRows":2,"acceptedCount":2,"rejectedCount":0,"rejected":[],"status":"PUBLISHED",

$ curl -s 'localhost:8084/sessions?date=2026-11-27'
[{"sessionId":5,"roundId":3,"driveId":2,"date":"2026-11-27","startTime":"17:00:00","endTime":"23:00:00","room":"Lab 1","status":"SCHEDULED"}]
$ curl -s 'localhost:8083/clashes?roundId=3&date=2026-11-27'
[{"roundId":3,"date":"2026-11-27","semester":"2026-ODD","windowStart":"17:00:00","windowEnd":"23:00:00","clashes":[{"studentId":"9900000021","sectionCode":"S15","courseName":"Distributed Systems","dayOfWeek":"FRIDAY","slotStart":"18:00:00","slotEnd":"19:00:00","room":"C-110"},{"studentId":"9900000022","sectionCode":"S15","courseName":"Distributed Systems","dayOfWeek":"FRIDAY","slotStart":"18:00:00","slotEnd":"19:00:00","room":"C-110"}],"lastPublished":{"eventId":"414fdaa8-1f75-42e3-b23c-749196343a6a","queuedAt":"2026-11-27T12:54:09.919595Z","clashCount":2,"current":true}}]
$ curl -s 'localhost:8083/clashes?roundId=1&date=2026-11-30' | python3 -c 'import json,sys; r=json.load(sys.stdin)[0]; print(len(r["clashes"]), "clashes, current:", r["lastPublished"]["current"])'
10 clashes, current: True

--- step 1: register the reader (DIRECTOR only)
$ curl -s -w ' HTTP %{http_code}\n' -H X-User-Id:coord-1 -H X-User-Role:COORDINATOR -H Content-Type:application/json -d '{"deviceId":"R-LAB-1","room":"Lab 1"}' localhost:8084/devices
{"status":403,"error":"Forbidden","code":"FORBIDDEN","message":"Only a DIRECTOR may do this","errors":[]} HTTP 403

$ curl -s -H X-User-Id:dir-1 -H X-User-Role:DIRECTOR -H Content-Type:application/json -d '{"deviceId":"R-LAB-1","room":"Lab 1"}' localhost:8084/devices
{"deviceId":"R-LAB-1","room":"Lab 1","active":true,"apiKey":"R-LAB-1.<secret redacted>","note":"Store this key now; it is not shown again. Send it as the X-Device-Key header."}

--- step 2: unknown card
$ curl -s -w ' HTTP %{http_code}\n' -H X-Device-Key:$KEY -H Content-Type:application/json -d '{"cardUid":"04:a3:1b:2c"}' localhost:8084/checkins/tap
{"status":404,"error":"Not Found","code":"UNKNOWN_CARD","message":"Card 04A31B2C is not enrolled; it is listed in GET /cards/unknown-taps","errors":[]} HTTP 404

$ curl -s localhost:8084/cards/unknown-taps
[{"cardUid":"04A31B2C","firstSeenAt":"2026-11-27T12:54:33.583513Z","lastSeenAt":"2026-11-27T12:54:33.583513Z","tapCount":1,"lastDeviceId":"R-LAB-1","lastRoom":"Lab 1"}]
--- step 3: enrol it
$ curl -s -w ' HTTP %{http_code}\n' -H X-User-Id:coord-1 -H X-User-Role:COORDINATOR -H Content-Type:application/json -d '{"cardUid":"04A31B2C","studentId":"9900000021"}' localhost:8084/cards
{"cardUid":"04A31B2C","studentId":"9900000021","enrolledBy":"coord-1","enrolledAt":"2026-11-27T12:54:33.688588526Z"} HTTP 201

$ curl -s localhost:8084/cards/unknown-taps
[]
--- step 4: tap -> MARKED
$ curl -s -w ' HTTP %{http_code} in %{time_total}s\n' -H X-Device-Key:$KEY -H Content-Type:application/json -d '{"cardUid":"04A31B2C"}' localhost:8084/checkins/tap
{"result":"MARKED","cardUid":"04A31B2C","studentId":"9900000021","sessionId":5,"roundId":3,"room":"Lab 1","deviceId":"R-LAB-1","markId":"349c163a-729e-4dba-a8f6-5ccadf00bb20","markedAt":"2026-11-27T12:54:33.765458236Z","tappedAt":"2026-11-27T12:54:33.765458236Z"} HTTP 200 in 0.043138s

--- step 5: tap again -> ALREADY_MARKED
$ curl -s -w ' HTTP %{http_code} in %{time_total}s\n' -H X-Device-Key:$KEY -H Content-Type:application/json -d '{"cardUid":"04A31B2C"}' localhost:8084/checkins/tap
{"result":"ALREADY_MARKED","cardUid":"04A31B2C","studentId":"9900000021","sessionId":5,"roundId":3,"room":"Lab 1","deviceId":"R-LAB-1","markId":"349c163a-729e-4dba-a8f6-5ccadf00bb20","markedAt":"2026-11-27T12:54:33.765458Z","tappedAt":"2026-11-27T12:54:33.827108391Z"} HTTP 200 in 0.022565s

--- step 6: OD appears
$ curl -s 'localhost:8082/od?date=2026-11-27'
[{"studentId":"9900000021","date":"2026-11-27","sectionCode":"S15","courseName":"Distributed Systems","slotStart":"18:00:00","slotEnd":"19:00:00","sessionId":5,"roundId":3,"reason":"Attended session 5 (17:00-23:00) of placement round 3","status":"GRANTED","version":1,"updatedAt":"2026-11-27T12:54:33.955651Z"}]
$ docker compose exec -T kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic od-events --partition 0 --offset earliest --timeout-ms 8000 --property print.key=true 2>/dev/null | grep 9900000021
9900000021	{"eventId":"dcf36a61-f922-44af-b354-7d98d2cc3372","eventType":"OD_GRANTED","schemaVersion":1,"occurredAt":"2026-11-27T12:54:33.958Z","studentId":"9900000021","date":"2026-11-27","sectionCode":"S15","courseName":"Distributed Systems","slotStart":"18:00:00","slotEnd":"19:00:00","sessionId":5,"reason":"Attended session 5 (17:00-23:00) of placement round 3"}

--- step 7: wrong key 401, inactive 403, cancelled 409
$ curl -s -w ' HTTP %{http_code}\n' -H X-Device-Key:R-LAB-1.wrongsecret -H Content-Type:application/json -d '{"cardUid":"04A31B2C"}' localhost:8084/checkins/tap
{"status":401,"error":"Unauthorized","code":"INVALID_DEVICE_KEY","message":"Unknown or wrong X-Device-Key","errors":[]} HTTP 401

$ curl -s -w ' HTTP %{http_code}\n' -H Content-Type:application/json -d '{"cardUid":"04A31B2C"}' localhost:8084/checkins/tap
{"status":401,"error":"Unauthorized","code":"INVALID_DEVICE_KEY","message":"Missing X-Device-Key header","errors":[]} HTTP 401

$ curl -s -w ' HTTP %{http_code}\n' -H X-User-Id:dir-1 -H X-User-Role:DIRECTOR -H Content-Type:application/json -X PATCH -d '{"active":false}' localhost:8084/devices/R-LAB-1
{"deviceId":"R-LAB-1","room":"Lab 1","active":false,"createdBy":"dir-1","createdAt":"2026-11-27T12:54:33.366527Z","lastSeenAt":"2026-11-27T12:54:33.827108Z"} HTTP 200

$ curl -s -w ' HTTP %{http_code}\n' -H X-Device-Key:$KEY -H Content-Type:application/json -d '{"cardUid":"04A31B2C"}' localhost:8084/checkins/tap
{"status":403,"error":"Forbidden","code":"DEVICE_INACTIVE","message":"Device R-LAB-1 is not active","errors":[]} HTTP 403

$ curl -s -w ' HTTP %{http_code}\n' -H X-User-Id:dir-1 -H X-User-Role:DIRECTOR -H Content-Type:application/json -X PATCH -d '{"active":true}' localhost:8084/devices/R-LAB-1
{"deviceId":"R-LAB-1","room":"Lab 1","active":true,"createdBy":"dir-1","createdAt":"2026-11-27T12:54:33.366527Z","lastSeenAt":"2026-11-27T12:54:33.827108Z"} HTTP 200

$ curl -s -w ' HTTP %{http_code}\n' -H X-User-Id:coord-1 -H X-User-Role:COORDINATOR -X POST localhost:8081/sessions/5/cancel
{"id":5,"roundId":3,"driveId":2,"date":"2026-11-27","startTime":"17:00:00","endTime":"23:00:00","room":"Lab 1","status":"CANCELLED"} HTTP 200

$ curl -s -w ' HTTP %{http_code}\n' -H X-Device-Key:$KEY -H Content-Type:application/json -d '{"cardUid":"04A31B2C"}' localhost:8084/checkins/tap
{"status":409,"error":"Conflict","code":"SESSION_CANCELLED","message":"Session 5 in room Lab 1 is cancelled and takes no new taps","errors":[]} HTTP 409

$ curl -s 'localhost:8082/od?date=2026-11-27' | cut -c1-200
[{"studentId":"9900000021","date":"2026-11-27","sectionCode":"S15","courseName":"Distributed Systems","slotStart":"18:00:00","slotEnd":"19:00:00","sessionId":5,"roundId":3,"reason":"Attended session 5

$ curl -s -w ' HTTP %{http_code}\n' -H X-User-Id:dir-1 -H X-User-Role:DIRECTOR -H Content-Type:application/json -X PATCH -d '{"room":"Hall Z"}' localhost:8084/devices/R-LAB-1
{"deviceId":"R-LAB-1","room":"Hall Z","active":true,"createdBy":"dir-1","createdAt":"2026-11-27T12:54:33.366527Z","lastSeenAt":"2026-11-27T12:54:49.382655Z"} HTTP 200

$ curl -s -w ' HTTP %{http_code}\n' -H X-Device-Key:$KEY -H Content-Type:application/json -d '{"cardUid":"04A31B2C"}' localhost:8084/checkins/tap
{"status":409,"error":"Conflict","code":"NO_ACTIVE_SESSION","message":"No session is running in room Hall Z at 2026-11-27T12:54:49.475284909Z","errors":[]} HTTP 409

$ curl -s localhost:8084/devices
[{"deviceId":"R-LAB-1","room":"Hall Z","active":true,"createdBy":"dir-1","createdAt":"2026-11-27T12:54:33.366527Z","lastSeenAt":"2026-11-27T12:54:49.475285Z"}]
--- after fix 30b00e3 (rebuilt checkin-service)
$ curl -s -w ' HTTP %{http_code}\n' -H X-Device-Key:$KEY -H Content-Type:application/json -d '{"cardUid":"04A31B2C"}' localhost:8084/checkins/tap
{"status":409,"error":"Conflict","code":"NO_ACTIVE_SESSION","message":"No session is running in room Hall Z at 18:27 on 2026-11-27 (Asia/Kolkata)","errors":[]} HTTP 409
```

## e) Frontend in headless Chrome (puppeteer-core 24, Chrome 158) against http://localhost:5173

Each script clicked through the UI; every `/api` request the page made is logged with its status (`[net]`), plus console errors. Successful GETs of `/outbox` and `/openapi.json` (the sidebar health pings) are omitted. The only console errors are the deliberate 400/403/502 cases.

```text
== e1: drives list, sidebar health, create drive
  [net] GET /api/placement/drives -> 200
Quorum | Placement office | Drives | Create drive | Excel import | Clashes | OD records | Corrections | Readers & cards | Services connected | Publishing to Kafka | SIGNED IN AS | Temporary | Coordinator | Director | User id | Sent as X-User-Id and X-User-Role until real login.
Drives | Each drive you create is published as an event. Attendance picks it up even if it was offline at the time. | Create drive | Upcoming | Past | All | Search company or venue | ID	Company	Date	Time in the college day	Venue	Event | #2	Globex	Fri, 27 Nov	 | 17:00 – 23:00 | 6 h | 	Lab Block	 | Published | #1	Acme Corp	Mon, 30 Nov	 | 09:00 – 17:00 | 8 h | 	Main Block	 | Published | Bar shows the drive against the college day, 8:00 to 17:00 | 2 upcoming drives
shot ok
  [net] POST /api/placement/drives -> 201 {"id":3,"companyName":"Initech","driveDate":"2026-11-30","startTime":"09:00:00","endTime":"17:00:00","venue":"Seminar Hall, C Block"}
  [net] GET /api/placement/drives -> 200
url after create: /drives
Drives | Each drive you create is published as an event. Attendance picks it up even if it was offline at the time. | Create drive | Drive #3 created and published to Kafka | Upcoming | Past | All | Search company or venue | ID	Company	Date	Time in the college day	Venue	Event | #3	Initech	Mon, 30 Nov	 | 09:00 – 17:00 | 8 h | 	Seminar Hall, C Block	 | Published | #2	Globex	Fri, 27 Nov	 | 17:00 – 23:00 | 6 h | 	Lab Block	 | Published | #1	Acme Corp	Mon, 30 Nov	 | 09:00 – 17:00 | 8 h | 	Main Block	 | Published | Bar shows the drive against the college day, 8:00 to 17:00 | 3 upcoming drives
== e2: rounds and sessions on drive #3
  [net] GET /api/placement/drives/3 -> 200
  [net] POST /api/placement/drives/3/rounds -> 201 {"id":4,"driveId":3,"name":"Aptitude","sequence":1,"sessions":[]}
  [net] GET /api/placement/drives/3 -> 200
  [net] POST /api/placement/rounds/4/sessions -> 201 {"id":6,"roundId":4,"driveId":3,"date":"2026-11-30","startTime":"09:30:00","endTime":"10:15:00","room":"Hall B","status":"SCHEDULED"}
  [net] GET /api/placement/drives/3 -> 200
  [net] POST /api/placement/rounds/4/sessions -> 201 {"id":7,"roundId":4,"driveId":3,"date":"2026-11-30","startTime":"10:30:00","endTime":"11:15:00","room":"Hall B","status":"SCHEDULED"}
  [net] GET /api/placement/drives/3 -> 200
  [net] POST /api/placement/drives/3/rounds -> 201 {"id":5,"driveId":3,"name":"HR","sequence":2,"sessions":[]}
  [net] GET /api/placement/drives/3 -> 200
  [net] POST /api/placement/rounds/5/sessions -> 201 {"id":8,"roundId":5,"driveId":3,"date":"2026-11-30","startTime":"14:00:00","endTime":"15:00:00","room":"Room 102","status":"SCHEDULED"}
  [net] GET /api/placement/drives/3 -> 200
Drives | Initech | Drive #3 | Mon, 30 Nov | 09:00 – 17:00 | Seminar Hall, C Block | Add round | Session #8 on Mon, 30 Nov added and published. | Round 1 · Aptitude | Round #4 · 2 sessions | Class clashes | Add session | Session	Date	Time	Room	Status	 | Actions | #6	Mon, 30 Nov	 | 09:30 – 10:15 | 45 min | 	Hall B	 | Scheduled	 | Check-in | Reschedule | Cancel | #7	Mon, 30 Nov	 | 10:30 – 11:15 | 45 min | 	Hall B	 | Scheduled	 | Check-in | Reschedule | Cancel | Round 2 · HR | Round #5 · 1 session | Class clashes | Add session | Session	Date	Time	Room	Status	 | Actions | #8	Mon, 30 Nov	 | 14:00 – 15:00 | 1 h | 	Room 102	 | Scheduled	 | Check-in | Reschedule | Cancel
== e2b: reschedule and cancel session #8 in the UI
  [net] GET /api/placement/drives/3 -> 200
  [net] PUT /api/placement/sessions/8 -> 200 {"id":8,"roundId":5,"driveId":3,"date":"2026-11-30","startTime":"14:30:00","endTime":"15:30:00","room":"Room 102","status":"SCHEDULED"}
  [net] GET /api/placement/drives/3 -> 200
  [net] POST /api/placement/sessions/8/cancel -> 200 {"id":8,"roundId":5,"driveId":3,"date":"2026-11-30","startTime":"14:30:00","endTime":"15:30:00","room":"Room 102","status":"CANCELLED"}
  [net] GET /api/placement/drives/3 -> 200
Round 2 · HR | Round #5 · 1 session | Class clashes | Add session | Session	Date	Time	Room	Status	 | Actions | #8	Mon, 30 Nov	 | 14:30 – 15:30 | 1 h | 	Room 102	 | Cancelled	 | Check-in
== e3: Excel import page
  [net] POST /api/import/imports/timetable -> 200 {"uploadId":"5ba3b145-28e2-44f0-96cd-8f24d8db8aa2","type":"TIMETABLE","targetId":"2026-ODD","fileName":"timetable_live.xlsx","totalRows":27,"acceptedCount":27,"rejectedCount":0,"rejected":[],"status":"PUBLISHED","uploade
  panel: Semester | Download template | File | timetable_live.xlsx | 6 KB, ready to check | Change file | Upload and check | timetable_live.xlsx | Semester 2026-ODD, checked at 18:30 | Published | Total rows | 27 | Accepted | 27 | Rejected | 0 | Every row was accepted.
  [net] POST /api/import/imports/shortlist -> 200 {"uploadId":"32627dc3-3afa-466e-bc06-cbf7d533a41a","type":"SHORTLIST","targetId":"4","fileName":"shortlist_valid.xlsx","totalRows":25,"acceptedCount":25,"rejectedCount":0,"rejected":[],"status":"PUBLISHED","uploadedAt":"
  panel: Round ID | Download template | File | shortlist_valid.xlsx | 5 KB, ready to check | Change file | Upload and check | shortlist_valid.xlsx | Round 4, checked at 18:31 | Published | Total rows | 25 | Accepted | 25 | Rejected | 0 | Every row was accepted.
  [net] POST /api/import/imports/shortlist -> 200 {"uploadId":"ab900381-bb33-481a-8d6d-3ee7ddf027fd","type":"SHORTLIST","targetId":"5","fileName":"shortlist_with_errors.xlsx","totalRows":8,"acceptedCount":4,"rejectedCount":4,"rejected":[{"row":7,"value":"99000004","reas
  panel: Round ID | Download template | File | shortlist_with_errors.xlsx | 5 KB, ready to check | Change file | Upload and check | shortlist_with_errors.xlsx | Round 5, checked at 18:31 | Published | Total rows | 8 | Accepted | 4 | Rejected | 4 | Row	Value in the file	Why it was rejected | 7	99000004	Not 10 digits | 8	99AB000005	Contains something other than digits | 9	(empty)	Empty cell | 11	9900000001	Duplicate of an earlier row
  template download: 200 application/vnd.openxmlformats-officedocument.spreadsheetml.sheet
== e4: clashes, from the drive page link
  [net] GET /api/placement/drives/3 -> 200
  [net] GET /api/placement/drives -> 200
  [net] GET /api/placement/drives/3 -> 200
  [net] GET /api/timetable/clashes?roundId=4&date=2026-11-30 -> 200
  [net] GET /api/placement/drives/3 -> 200
  url: /clashes?driveId=3&roundId=4&date=2026-11-30
  page: Clashes | Class slots of shortlisted students that overlap a round's placement window, computed by timetable-service from the active timetable. | Drive | Choose a drive | #3 Initech, Mon, 30 Nov | #2 Globex, Fri, 27 Nov | #1 Acme Corp, Mon, 30 Nov | Round | Choose a round | Round 1 · Aptitude (#4) | Round 2 · HR (#5) | Date | Every session date | Mon, 30 Nov | Mon, 30 Nov · Monday | Round #4 · Semester 2026-ODD · 10 clashes | Published 27 Nov, 18:31:00 | Placement window | 09:30 – 11:15 | Earliest session start to latest session end of this round on this date, cancelled sessions included. | 8:00 | 10:00 | 12:00 | 14:00 | 17:00 | Student ID	Section	Course	Class slot	Room | 9900000001	 … (trimmed)
== e5: add a Wednesday session in the UI, then mark by hand on its check-in page
  [net] GET /api/placement/drives/3 -> 200
  [net] POST /api/placement/rounds/4/sessions -> 201 {"id":9,"roundId":4,"driveId":3,"date":"2026-12-02","startTime":"10:30:00","endTime":"11:30:00","room":"Hall B","status":"SCHEDULED"}
  [net] GET /api/placement/drives/3 -> 200
  new session 9
  [net] GET /api/placement/sessions/9 -> 200
  [net] GET /api/checkin/sessions/9/marks -> 200
  [net] POST /api/checkin/sessions/9/marks -> 201 {"markId":"56e0f7cb-0a84-4ab8-8bcb-771419eef793","sessionId":9,"roundId":4,"studentId":"9900000004","version":1,"present":true,"source":"MANUAL","markedBy":"coord-1","markedAt":"2026-11-27T13:01:51.321385869Z","createdAt
  [net] GET /api/checkin/sessions/9/marks -> 200
  [net] POST /api/checkin/sessions/9/marks -> 201 {"markId":"fc02b7a6-8f1a-4a6d-9322-2c759b0f16ba","sessionId":9,"roundId":4,"studentId":"9900000005","version":1,"present":true,"source":"MANUAL","markedBy":"coord-1","markedAt":"2026-11-27T13:01:52.861565581Z","createdAt
  [net] GET /api/checkin/sessions/9/marks -> 200
  [net] GET /api/checkin/sessions/9/marks -> 200
  [net] POST /api/checkin/sessions/9/marks -> 201 {"markId":"38d0142c-d6b9-4a33-86f4-2709e6367dd2","sessionId":9,"roundId":4,"studentId":"9900000024","version":1,"present":false,"source":"MANUAL","markedBy":"coord-1","markedAt":"2026-11-27T13:01:54.423918371Z","createdA
  [net] GET /api/checkin/sessions/9/marks -> 200
  [net] GET /api/checkin/sessions/9/marks -> 200
  page: Drive #3 | Session #9 check-in | Round #4 | Wed, 2 Dec | 10:30 – 11:30 | Hall B | Scheduled | Correct a mark | Marks from checkin-service | Latest mark per student. Taps on the room's reader appear here by themselves. | Live · 18:31:56 | Student ID	Status	Source	Marked by	Marked at	 | Actions | 9900000004	 | Present	MANUAL	coord-1	27 Nov, 18:31:51	Correct | 9900000005	 | Present	MANUAL	coord-1	27 Nov, 18:31:52	Correct | 9900000024	 | Absent	MANUAL	coord-1	27 Nov, 18:31:54	Correct | RFID = card tap · MANUAL = marked on this page · FILE = attendance upload | 3 marked | Present vs marked | Present | 2 | Absent | 1 | Marked | 3 | Mark by hand | For a forgotten or broken card. A tap never … (trimmed)
== e6: OD page for Wed 2 Dec
  [net] GET /api/attendance/od?date=2026-12-02&includeRevoked=false -> 200
  page: OD records | On-Duty granted by attendance-service for class slots that clashed with a placement session the student attended. No faculty approval is needed. | Download CSV | Date | Student ID | Show revoked | Student ID	Course	Class slot	Session	Reason	Status | 9900000004	Cloud Native | S12 | 	11:00 – 11:50	#9 | round #4 | 	Attended session 9 (10:30-11:30) of placement round 4	 | Granted | 9900000005	Cloud Native | S12 | 	11:00 – 11:50	#9 | round #4 | 	Attended session 9 (10:30-11:30) of placement round 4	 | Granted | Wednesday 2 Dec · the CSV holds the granted OD of this date | 2 granted slots · 2 students
  CSV via the UI proxy: 200 attachment; filename="od-2026-12-02.csv" ⏎ studentId,date,course,section,slotStart,slotEnd,sessionId,reason ⏎ 9900000004,2026-12-02,Cloud Native,S12,11:00,11:50,9,Attended session 9 (10:30-11:30) of placement round 4 ⏎ 9900000005,2026-12-02,Cloud Native,S12,11:00,11:50,9,Attended session 9 (10:30-11:30) of placement round 4 ⏎ 
  [net] GET /api/attendance/od/export?date=2026-12-02 -> 200
  [net] GET /api/attendance/od/export?date=2026-12-02 -> 200
== e7: correction from the session page "Correct" link
  [net] GET /api/placement/sessions/9 -> 200
  [net] GET /api/checkin/sessions/9/marks -> 200
  [net] GET /api/placement/drives -> 200
  [net] GET /api/attendance/corrections?sessionId=9 -> 200
  [net] GET /api/attendance/marks/sessions/9 -> 200
  [net] GET /api/placement/sessions/9 -> 200
  [net] GET /api/placement/drives/3 -> 200
  [net] GET /api/placement/drives/3 -> 200
  url: /corrections?sessionId=9&studentId=9900000004&driveId=3&roundId=4
  [net] POST /api/attendance/corrections -> 201 {"id":5,"sessionId":9,"studentId":"9900000004","oldPresent":true,"newPresent":false,"reason":"Left before the session started; seen leaving by the coordinator","correctedBy":"coord-1","role":"COORDINATOR","correctedAt":"
  [net] GET /api/attendance/corrections?sessionId=9 -> 200
  [net] GET /api/attendance/marks/sessions/9 -> 200
  page: Corrections | Change a student's mark for a session. No approval step: a Coordinator may correct up to 14 days after the session, a Director at any time. Every correction keeps the old value, who made it and why. | 9900000004 is now absent in session #9 (mark version 2). OD is recomputed right away. | Drive | Choose a drive | #3 Initech, Mon, 30 Nov | #2 Globex, Fri, 27 Nov | #1 Acme Corp, Mon, 30 Nov | Round | Choose a round | Round 1 · Aptitude (#4) | Round 2 · HR (#5) | Session | Choose a session | #6 Mon, 30 Nov, 09:30 – 10:15, Hall B | #7 Mon, 30 Nov, 10:30 – 11:15, Hall B | #9 Wed, 2 Dec, 10:30 – 11:30, Hall B | Session #9 on Wed, 2 Dec, 10:30 – 11:30, Hall B. Coordinators may  … (trimmed)
  [net] GET /api/attendance/od?date=2026-12-02&includeRevoked=true -> 200
  OD with revoked: Student ID | Show revoked | Student ID	Course	Class slot	Session	Reason	Status | 9900000004	Cloud Native | S12 | 	11:00 – 11:50	#9 | round #4 | 	No longer marked present in any session of round 4	 | Revoked | version 2 | 9900000005	Cloud Native | S12 | 	11:00 – 11:50	#9 | round #4 | 	Attended session 9 (10:30-11:30) of placement round 4	 | Granted | Wednesday 2 Dec · the CSV holds the granted OD of this date | 1 granted slot · 1 student
== e8: 14-day rule in the UI (coordinator), then as Director
  [net] GET /api/placement/sessions/4 -> 200
  [net] GET /api/placement/drives -> 200
  [net] GET /api/attendance/marks/sessions/4 -> 200
  [net] GET /api/attendance/corrections?sessionId=4 -> 200
  [net] GET /api/placement/drives/1 -> 200
  [net] GET /api/placement/drives/1 -> 200
  before save: Drive | Choose a drive | #3 Initech, Mon, 30 Nov | #2 Globex, Fri, 27 Nov | #1 Acme Corp, Mon, 30 Nov | Round | Choose a round | Round 1 · Aptitude (#1) | Round 2 · Technical (#2) | Session | Choose a session | #4 Mon, 2 Nov, 09:00 – 10:00, Room 101 | #3 Mon, 30 Nov, 14:30 – 15:30, Room 101 (cancelled) | Session #4 on Mon, 2 Nov, 09:00 – 10:00, Room 101. Coordinators may correct until Mon, 16 Nov. | This session was more than 14 days ago (last day for a Coordinator was Mon, 16 Nov). attendance-s
  [console.error] Failed to load resource: the server responded with a status of 403 ()
  [net] POST /api/attendance/corrections -> 403 {"status":403,"error":"Forbidden","message":"A COORDINATOR may correct attendance only up to 14 days after the session date (session on 2026-11-02, last day 2026-11-16, today 2026-11-27). Ask a DIRECTOR.","errors":[]}
  after save as coordinator: Corrections | Change a student's mark for a session. No approval step: a Coordinator may correct up to 14 days after the session, a Director at any time. Every correction keeps the old value, who made it and why. | Refused: outside the Coordinator's 14-day window | A COORDINATOR may correct attendance only up to 14 days after the session date (session on 2026-11-02, last day 2026-11-16, today 2026
  [net] POST /api/attendance/corrections -> 201 {"id":6,"sessionId":4,"studentId":"9900000002","oldPresent":null,"newPresent":true,"reason":"Signed the paper sheet","correctedBy":"coord-1","role":"DIRECTOR","correctedAt":"2026-11-27T13:03:07.138260121Z","markId":"CORR
  [net] GET /api/attendance/corrections?sessionId=4 -> 200
  [net] GET /api/attendance/marks/sessions/4 -> 200
  after save as director: Corrections | Change a student's mark for a session. No approval step: a Coordinator may correct up to 14 days after the session, a Director at any time. Every correction keeps the old value, who made it and why. | 9900000002 is now present in session #4 (mark version 1). OD is recomputed right away
== e9: Readers & cards as Director
  [net] GET /api/checkin/devices -> 200
  [net] GET /api/checkin/cards/unknown-taps -> 200
  [net] POST /api/checkin/devices -> 201 {"deviceId":"R-HALL-B","room":"Hall B","active":true,"apiKey":"R-HALL-B.<secret redacted>","note":"Store this key now; it is not shown again. Send it as the X-Device-Key header."}
  [net] GET /api/checkin/devices -> 200
== e9: Readers & cards as Director
  [net] GET /api/checkin/cards/unknown-taps -> 200
  [net] GET /api/checkin/devices -> 200
  [net] POST /api/checkin/devices -> 201 {"deviceId":"R-LIB-1","room":"Library","active":true,"apiKey":"R-LIB-1.<secret redacted>","note":"Store this key now; it is not shown again. Send it as the X-Device-Key header."}
  [net] GET /api/checkin/devices -> 200
  key reveal: Key for R-LIB-1 (Library) | This key is shown only once. Copy it into the reader's secrets.h as DEVICE_KEY now. Only a hash is stored, so it cannot be shown again; a lost key means issuing a new one. | Device key | Copy key | I've stored the key | key input holds R-LIB-1.<secret redacted> (40 chars)
  [net] POST /api/checkin/cards/import -> 200 {"imported":2,"unchanged":0,"total":2}
  import cards.csv: Import cards | CSV with columns studentId,cardUid (header optional). Any bad line refuses the whole file. | File | Drag the file here | studentId,cardUid per line, .csv | Choose file | Import cards | 2 new cards enrolled, 0 already enrolled, 2 in the file.
  [console.error] Failed to load resource: the server responded with a status of 400 ()
  [net] POST /api/checkin/cards/import -> 400 {"status":400,"error":"Bad Request","code":"CSV_INVALID","message":"2 line(s) are wrong; nothing was imported","errors":[{"field":"line 1","message":"cardUid must be 4, 7 or 10 bytes of hex (8, 14 or 20 hex digits): XYZ"
  import cards_bad.csv: Import cards | CSV with columns studentId,cardUid (header optional). Any bad line refuses the whole file. | File | cards_bad.csv | 28 bytes, ready to check | Change file | Import cards | 2 line(s) are wrong; nothing was imported | Where	Problem | line 1	cardUid must be 4, 7 or 10 bytes of hex (8, 14 or 20 hex digits): XYZ | line 2	studentId must be exactly 10 digits: 123
  -- an unknown card taps the Library reader (curl, as the ESP32 would)
  $ curl -H "X-Device-Key: R-LIB-1.<secret>" -d '{"cardUid":"04C0FFEE"}' localhost:8084/checkins/tap
  {"status":404,"error":"Not Found","code":"UNKNOWN_CARD","message":"Card 04C0FFEE is not enrolled; it is listed in GET /cards/unknown-taps","errors":[]} HTTP 404
  [net] GET /api/checkin/devices -> 200
  [net] GET /api/checkin/cards/unknown-taps -> 200
  unknown taps: Unknown taps | Cards tapped on a reader but not enrolled yet, most recent first. | Refresh | Card UID	Taps	Last tap	Last reader	Enrol to student | 04C0FFEE	1	27 Nov, 18:33:54	R-LIB-1 | Library | 	 | Student ID for card 04C0FFEE | Enrol
  [net] POST /api/checkin/cards -> 201 {"cardUid":"04C0FFEE","studentId":"9900000006","enrolledBy":"coord-1","enrolledAt":"2026-11-27T13:03:56.777766516Z"}
  [net] GET /api/checkin/cards/unknown-taps -> 200
  after enrol: Unknown taps | Cards tapped on a reader but not enrolled yet, most recent first. | Refresh | Card 04C0FFEE enrolled to 9900000006. Their next tap counts. | No unknown taps | When someone taps a card that is not enrolled, it is listed here to enrol.
  [net] PATCH /api/checkin/devices/R-LIB-1 -> 200 {"deviceId":"R-LIB-1","room":"Library","active":false,"createdBy":"coord-1","createdAt":"2026-11-27T13:03:49.262062Z","lastSeenAt":"2026-11-27T13:03:54.858916Z"}
  [net] GET /api/checkin/devices -> 200
  readers after Deactivate: R-LIB-1 switched off. Its taps are now refused with 403. | Reader	Room	State	Last tap	Registered	 | Actions 
  tap on the switched-off reader: {"status":403,"error":"Forbidden","code":"DEVICE_INACTIVE","message":"Device R-LIB-1 is not active","errors":[]} HTTP 403
  [net] PATCH /api/checkin/devices/R-LIB-1 -> 200 {"deviceId":"R-LIB-1","room":"Library","active":true,"createdBy":"coord-1","createdAt":"2026-11-27T13:03:49.262062Z","lastSeenAt":"2026-11-27T13:03:54.858916Z"}
  [net] GET /api/checkin/devices -> 200
  readers after Activate: R-LIB-1 switched on again. | Reader	Room	State	Last tap	Registered	 | Actions 
  -- Coordinator view of the same page
   Readers | Only a Director registers and switches readers. Switch "Signed in as" to Director to do so. | R-LIB-1 switched on again. | Reader	Room	State	Last tap	 | buttons disabled: true
== e10: attendance-service stopped -> the UI names it (nginx 502 counts as unreachable)
  [console.error] Failed to load resource: the server responded with a status of 502 (Bad Gateway)
  [console.error] Failed to load resource: the server responded with a status of 502 (Bad Gateway)
  [net] GET /api/attendance/outbox?limit=1 -> 502 <html>
<head><title>502 Bad Gateway</title></head>
<body>
<center><h1>502 Bad Gateway</h1></center>
<hr><center>nginx/1.27.5</center>
</body>
</html>
<!-- a padding to disable MSIE and Chrome friendly error page -
  [net] GET /api/attendance/od?date=2026-12-02&includeRevoked=false -> 502 <html>
<head><title>502 Bad Gateway</title></head>
<body>
<center><h1>502 Bad Gateway</h1></center>
<hr><center>nginx/1.27.5</center>
</body>
</html>
<!-- a padding to disable MSIE and Chrome friendly error page -
  page: OD records | On-Duty granted by attendance-service for class slots that clashed with a placement session the student attended. No faculty approval is needed. | Download CSV | Date | Student ID | Show revoked | Can't reach attendance-service on port 8082. Start it and refresh. | Try again | Wednesday 2 Dec · the CSV holds the gra
  sidebar: attendance-service offline | Start it on port 8082 | SIGNED IN AS 
```

## f) Clean start and the README smoke test

`docker compose down -v` (volumes deleted), then `docker compose up -d --build` from empty, then the 10 smoke-test lines from the root README run unchanged (shown with `bash -x`; curl prints no trailing newline, so the next command follows on the same line).

```text
$ docker compose down -v 2>&1 | tail -3
 Volume odattendence_kafka-data  Removed
 Volume odattendence_mysql-data  Removed
 Network odattendence_default  Removed

$ time docker compose up -d --build 2>&1 | tail -4
 Container odattendence-timetable-service-1  Healthy
 Container odattendence-placement-service-1  Healthy
 Container odattendence-frontend-1  Starting
 Container odattendence-frontend-1  Started

real	1m5.612s
user	0m0.914s
sys	0m0.595s

$ docker compose ps --format '{{.Service}}: {{.Status}}'
attendance-service: Up 27 seconds (healthy)
checkin-service: Up 27 seconds (healthy)
frontend: Up Less than a second (health: starting)
import-service: Up 27 seconds (healthy)
kafka: Up 59 seconds (healthy)
mysql: Up 59 seconds (healthy)
placement-service: Up 27 seconds (healthy)
timetable-service: Up 27 seconds (healthy)

$ bash smoke.sh   # the 10 lines from README "Smoke test", unchanged
$ H='-H X-User-Id:coord-1 -H X-User-Role:COORDINATOR -H Content-Type:application/json'
$ curl -s -H X-User-Id:coord-1 -H X-User-Role:COORDINATOR -H Content-Type:application/json -d '{"companyName":"Smoke","driveDate":"2026-11-30","startTime":"09:00","endTime":"17:00","venue":"Hall"}' localhost:8081/drives
{"id":1,"companyName":"Smoke","driveDate":"2026-11-30","startTime":"09:00:00","endTime":"17:00:00","venue":"Hall"}+ curl -s -H X-User-Id:coord-1 -H X-User-Role:COORDINATOR -H Content-Type:application/json -d '{"name":"Aptitude","sequence":1}' localhost:8081/drives/1/rounds
{"id":1,"driveId":1,"name":"Aptitude","sequence":1,"sessions":[]}+ curl -s -H X-User-Id:coord-1 -H X-User-Role:COORDINATOR -H Content-Type:application/json -d '{"date":"2026-11-30","startTime":"09:30","endTime":"10:15","room":"Hall A"}' localhost:8081/rounds/1/sessions
{"id":1,"roundId":1,"driveId":1,"date":"2026-11-30","startTime":"09:30:00","endTime":"10:15:00","room":"Hall A","status":"SCHEDULED"}+ curl -s -F file=@import-service/samples/timetable.xlsx -F semester=2026-ODD localhost:8090/imports/timetable
{"uploadId":"1af40bbe-b831-432d-b97d-14487908fa6a","type":"TIMETABLE","targetId":"2026-ODD","fileName":"timetable.xlsx","totalRows":27,"acceptedCount":24,"rejectedCount":3,"rejected":[{"row":6,"sheet":"slots","value":"SUNDAY","reason":"INVALID_DAY"},{"row":7,"sheet":"slots","value":"12:00-11:00","reason":"START_NOT_BEFORE_END"},{"row":22,"sheet":"enrolments","value":"S99","reason":"UNKNOWN_SECTION"}],"status":"PUBLISHED","uploadedAt":"2026-11-27T18:38:26"}+ curl -s -F file=@import-service/samples/shortlist_valid.xlsx -F roundId=1 localhost:8090/imports/shortlist
{"uploadId":"6a82977c-2840-4752-83d0-d1ce0ae3492d","type":"SHORTLIST","targetId":"1","fileName":"shortlist_valid.xlsx","totalRows":25,"acceptedCount":25,"rejectedCount":0,"rejected":[],"status":"PUBLISHED","uploadedAt":"2026-11-27T18:38:27"}+ sleep 3
$ curl -s 'localhost:8083/clashes?roundId=1&date=2026-11-30'
[{"roundId":1,"date":"2026-11-30","semester":"2026-ODD","windowStart":"09:30:00","windowEnd":"10:15:00","clashes":[{"studentId":"9900000001","sectionCode":"S12","courseName":"Cloud Native","dayOfWeek":"MONDAY","slotStart":"10:00:00","slotEnd":"10:50:00","room":"C-301"},{"studentId":"9900000002","sectionCode":"S12","courseName":"Cloud Native","dayOfWeek":"MONDAY","slotStart":"10:00:00","slotEnd":"10:50:00","room":"C-301"},{"studentId":"9900000003","sectionCode":"S12","courseName":"Cloud Native","dayOfWeek":"MONDAY","slotStart":"10:00:00","slotEnd":"10:50:00","room":"C-301"},{"studentId":"9900000004","sectionCode":"S12","courseName":"Cloud Native","dayOfWeek":"MONDAY","slotStart":"10:00:00","s
{"uploadId":"97815a70-e0d2-4a5e-a79b-1a2ac7b343b2","type":"ATTENDANCE","targetId":"1","fileName":"attendance.csv","totalRows":20,"acceptedCount":20,"rejectedCount":0,"rejected":[],"status":"PUBLISHED","uploadedAt":"2026-11-27T18:38:30"}+ sleep 3
$ curl -s 'localhost:8082/od?date=2026-11-30'
[{"studentId":"9900000001","date":"2026-11-30","sectionCode":"S12","courseName":"Cloud Native","slotStart":"10:00:00","slotEnd":"10:50:00","sessionId":1,"roundId":1,"reason":"Attended session 1 (09:30-10:15) of placement round 1","status":"GRANTED","version":1,"updatedAt":"2026-11-27T13:08:30.836985Z"},{"studentId":"9900000002","date":"2026-11-30","sectionCode":"S12","courseName":"Cloud Native","slotStart":"10:00:00","slotEnd":"10:50:00","sessionId":1,"roundId":1,"reason":"Attended session 1 (09:30-10:15) of placement round 1","status":"GRANTED","version":1,"updatedAt":"2026-11-27T13:08:30.906672Z"},{"studentId":"9900000003","date":"2026-11-30","sectionCode":"S12","courseName":"Cloud Native"
{"pending":0,"latest":[{"id":10,"topic":"od-events","aggregateKey":"9900000010","eventType":"OD_GRANTED","createdAt":"2026-11-27T13:08:31.337105Z","publishedAt":"2026-11-27T13:08:31.850779Z","attempts":1,"lastError":null}]}+ curl -s localhost:8084/sessions/1/marks
$ head -c 300
[{"markId":"9db70201-4019-3687-8d31-6eaac526fe52","sessionId":1,"roundId":1,"studentId":"9900000001","version":1,"present":true,"source":"FILE","markedBy":"import-service","markedAt":"2026-11-27T13:08:30.253950Z","createdAt":"2026-11-27T13:08:30.280034Z"},{"markId":"91938e6f-76ab-3fda-84fa-ea502a6b3
$ curl -s 'localhost:8083/clashes?roundId=1&date=2026-11-30' | python3 -c 'import json,sys; print(len(json.load(sys.stdin)[0]["clashes"]), "clashes")'
10 clashes

$ curl -s 'localhost:8082/od?date=2026-11-30' | python3 -c 'import json,sys; o=json.load(sys.stdin); print(len(o), "OD records,", {r["status"] for r in o})'
10 OD records, {'GRANTED'}

$ curl -s -o /dev/null -w 'UI %{http_code}\n' localhost:5173/ && curl -s -o /dev/null -w 'UI proxy to placement %{http_code}\n' localhost:5173/api/placement/drives
UI 200
UI proxy to placement 200
```
