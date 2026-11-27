import { useEffect, useMemo, useRef, useState, type FormEvent } from 'react';
import { Link, useParams } from 'react-router-dom';
import { ApiError, errorText, getSession, listMarks, markStudent, type CheckinMark, type Session } from '../api';
import { SourceBadge } from '../components/Badge';
import { Banner } from '../components/Banner';
import { Button, buttonClass } from '../components/Button';
import { Field } from '../components/Field';
import { ChevronLeftIcon, ReaderIcon } from '../components/Icons';
import { EmptyState, ErrorState, SkeletonRows } from '../components/States';
import { StatusPill } from '../components/StatusPill';
import { formatClock, formatDriveDate, formatInstant, formatTimeRange, STUDENT_ID } from '../time';
import { useResource } from '../useResource';

const POLL_MS = 3000;
const HIGHLIGHT_MS = 2400;

type Notice = { tone: 'success' | 'error'; text: string } | null;

export function SessionCheckinPage() {
  const params = useParams();
  const sessionId = Number(params.id);
  const validId = Number.isInteger(sessionId) && sessionId > 0;

  const session = useResource<Session>(validId ? (signal) => getSession(sessionId, signal) : null, [sessionId]);
  const marks = useResource<CheckinMark[]>(
    validId ? (signal) => listMarks(sessionId, signal) : null,
    [sessionId],
    POLL_MS,
  );

  // Rows whose mark arrived since the previous poll are tinted for a moment.
  const seen = useRef<Set<string> | null>(null);
  const [fresh, setFresh] = useState<Set<string>>(new Set());
  const markList = marks.state.kind === 'ready' ? marks.state.data : null;

  useEffect(() => {
    if (!markList) return;
    const ids = new Set(markList.map((m) => m.markId));
    if (seen.current) {
      const added = markList.filter((m) => !seen.current!.has(m.markId)).map((m) => m.markId);
      if (added.length > 0) {
        setFresh(new Set(added));
        const timer = window.setTimeout(() => setFresh(new Set()), HIGHLIGHT_MS);
        seen.current = ids;
        return () => window.clearTimeout(timer);
      }
    }
    seen.current = ids;
  }, [markList]);

  useEffect(() => {
    seen.current = null;
  }, [sessionId]);

  const counts = useMemo(() => {
    const list = markList ?? [];
    const present = list.filter((m) => m.present).length;
    return { marked: list.length, present, absent: list.length - present };
  }, [markList]);

  if (!validId) {
    return (
      <section className="card">
        <EmptyState title={`"${params.id}" is not a session id`}>
          <Link to="/drives" className={buttonClass('secondary', true)}>
            Show drives
          </Link>
        </EmptyState>
      </section>
    );
  }

  const s = session.state.kind === 'ready' ? session.state.data : null;
  const cancelled = s?.status === 'CANCELLED';

  return (
    <>
      {s ? (
        <Link to={`/drives/${s.driveId}`} className="back-link">
          <ChevronLeftIcon size={16} />
          Drive #{s.driveId}
        </Link>
      ) : (
        <Link to="/drives" className="back-link">
          <ChevronLeftIcon size={16} />
          Drives
        </Link>
      )}

      <header className="page-header">
        <div>
          <h1 className="page-title">Session #{sessionId} check-in</h1>
          {s ? (
            <div className="meta-list">
              <span>
                Round <strong>#{s.roundId}</strong>
              </span>
              <span>{formatDriveDate(s.date)}</span>
              <span>{formatTimeRange(s.startTime, s.endTime)}</span>
              <span>{s.room}</span>
              {cancelled ? (
                <StatusPill tone="error">Cancelled</StatusPill>
              ) : (
                <StatusPill tone="success">Scheduled</StatusPill>
              )}
            </div>
          ) : session.state.kind === 'error' ? (
            <p className="page-lede">
              {session.state.error instanceof ApiError && session.state.error.status === 404
                ? 'placement-service does not know this session.'
                : `Session details unavailable. ${errorText(session.state.error)}`}
            </p>
          ) : (
            <p className="page-lede">Loading session details</p>
          )}
        </div>
        <Link to={`/corrections?sessionId=${sessionId}`} className={buttonClass('secondary')}>
          Correct a mark
        </Link>
      </header>

      {cancelled && (
        <Banner tone="error">
          This session is cancelled. Readers and manual marks are refused; the marks below are kept and still count for
          OD.
        </Banner>
      )}

      <div className="checkin-layout">
        <section className="card" aria-labelledby="marks-title">
          <div className="card-head">
            <div>
              <h2 className="card-title" id="marks-title">
                Marks from checkin-service
              </h2>
              <p className="card-head-sub">Latest mark per student. Taps on the room's reader appear here by themselves.</p>
            </div>
            <LiveIndicator stale={marks.refreshError != null || marks.state.kind === 'error'} last={marks.lastLoaded} />
          </div>

          {marks.refreshError != null && (
            <div className="card-body" style={{ paddingBottom: 0 }}>
              <Banner tone="error">{errorText(marks.refreshError)} Showing the last list; retrying every 3 s.</Banner>
            </div>
          )}

          {marks.state.kind === 'error' ? (
            <ErrorState error={marks.state.error} onRetry={marks.reload} />
          ) : marks.state.kind === 'ready' && marks.state.data.length === 0 ? (
            <EmptyState icon={<ReaderIcon size={20} />} title="No one is marked yet">
              <p className="state-text">
                Students tapping their card on the reader in {s?.room ?? 'the session room'} appear here within 3 s. You
                can also mark a student by hand.
              </p>
            </EmptyState>
          ) : (
            <div className="table-scroll">
              <table className="table compact-table marks-table">
                <colgroup>
                  <col style={{ width: 150 }} />
                  <col style={{ width: 120 }} />
                  <col style={{ width: 112 }} />
                  <col />
                  <col style={{ width: 150 }} />
                  <col style={{ width: 96 }} />
                </colgroup>
                <thead>
                  <tr>
                    <th scope="col">Student ID</th>
                    <th scope="col">Status</th>
                    <th scope="col">Source</th>
                    <th scope="col">Marked by</th>
                    <th scope="col">Marked at</th>
                    <th scope="col">
                      <span className="visually-hidden">Actions</span>
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {marks.state.kind === 'loading' ? (
                    <SkeletonRows columns={6} />
                  ) : (
                    marks.state.data.map((m) => (
                      <tr key={m.markId} className={fresh.has(m.markId) ? 'row-new' : undefined}>
                        <td className="cell-strong mono">{m.studentId}</td>
                        <td>
                          {m.present ? (
                            <StatusPill tone="success">Present</StatusPill>
                          ) : (
                            <StatusPill tone="error">Absent</StatusPill>
                          )}
                        </td>
                        <td>
                          <SourceBadge source={m.source} />
                        </td>
                        <td className="cell-truncate" title={m.markedBy}>
                          {m.markedBy}
                          {m.version > 1 && <span className="cell-sub">version {m.version}</span>}
                        </td>
                        <td>{formatInstant(m.markedAt)}</td>
                        <td>
                          <Link
                            to={`/corrections?sessionId=${sessionId}&studentId=${m.studentId}`}
                            className="text-link"
                          >
                            Correct
                          </Link>
                        </td>
                      </tr>
                    ))
                  )}
                </tbody>
              </table>
            </div>
          )}
          <div className="card-footer">
            <span>RFID = card tap · MANUAL = marked on this page · FILE = attendance upload</span>
            {marks.state.kind === 'ready' && <span>{counts.marked} marked</span>}
          </div>
        </section>

        <aside>
          <section className="card card-body" aria-labelledby="counts-title">
            <h2 className="card-title" id="counts-title">
              Present vs marked
            </h2>
            <div className="figures" style={{ marginTop: 12 }}>
              <div>
                <div className="figure-label">Present</div>
                <div className="figure-value figure-accepted">{markList ? counts.present : '–'}</div>
              </div>
              <div>
                <div className="figure-label">Absent</div>
                <div className="figure-value figure-rejected">{markList ? counts.absent : '–'}</div>
              </div>
              <div>
                <div className="figure-label">Marked</div>
                <div className="figure-value">{markList ? counts.marked : '–'}</div>
              </div>
            </div>
            <div className="stack-bar" aria-hidden="true">
              {counts.marked > 0 && (
                <>
                  <span className="stack-accepted" style={{ width: `${(counts.present / counts.marked) * 100}%` }} />
                  <span className="stack-rejected" style={{ width: `${(counts.absent / counts.marked) * 100}%` }} />
                </>
              )}
            </div>
          </section>

          <ManualMark sessionId={sessionId} disabled={cancelled} onMarked={marks.reload} />
        </aside>
      </div>
    </>
  );
}

function LiveIndicator({ stale, last }: { stale: boolean; last: Date | null }) {
  return (
    <span className="live" aria-live="off">
      <span className={`live-dot${stale ? ' live-dot-stale' : ''}`} aria-hidden="true" />
      {stale ? 'Not updating' : last ? `Live · ${formatClock(last)}:${String(last.getSeconds()).padStart(2, '0')}` : 'Connecting'}
    </span>
  );
}

function ManualMark({ sessionId, disabled, onMarked }: { sessionId: number; disabled: boolean; onMarked: () => void }) {
  const [studentId, setStudentId] = useState('');
  const [touched, setTouched] = useState(false);
  const [sending, setSending] = useState<'present' | 'absent' | null>(null);
  const [notice, setNotice] = useState<Notice>(null);
  const inputRef = useRef<HTMLInputElement>(null);

  const id = studentId.trim();
  const error = !id ? 'Enter the student ID' : !STUDENT_ID.test(id) ? 'A student ID is exactly 10 digits' : null;

  async function send(present: boolean) {
    setTouched(true);
    setNotice(null);
    if (error) {
      inputRef.current?.focus();
      return;
    }
    setSending(present ? 'present' : 'absent');
    try {
      const { created } = await markStudent(sessionId, id, present);
      setNotice({
        tone: 'success',
        text: created
          ? `${id} marked ${present ? 'present' : 'absent'} and published.`
          : `${id} was already marked ${present ? 'present' : 'absent'}; nothing changed.`,
      });
      setStudentId('');
      setTouched(false);
      onMarked();
      inputRef.current?.focus();
    } catch (err) {
      let text = errorText(err);
      if (err instanceof ApiError && err.code === 'SESSION_NOT_FOUND') {
        text = 'checkin-service has not received this session from drive-events yet. Try again in a moment.';
      }
      setNotice({ tone: 'error', text });
    } finally {
      setSending(null);
    }
  }

  return (
    <section className="card card-body" aria-labelledby="manual-title">
      <h2 className="card-title" id="manual-title">
        Mark by hand
      </h2>
      <p className="side-note" style={{ marginTop: 4, marginBottom: 16 }}>
        For a forgotten or broken card. A tap never overrides a manual absent.
      </p>
      {notice && (
        <Banner tone={notice.tone} onDismiss={() => setNotice(null)}>
          {notice.text}
        </Banner>
      )}
      <form
        onSubmit={(e: FormEvent) => {
          e.preventDefault();
          void send(true);
        }}
        noValidate
      >
        <Field id="manual-student" label="Student ID" error={touched ? error : null}>
          <input
            ref={inputRef}
            className="input mono"
            type="text"
            inputMode="numeric"
            maxLength={10}
            placeholder="10 digits"
            value={studentId}
            disabled={disabled}
            autoComplete="off"
            onChange={(e) => setStudentId(e.target.value.replace(/\D/g, ''))}
            onBlur={() => id && setTouched(true)}
          />
        </Field>
        <div className="manual-actions">
          <Button type="submit" disabled={disabled || sending !== null} loading={sending === 'present'} loadingText="Marking">
            Mark present
          </Button>
          <Button
            variant="secondary"
            disabled={disabled || sending !== null}
            loading={sending === 'absent'}
            loadingText="Marking"
            onClick={() => void send(false)}
          >
            Mark absent
          </Button>
        </div>
      </form>
    </section>
  );
}
