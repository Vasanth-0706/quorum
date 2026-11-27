import { Link, useSearchParams } from 'react-router-dom';
import { getClashes, type ClashResult } from '../api';
import { buttonClass } from '../components/Button';
import { DayTrack } from '../components/DayTrack';
import { Field } from '../components/Field';
import { ClashIcon } from '../components/Icons';
import { SessionPicker, useDriveDetail, type Pick } from '../components/SessionPicker';
import { EmptyState, ErrorState, SkeletonRows } from '../components/States';
import { StatusPill } from '../components/StatusPill';
import { formatDriveDate, formatInstant, formatTimeRange, toMinutes, weekdayOf } from '../time';
import { useResource } from '../useResource';

function numberParam(value: string | null): number | null {
  const n = Number(value);
  return value && Number.isInteger(n) && n > 0 ? n : null;
}

export function ClashesPage() {
  const [params, setParams] = useSearchParams();
  const pick: Pick = {
    driveId: numberParam(params.get('driveId')),
    roundId: numberParam(params.get('roundId')),
    sessionId: null,
  };
  const date = params.get('date') ?? '';

  const detail = useDriveDetail(pick.driveId);
  const round = detail.kind === 'ready' ? detail.data.rounds.find((r) => r.id === pick.roundId) ?? null : null;
  const dates = round ? [...new Set(round.sessions.map((s) => s.date))].sort() : [];

  const { state, reload } = useResource<ClashResult[]>(
    pick.roundId ? (signal) => getClashes(pick.roundId!, date || null, signal) : null,
    [pick.roundId, date],
  );

  function update(next: Pick, nextDate: string) {
    const query: Record<string, string> = {};
    if (next.driveId) query.driveId = String(next.driveId);
    if (next.roundId) query.roundId = String(next.roundId);
    if (next.roundId && nextDate) query.date = nextDate;
    setParams(query, { replace: true });
  }

  return (
    <>
      <header className="page-header">
        <div>
          <h1 className="page-title">Clashes</h1>
          <p className="page-lede">
            Class slots of shortlisted students that overlap a round's placement window, computed by timetable-service
            from the active timetable.
          </p>
        </div>
      </header>

      <section className="card card-body" aria-label="Choose a round">
        <div className="picker-with-date">
          <SessionPicker
            value={pick}
            onChange={(next) => {
              // A new round starts on its first session date.
              const nextRound =
                detail.kind === 'ready' ? detail.data.rounds.find((r) => r.id === next.roundId) : undefined;
              const first = nextRound?.sessions.map((s) => s.date).sort()[0] ?? '';
              update(next, next.roundId === pick.roundId ? date : first);
            }}
          />
          <Field id="pick-date" label="Date">
            <select
              className="input"
              value={date}
              disabled={!pick.roundId}
              onChange={(e) => update(pick, e.target.value)}
            >
              <option value="">Every session date</option>
              {dates.map((d) => (
                <option key={d} value={d}>
                  {formatDriveDate(d)}
                </option>
              ))}
              {date && !dates.includes(date) && <option value={date}>{formatDriveDate(date)}</option>}
            </select>
          </Field>
        </div>
      </section>

      {!pick.roundId ? (
        <section className="card">
          <EmptyState icon={<ClashIcon size={20} />} title="Choose a drive and a round">
            <p className="state-text">The clash list is worked out per round and date of its sessions.</p>
          </EmptyState>
        </section>
      ) : state.kind === 'loading' ? (
        <section className="card" aria-busy="true">
          <div className="table-scroll">
            <table className="table compact-table">
              <tbody>
                <SkeletonRows columns={5} />
              </tbody>
            </table>
          </div>
        </section>
      ) : state.kind === 'error' ? (
        <section className="card">
          <ErrorState error={state.error} onRetry={reload} />
        </section>
      ) : state.data.length === 0 ? (
        <section className="card">
          <EmptyState icon={<ClashIcon size={20} />} title="timetable-service has no sessions for this round yet">
            <p className="state-text">
              It learns sessions from drive-events. Add a session on the drive page, or wait a moment and refresh.
            </p>
            {pick.driveId && (
              <Link to={`/drives/${pick.driveId}`} className={buttonClass('secondary', true)}>
                Open the drive
              </Link>
            )}
          </EmptyState>
        </section>
      ) : (
        state.data.map((result) => <ClashCard key={result.date} result={result} />)
      )}
    </>
  );
}

function ClashCard({ result }: { result: ClashResult }) {
  const start = toMinutes(result.windowStart);
  const end = toMinutes(result.windowEnd);
  const titleId = `clash-${result.date}`;
  const published = result.lastPublished;

  return (
    <section className="card" aria-labelledby={titleId}>
      <div className="card-head">
        <div>
          <h2 className="card-title" id={titleId}>
            {formatDriveDate(result.date)} · {weekdayOf(result.date)}
          </h2>
          <p className="card-head-sub">
            Round #{result.roundId} · Semester {result.semester ?? 'none applied'} ·{' '}
            {result.clashes.length} {result.clashes.length === 1 ? 'clash' : 'clashes'}
          </p>
        </div>
        <div className="card-head-actions">
          {published ? (
            published.current ? (
              <StatusPill tone="success">Published {formatInstant(published.queuedAt)}</StatusPill>
            ) : (
              <StatusPill tone="warning">Changed since last publish</StatusPill>
            )
          ) : (
            <StatusPill tone="neutral">Not published</StatusPill>
          )}
        </div>
      </div>

      <div className="card-body clash-window">
        <div>
          <div className="figure-label">Placement window</div>
          <div className="figure-value">{formatTimeRange(result.windowStart, result.windowEnd)}</div>
          <p className="side-note" style={{ marginTop: 4 }}>
            Earliest session start to latest session end of this round on this date, cancelled sessions included.
          </p>
        </div>
        <div className="clash-track">
          <DayTrack start={start} end={end} height={14} showTicks />
        </div>
      </div>

      {result.semester === null ? (
        <EmptyState title="No timetable applied yet">
          <p className="state-text">Upload the semester timetable on Excel import; clashes are computed once it is in.</p>
          <Link to="/imports?type=timetable" className={buttonClass('secondary', true)}>
            Upload timetable
          </Link>
        </EmptyState>
      ) : result.windowStart === null ? (
        <EmptyState title="The round has no session on this date any more">
          <p className="state-text">An empty list was published so attendance-service drops the old clashes.</p>
        </EmptyState>
      ) : result.clashes.length === 0 ? (
        <EmptyState title="No clashes">
          <p className="state-text">
            No shortlisted student has a class in this window. If you expected some, check that the round's shortlist
            was uploaded.
          </p>
        </EmptyState>
      ) : (
        <div className="table-scroll">
          <table className="table compact-table clash-table">
            <colgroup>
              <col style={{ width: 150 }} />
              <col style={{ width: 120 }} />
              <col />
              <col style={{ width: 150 }} />
              <col style={{ width: 140 }} />
            </colgroup>
            <thead>
              <tr>
                <th scope="col">Student ID</th>
                <th scope="col">Section</th>
                <th scope="col">Course</th>
                <th scope="col">Class slot</th>
                <th scope="col">Room</th>
              </tr>
            </thead>
            <tbody>
              {result.clashes.map((c) => (
                <tr key={`${c.studentId}-${c.sectionCode}-${c.slotStart}`}>
                  <td className="cell-strong mono">{c.studentId}</td>
                  <td>{c.sectionCode}</td>
                  <td className="cell-truncate" title={c.courseName}>
                    {c.courseName}
                  </td>
                  <td>{formatTimeRange(c.slotStart, c.slotEnd)}</td>
                  <td className="cell-truncate" title={c.room}>
                    {c.room}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}
