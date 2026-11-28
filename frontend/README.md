# Quorum frontend

Placement-office console: React 18 + Vite + TypeScript, no UI kit. Design tokens live in
`src/styles/tokens.css` (Public Sans, maroon `#8A1C2B`, 44 px controls).

## Run

```bash
npm install
npm run dev      # http://localhost:5173
npm run build    # tsc --noEmit + vite build; must pass with zero errors
```

The dev server proxies each service and strips the prefix:

| Prefix | Service | Port |
|---|---|---|
| `/api/placement` | placement-service | 8081 |
| `/api/attendance` | attendance-service | 8082 |
| `/api/timetable` | timetable-service | 8083 |
| `/api/checkin` | checkin-service | 8084 |
| `/api/import` | import-service | 8090 |

Every page shows loading, empty and error states. When a service does not answer, the page and the
sidebar name it and its port.

## Temporary sign-in (TEMPORARY, until proper login)

The "Signed in as" panel at the bottom of the sidebar picks **Coordinator** or **Director** and a
user id (default `coord-1`). The choice is kept in `localStorage` (`quorum.temporarySignIn`) and sent
as `X-User-Id` and `X-User-Role` on every write. Nothing is verified; it only stands in for login.
Device endpoints of checkin-service accept Director only; coordinators see those buttons disabled.

## Pages

| Route | What | Calls |
|---|---|---|
| `/drives` | drives; a row opens its detail | placement `GET /drives` |
| `/drives/new` | create a drive | placement `POST /drives` |
| `/drives/:id` | rounds and sessions; add round, add session, reschedule, cancel (confirm on the page). Each round has a **Students** panel: shortlist count, Upload shortlist (roundId filled in), template, the upload report inline, a collapsible ID + name table. Each session row has an **Attendance** button. `?shortlist=<roundId>` opens that round's upload | placement `GET /drives/{id}`, `POST /drives/{id}/rounds`, `POST /rounds/{id}/sessions`, `PUT /sessions/{id}`, `POST /sessions/{id}/cancel`; checkin `GET /rounds/{id}/roster`; import `POST /imports/shortlist` |
| `/sessions/:id/attendance` | the session's attendance sheet, refreshed every 5 s: Present / Absent / Not marked / Total, one row per shortlisted student with a Present/Absent toggle that saves at once, source and last-marked time; search, filter, "Mark all remaining present" (confirmed on the page), Upload attendance file (sessionId filled in) with its report; marked students not on the shortlist at the bottom; read-only when cancelled | placement `GET /sessions/{id}`, `GET /drives/{id}`; checkin `GET /sessions/{id}/attendance-sheet`, `POST /sessions/{id}/marks`; import `POST /imports/attendance` |
| `/sessions/:id` | redirects to `/sessions/:id/attendance` | |
| `/timetable` | semester timetable upload (semester, file, template, report) and the semesters in timetable-service with the active one | import `POST /imports/timetable`; timetable `GET /timetable/semesters` |
| `/imports` | redirects to `/timetable` (the old "Excel import" page) | |
| `/clashes` | window and clash list per round and date | timetable `GET /clashes?roundId=&date=` |
| `/od` | OD by date, student filter, show revoked, CSV | attendance `GET /od`, `GET /od/export` |
| `/corrections` | correct a mark with a reason; 403 outside the coordinator's 14 days; history | attendance `POST /corrections`, `GET /corrections?sessionId=`, `GET /marks/sessions/{id}` |
| `/devices` | readers (one-time key, activate/deactivate), card CSV import, unknown taps with enrolment | checkin `GET`/`POST /devices`, `PATCH /devices/{id}`, `POST /cards/import`, `GET /cards/unknown-taps`, `POST /cards` |

Deep links: `/clashes?driveId=&roundId=&date=`, `/corrections?sessionId=&studentId=`, `/od?date=&revoked=1`.
