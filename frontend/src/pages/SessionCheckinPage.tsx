import { useEffect, useMemo, useRef, useState } from 'react';
import { Link, Navigate, useParams } from 'react-router-dom';
import {
  ApiError,
  errorText,
  getAttendanceSheet,
  getDrive,
  getSession,
  markStudent,
  uploadAttendance,
  type AttendanceSheet,
  type DriveDetail,
  type ImportReport,
  type MarkSource,
  type Round,
  type Session,
  type SheetRow,
  type SheetStatus,
} from '../api';
import { useSignIn } from '../auth';
import { Badge, SourceBadge } from '../components/Badge';
import { Banner } from '../components/Banner';
import { Button, buttonClass } from '../components/Button';
import { ChevronLeftIcon, SearchIcon, UploadIcon, UserIcon } from '../components/Icons';
import { ImportReportView } from '../components/ImportReport';
import { InlineUpload } from '../components/InlineUpload';
import { EmptyState, ErrorState, SkeletonRows } from '../components/States';
import { StatusPill } from '../components/StatusPill';
import { formatClock, formatDriveDate, formatInstant, formatTimeRange } from '../time';
import { useResource } from '../useResource';

const POLL_MS = 5000;
const HIGHLIGHT_MS = 2400;
const BULK_PARALLEL = 4;

type Filter = 'ALL' | SheetStatus;

const FILTERS: { value: Filter; label: string }[] = [
  { value: 'ALL', label: 'All' },
  { value: 'PRESENT', label: 'Present' },
  { value: 'ABSENT', label: 'Absent' },
  { value: 'NOT_MARKED', label: 'Not marked' },
];

/** A manual mark saved from this page, shown until the next sheet poll carries it (or something newer). */
interface LocalMark {
  status: SheetStatus;
  source: MarkSource;
  markedAt: string;
  markedBy: string;
}

interface Context {
  session: Session;
  drive: DriveDetail | null;
  round: Round | null;
}

type Notice = { tone: 'success' | 'error'; text: string } | null;

/** Old check-in route: /sessions/:id goes to the attendance sheet. */
export function SessionRedirect() {
  const { id } = useParams();
  return <Navigate to={`/sessions/${id}/attendance`} replace />;
}

export function SessionCheckinPage() {
  const params = useParams();
  const sessionId = Number(params.id);
  const validId = Number.isInteger(sessionId) && sessionId > 0;
  const signIn = useSignIn();

  // Company and round names come from placement-service; the sheet itself from checkin-service.
  const context = useResource<Context>(
    validId
      ? async (signal) => {
          const session = await getSession(sessionId, signal);
          let drive: DriveDetail | null = null;
          try {
            drive = await getDrive(session.driveId, signal);
          } catch (error) {
            if (signal.aborted) throw error;
          }
          return { session, drive, round: drive?.rounds.find((r) => r.id === session.roundId) ?? null };
        }
      : null,
    [sessionId],
  );
  const sheet = useResource<AttendanceSheet>(
    validId ? (signal) => getAttendanceSheet(sessionId, signal) : null,
    [sessionId],
    POLL_MS,
  );

  const [local, setLocal] = useState<Map<string, LocalMark>>(new Map());
  const [pending, setPending] = useState<Map<string, boolean>>(new Map());
  const [rowErrors, setRowErrors] = useState<Map<string, string>>(new Map());
  const [query, setQuery] = useState('');
  const [filter, setFilter] = useState<Filter>('ALL');
  const [panel, setPanel] = useState<'upload' | 'confirm' | null>(null);
  const [bulk, setBulk] = useState<{ done: number; total: number } | null>(null);
  const [notice, setNotice] = useState<Notice>(null);
  const [upload, setUpload] = useState<{ report: ImportReport; checkedAt: Date } | null>(null);

  useEffect(() => {
    setLocal(new Map());
    setPending(new Map());
    setRowErrors(new Map());
    setPanel(null);
    setUpload(null);
    setNotice(null);
  }, [sessionId]);

  const data = sheet.state.kind === 'ready' ? sheet.state.data : null;

  // The server's row, unless a manual mark saved here is newer than what the last poll returned.
  const rows: SheetRow[] = useMemo(() => {
    if (!data) return [];
    return data.students.map((row) => {
      const mine = local.get(row.studentId);
      if (mine && (!row.markedAt || Date.parse(row.markedAt) < Date.parse(mine.markedAt))) {
        return { ...row, ...mine };
      }
      return row;
    });
  }, [data, local]);

  const counts = useMemo(() => {
    const present = rows.filter((r) => r.status === 'PRESENT').length;
    const absent = rows.filter((r) => r.status === 'ABSENT').length;
    return { PRESENT: present, ABSENT: absent, NOT_MARKED: rows.length - present - absent, ALL: rows.length };
  }, [rows]);

  // Rows whose latest mark changed since the previous poll (e.g. an RFID tap) are tinted for a moment.
  const seen = useRef<Map<string, string | null> | null>(null);
  const [fresh, setFresh] = useState<Set<string>>(new Set());
  useEffect(() => {
    if (!data) return;
    const now = new Map(data.students.map((r) => [r.studentId, r.markedAt]));
    const before = seen.current;
    seen.current = now;
    if (!before) return;
    const changed = data.students
      .filter((r) => r.markedAt && before.get(r.studentId) !== r.markedAt && !local.has(r.studentId))
      .map((r) => r.studentId);
    if (changed.length === 0) return;
    setFresh(new Set(changed));
    const timer = window.setTimeout(() => setFresh(new Set()), HIGHLIGHT_MS);
    return () => window.clearTimeout(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [data]);
  useEffect(() => {
    seen.current = null;
  }, [sessionId]);

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

  const ctx = context.state.kind === 'ready' ? context.state.data : null;
  const driveId = ctx?.session.driveId ?? data?.driveId ?? null;
  const roundId = ctx?.session.roundId ?? data?.roundId ?? null;
  const cancelled = ctx?.session.status === 'CANCELLED' || data?.status === 'CANCELLED';
  const readOnly = cancelled || bulk !== null;
  const shortlisted = rows.filter((r) => r.onShortlist);
  const notMarked = shortlisted.filter((r) => r.status === 'NOT_MARKED');
  const roundLabel = ctx?.round ? ctx.round.name : roundId ? `Round #${roundId}` : 'Round';

  const needle = query.trim().toLowerCase();
  const visible = rows.filter(
    (r) =>
      (filter === 'ALL' || r.status === filter) &&
      (!needle || r.studentId.includes(needle) || (r.name ?? '').toLowerCase().includes(needle)),
  );
  const firstOffRoster = visible.findIndex((r) => !r.onShortlist);

  async function mark(studentId: string, present: boolean): Promise<boolean> {
    setPending((p) => new Map(p).set(studentId, present));
    setRowErrors((e) => {
      const next = new Map(e);
      next.delete(studentId);
      return next;
    });
    try {
      const { mark: saved } = await markStudent(sessionId, studentId, present);
      setLocal((l) =>
        new Map(l).set(studentId, {
          status: saved.present ? 'PRESENT' : 'ABSENT',
          source: saved.source,
          markedAt: saved.markedAt,
          markedBy: saved.markedBy,
        }),
      );
      return true;
    } catch (error) {
      let text = errorText(error);
      if (error instanceof ApiError && error.code === 'SESSION_CANCELLED') text = 'Not saved: the session is cancelled.';
      if (error instanceof ApiError && error.code === 'SESSION_NOT_FOUND') {
        text = 'Not saved: checkin-service has not received this session yet. Try again in a moment.';
      }
      setRowErrors((e) => new Map(e).set(studentId, text));
      return false;
    } finally {
      setPending((p) => {
        const next = new Map(p);
        next.delete(studentId);
        return next;
      });
    }
  }

  async function markAllRemaining() {
    const ids = notMarked.map((r) => r.studentId);
    setPanel(null);
    setNotice(null);
    setBulk({ done: 0, total: ids.length });
    let failed = 0;
    let next = 0;
    async function worker() {
      while (next < ids.length) {
        const id = ids[next++];
        if (!(await mark(id, true))) failed++;
        setBulk((b) => (b ? { ...b, done: b.done + 1 } : b));
      }
    }
    await Promise.all(Array.from({ length: Math.min(BULK_PARALLEL, ids.length) }, worker));
    setBulk(null);
    setNotice(
      failed === 0
        ? { tone: 'success', text: `${ids.length} ${ids.length === 1 ? 'student' : 'students'} marked present.` }
        : { tone: 'error', text: `${ids.length - failed} marked present, ${failed} not saved. The reason is shown on each row.` },
    );
    sheet.reload();
  }

  function handleUploadReport(report: ImportReport) {
    setUpload({ report, checkedAt: new Date() });
    setPanel(null);
    // FILE marks reach checkin-service through Kafka; look again before the next 5 s poll.
    window.setTimeout(sheet.reload, 1200);
  }

  const sheetMissing =
    sheet.state.kind === 'error' && sheet.state.error instanceof ApiError && sheet.state.error.status === 404;

  return (
    <>
      <Link to={driveId ? `/drives/${driveId}` : '/drives'} className="back-link">
        <ChevronLeftIcon size={16} />
        {ctx?.drive ? ctx.drive.companyName : driveId ? `Drive #${driveId}` : 'Drives'}
      </Link>

      <header className="page-header">
        <div>
          <h1 className="page-title">
            {ctx?.drive ? `${ctx.drive.companyName} · ${roundLabel}` : `Session #${sessionId}`}
          </h1>
          <div className="meta-list">
            <span>
              Attendance, session <strong>#{sessionId}</strong>
            </span>
            {ctx || data ? (
              <>
                <span>{formatDriveDate((ctx?.session ?? data)!.date)}</span>
                <span>{formatTimeRange((ctx?.session ?? data)!.startTime, (ctx?.session ?? data)!.endTime)}</span>
                <span>{(ctx?.session ?? data)!.room}</span>
                {cancelled ? <StatusPill tone="error">Cancelled</StatusPill> : <StatusPill tone="success">Scheduled</StatusPill>}
              </>
            ) : context.state.kind === 'error' && sheet.state.kind === 'error' ? (
              <span>Session details unavailable</span>
            ) : (
              <span>Loading session details</span>
            )}
          </div>
        </div>
        <Link to={`/corrections?sessionId=${sessionId}`} className={buttonClass('secondary')}>
          Correct a mark
        </Link>
      </header>

      {context.state.kind === 'error' && (
        <Banner tone="error">
          {context.state.error instanceof ApiError && context.state.error.status === 404
            ? `placement-service does not know session #${sessionId}.`
            : `Company and round names unavailable. ${errorText(context.state.error)}`}
        </Banner>
      )}

      {cancelled && (
        <Banner tone="error">
          This session is cancelled, so the sheet is read-only. Marks recorded before it was cancelled are kept and
          still count for OD.
        </Banner>
      )}

      {notice && (
        <Banner tone={notice.tone} onDismiss={() => setNotice(null)}>
          {notice.text}
        </Banner>
      )}

      <section className="card sheet-counters" aria-label="Counts">
        <div className="figures figures-4">
          <Counter label="Present" value={data ? counts.PRESENT : null} tone="accepted" />
          <Counter label="Absent" value={data ? counts.ABSENT : null} tone="rejected" />
          <Counter label="Not marked" value={data ? counts.NOT_MARKED : null} />
          <Counter label="Total" value={data ? counts.ALL : null} />
        </div>
        <div className="stack-bar" aria-hidden="true">
          {counts.ALL > 0 && (
            <>
              <span className="stack-accepted" style={{ width: `${(counts.PRESENT / counts.ALL) * 100}%` }} />
              <span className="stack-rejected" style={{ width: `${(counts.ABSENT / counts.ALL) * 100}%` }} />
            </>
          )}
        </div>
      </section>

      <section className="card" aria-labelledby="sheet-title">
        <div className="card-head">
          <div>
            <h2 className="card-title" id="sheet-title">
              Attendance sheet
            </h2>
            <p className="card-head-sub">
              Shortlisted students of {roundLabel}. Saved as soon as you click; card taps appear by themselves.
            </p>
          </div>
          <div className="card-head-actions">
            <LiveIndicator stale={sheet.refreshError != null || sheet.state.kind === 'error'} last={sheet.lastLoaded} />
            <div className="sheet-actions">
              <Button
                variant="secondary"
                small
                icon={<UploadIcon size={16} />}
                disabled={readOnly || !data}
                aria-expanded={panel === 'upload'}
                onClick={() => setPanel(panel === 'upload' ? null : 'upload')}
              >
                Upload attendance file
              </Button>
              {shortlisted.length > 0 && (
                <Button
                  small
                  disabled={readOnly || !data || notMarked.length === 0}
                  aria-expanded={panel === 'confirm'}
                  loading={bulk !== null}
                  loadingText={bulk ? `Marking ${bulk.done} of ${bulk.total}` : undefined}
                  onClick={() => setPanel(panel === 'confirm' ? null : 'confirm')}
                >
                  Mark all remaining present
                </Button>
              )}
            </div>
          </div>
        </div>

        {sheet.refreshError != null && (
          <div className="card-body" style={{ paddingBottom: 0 }}>
            <Banner tone="error">{errorText(sheet.refreshError)} Showing the last sheet; retrying every 5 s.</Banner>
          </div>
        )}

        {sheet.state.kind === 'error' ? (
          sheetMissing ? (
            <EmptyState icon={<UserIcon size={20} />} title={`checkin-service has not received session #${sessionId} yet`}>
              <p className="state-text">
                It arrives through Kafka a few seconds after the session is created. This page keeps checking every 5 s.
              </p>
            </EmptyState>
          ) : (
            <ErrorState error={sheet.state.error} onRetry={sheet.reload} />
          )
        ) : (
          <>
            {rows.length > 0 && (
              <div className="toolbar sheet-toolbar">
                <div className="sheet-toolbar-left">
                  <label className="search">
                    <span className="visually-hidden">Search by student ID or name</span>
                    <SearchIcon size={16} />
                    <input
                      className="input"
                      type="search"
                      placeholder="Search ID or name"
                      value={query}
                      onChange={(e) => setQuery(e.target.value)}
                    />
                  </label>
                  <div className="segmented" role="group" aria-label="Show">
                    {FILTERS.map((f) => (
                      <button
                        key={f.value}
                        type="button"
                        aria-pressed={filter === f.value}
                        onClick={() => setFilter(f.value)}
                      >
                        {f.label}
                        <span className="segmented-count">{data ? counts[f.value] : ''}</span>
                      </button>
                    ))}
                  </div>
                </div>
              </div>
            )}

            {panel === 'upload' && !readOnly && (
              <InlineUpload
                id={`session-${sessionId}-attendance-file`}
                type="attendance"
                title="Upload an attendance file"
                targetText={`session #${sessionId}`}
                hint="Columns studentId and, optionally, studentName: everyone in the file is marked present (FILE). Students not in the file are left as they are."
                upload={(file) => uploadAttendance(sessionId, file)}
                onReport={handleUploadReport}
                onClose={() => setPanel(null)}
              />
            )}

            {panel === 'confirm' && !readOnly && (
              <ConfirmMarkAll
                count={notMarked.length}
                userId={signIn.userId}
                onCancel={() => setPanel(null)}
                onConfirm={() => void markAllRemaining()}
              />
            )}

            {upload && (
              <ImportReportView
                report={upload.report}
                checkedAt={upload.checkedAt}
                targetLabel="Session"
                embedded
                headingId={`session-${sessionId}-upload-report`}
                onDismiss={() => setUpload(null)}
              />
            )}

            {data && shortlisted.length === 0 && (
              <EmptyState icon={<UserIcon size={20} />} title={`No shortlist uploaded for ${roundLabel} yet`}>
                <p className="state-text">
                  The sheet lists the round's shortlisted students. Upload the shortlist on the round first; students
                  who tap a card still appear below.
                </p>
                {driveId && roundId && (
                  <Link to={`/drives/${driveId}?shortlist=${roundId}`} className={buttonClass('primary', true)}>
                    Upload shortlist
                  </Link>
                )}
              </EmptyState>
            )}

            {(!data || rows.length > 0) && (
              <div className="table-sticky table-sticky-tall">
                <table className="table compact-table sheet-table">
                  <colgroup>
                    <col style={{ width: 140 }} />
                    <col />
                    <col style={{ width: 128 }} />
                    <col style={{ width: 196 }} />
                    <col style={{ width: 104 }} />
                    <col style={{ width: 172 }} />
                  </colgroup>
                  <thead>
                    <tr>
                      <th scope="col">Student ID</th>
                      <th scope="col">Name</th>
                      <th scope="col">Status</th>
                      <th scope="col">Mark</th>
                      <th scope="col">Source</th>
                      <th scope="col">Last marked</th>
                    </tr>
                  </thead>
                  <tbody>
                    {!data ? (
                      <SkeletonRows columns={6} rows={6} />
                    ) : visible.length === 0 ? (
                      <tr>
                        <td colSpan={6} className="sheet-no-match">
                          No student matches {needle ? `"${query.trim()}"` : 'this filter'}.
                        </td>
                      </tr>
                    ) : (
                      visible.map((r, index) => (
                        <SheetRowView
                          key={r.studentId}
                          row={r}
                          divider={index === firstOffRoster}
                          fresh={fresh.has(r.studentId)}
                          pending={pending.get(r.studentId)}
                          error={rowErrors.get(r.studentId)}
                          disabled={cancelled || pending.has(r.studentId)}
                          onMark={(present) => void mark(r.studentId, present)}
                        />
                      ))
                    )}
                  </tbody>
                </table>
              </div>
            )}
          </>
        )}
        <div className="card-footer">
          <span>RFID = card tap · MANUAL = marked on this page · FILE = attendance upload</span>
          {data && (
            <span>
              {visible.length === rows.length ? `${rows.length} students` : `${visible.length} of ${rows.length} shown`}
            </span>
          )}
        </div>
      </section>
    </>
  );
}

function Counter({ label, value, tone }: { label: string; value: number | null; tone?: 'accepted' | 'rejected' }) {
  return (
    <div>
      <div className="figure-label">{label}</div>
      <div className={`figure-value${tone ? ` figure-${tone}` : ''}`}>{value ?? '–'}</div>
    </div>
  );
}

interface SheetRowViewProps {
  row: SheetRow;
  divider: boolean;
  fresh: boolean;
  /** The value being saved, while a request runs. */
  pending: boolean | undefined;
  error: string | undefined;
  disabled: boolean;
  onMark: (present: boolean) => void;
}

function SheetRowView({ row, divider, fresh, pending, error, disabled, onMark }: SheetRowViewProps) {
  const label = row.name ?? row.studentId;
  return (
    <>
      {divider && (
        <tr className="row-divider">
          <th scope="rowgroup" colSpan={6}>
            Marked, but not on the shortlist
          </th>
        </tr>
      )}
      <tr className={fresh ? 'row-new' : undefined}>
        <td className="cell-strong mono">{row.studentId}</td>
        <td>
          <div className="sheet-name">
            <span className="cell-truncate" title={row.name ?? undefined}>
              {row.name ?? <span className="value-empty" aria-label="name unknown">—</span>}
            </span>
            {!row.onShortlist && <Badge tone="warning">Not on shortlist</Badge>}
          </div>
          {error && (
            <p className="row-error" role="alert">
              {error}
            </p>
          )}
        </td>
        <td>
          {row.status === 'PRESENT' ? (
            <StatusPill tone="success">Present</StatusPill>
          ) : row.status === 'ABSENT' ? (
            <StatusPill tone="error">Absent</StatusPill>
          ) : (
            <StatusPill tone="neutral">Not marked</StatusPill>
          )}
        </td>
        <td>
          <div className="mark-toggle" role="group" aria-label={`Mark ${label}`}>
            <button
              type="button"
              className="mark-present"
              aria-pressed={row.status === 'PRESENT'}
              aria-busy={pending === true || undefined}
              disabled={disabled}
              onClick={() => row.status !== 'PRESENT' && onMark(true)}
            >
              {pending === true && <span className="spinner" aria-hidden="true" />}
              Present
            </button>
            <button
              type="button"
              className="mark-absent"
              aria-pressed={row.status === 'ABSENT'}
              aria-busy={pending === false || undefined}
              disabled={disabled}
              onClick={() => row.status !== 'ABSENT' && onMark(false)}
            >
              {pending === false && <span className="spinner" aria-hidden="true" />}
              Absent
            </button>
          </div>
        </td>
        <td>{row.source ? <SourceBadge source={row.source} /> : <span className="value-empty">—</span>}</td>
        <td title={row.markedBy ? `by ${row.markedBy}` : undefined}>
          {row.markedAt ? (
            <span className="nowrap">{formatInstant(row.markedAt)}</span>
          ) : (
            <span className="value-empty">—</span>
          )}
          {row.markedBy && <span className="cell-sub">{row.markedBy}</span>}
        </td>
      </tr>
    </>
  );
}

function ConfirmMarkAll({
  count,
  userId,
  onCancel,
  onConfirm,
}: {
  count: number;
  userId: string;
  onCancel: () => void;
  onConfirm: () => void;
}) {
  const confirmRef = useRef<HTMLButtonElement>(null);
  useEffect(() => confirmRef.current?.focus(), []);
  return (
    <div className="panel panel-confirm panel-confirm-accent" role="group" aria-labelledby="mark-all-title">
      <p className="panel-title" id="mark-all-title">
        Mark {count} {count === 1 ? 'student' : 'students'} present?
      </p>
      <p>
        Only shortlisted students who are not marked yet are changed. Each one is saved as a MANUAL mark by{' '}
        <strong>{userId || 'the signed-in user'}</strong>. Students already marked present or absent keep their mark.
      </p>
      <div className="panel-actions">
        <Button variant="secondary" onClick={onCancel}>
          Keep as is
        </Button>
        <Button ref={confirmRef} onClick={onConfirm}>
          Mark {count} present
        </Button>
      </div>
    </div>
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
