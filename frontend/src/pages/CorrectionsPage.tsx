import { useEffect, useRef, useState, type FormEvent } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import {
  ApiError,
  createCorrection,
  errorText,
  getSession,
  listAttendanceMarks,
  listCorrections,
  type AttendanceMark,
  type Correction,
} from '../api';
import { ROLE_LABEL, useSignIn } from '../auth';
import { SourceBadge } from '../components/Badge';
import { Banner } from '../components/Banner';
import { Button } from '../components/Button';
import { Field } from '../components/Field';
import { AlertIcon, PencilIcon } from '../components/Icons';
import { SessionPicker, useDriveDetail, type Pick } from '../components/SessionPicker';
import { EmptyState, ErrorState, SkeletonRows } from '../components/States';
import { formatDriveDate, formatInstant, formatTimeRange, daysSince, STUDENT_ID } from '../time';
import { useResource } from '../useResource';

const WINDOW_DAYS = 14;
const REASON_MAX = 500;

function numberParam(value: string | null): number | null {
  const n = Number(value);
  return value && Number.isInteger(n) && n > 0 ? n : null;
}

function addDays(iso: string, days: number): string {
  const [y, m, d] = iso.split('-').map(Number);
  const date = new Date(y, m - 1, d + days);
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
}

type Outcome =
  | { kind: 'success'; correction: Correction }
  | { kind: 'window'; message: string }
  | { kind: 'error'; message: string }
  | null;

export function CorrectionsPage() {
  const [params, setParams] = useSearchParams();
  const signIn = useSignIn();
  const pick: Pick = {
    driveId: numberParam(params.get('driveId')),
    roundId: numberParam(params.get('roundId')),
    sessionId: numberParam(params.get('sessionId')),
  };

  // A link with only ?sessionId= (from the check-in page): find its drive and round for the picker.
  const lookup = useResource(
    pick.sessionId && !pick.driveId ? (signal) => getSession(pick.sessionId!, signal) : null,
    [pick.sessionId, pick.driveId],
  );
  useEffect(() => {
    if (lookup.state.kind !== 'ready' || pick.driveId) return;
    const s = lookup.state.data;
    const next = new URLSearchParams(params);
    next.set('driveId', String(s.driveId));
    next.set('roundId', String(s.roundId));
    setParams(next, { replace: true });
  }, [lookup.state, pick.driveId, params, setParams]);

  const detail = useDriveDetail(pick.driveId);
  const session =
    detail.kind === 'ready'
      ? detail.data.rounds.flatMap((r) => r.sessions).find((s) => s.id === pick.sessionId) ?? null
      : null;

  const [studentId, setStudentId] = useState(params.get('studentId') ?? '');
  const [present, setPresent] = useState<boolean | null>(null);
  const [reason, setReason] = useState('');
  const [showErrors, setShowErrors] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [outcome, setOutcome] = useState<Outcome>(null);
  const outcomeRef = useRef<HTMLDivElement>(null);

  const history = useResource<Correction[]>(
    pick.sessionId ? (signal) => listCorrections(pick.sessionId!, signal) : null,
    [pick.sessionId],
  );
  const marks = useResource<AttendanceMark[]>(
    pick.sessionId ? (signal) => listAttendanceMarks(pick.sessionId!, signal) : null,
    [pick.sessionId],
  );

  const id = studentId.trim();
  const errors = {
    session: pick.sessionId ? undefined : 'Choose the session',
    studentId: !id ? 'Enter the student ID' : !STUDENT_ID.test(id) ? 'A student ID is exactly 10 digits' : undefined,
    present: present === null ? 'Choose present or absent' : undefined,
    reason: !reason.trim() ? 'A reason is required' : undefined,
  };
  const invalid = Object.values(errors).some(Boolean);

  const currentMark =
    marks.state.kind === 'ready' && STUDENT_ID.test(id) ? marks.state.data.find((m) => m.studentId === id) ?? null : null;

  const lastDay = session ? addDays(session.date, WINDOW_DAYS) : null;
  const pastWindow = session ? daysSince(session.date) > WINDOW_DAYS : false;

  function changePick(next: Pick) {
    const query = new URLSearchParams();
    if (next.driveId) query.set('driveId', String(next.driveId));
    if (next.roundId) query.set('roundId', String(next.roundId));
    if (next.sessionId) query.set('sessionId', String(next.sessionId));
    if (id) query.set('studentId', id);
    setParams(query, { replace: true });
    setOutcome(null);
  }

  async function submit(event: FormEvent) {
    event.preventDefault();
    setOutcome(null);
    if (invalid) {
      setShowErrors(true);
      return;
    }
    setSubmitting(true);
    try {
      const correction = await createCorrection({
        sessionId: pick.sessionId!,
        studentId: id,
        present: present!,
        reason: reason.trim(),
      });
      setOutcome({ kind: 'success', correction });
      setReason('');
      setPresent(null);
      setShowErrors(false);
      history.reload();
      marks.reload();
    } catch (error) {
      if (error instanceof ApiError && error.status === 403) {
        setOutcome({ kind: 'window', message: error.message });
      } else if (error instanceof ApiError && error.status === 404) {
        setOutcome({
          kind: 'error',
          message: `attendance-service has not received session #${pick.sessionId} from drive-events yet, so it cannot be corrected.`,
        });
      } else {
        setOutcome({ kind: 'error', message: `The correction was not saved. ${errorText(error)}` });
      }
    } finally {
      setSubmitting(false);
      window.setTimeout(() => outcomeRef.current?.scrollIntoView({ block: 'nearest' }), 0);
    }
  }

  return (
    <>
      <header className="page-header">
        <div>
          <h1 className="page-title">Corrections</h1>
          <p className="page-lede">
            Change a student's mark for a session. No approval step: a Coordinator may correct up to {WINDOW_DAYS} days
            after the session, a Director at any time. Every correction keeps the old value, who made it and why.
          </p>
        </div>
      </header>

      <div ref={outcomeRef}>
        {outcome?.kind === 'success' && (
          <Banner tone="success" onDismiss={() => setOutcome(null)}>
            {outcome.correction.studentId} is now {outcome.correction.newPresent ? 'present' : 'absent'} in session #
            {outcome.correction.sessionId} (mark version {outcome.correction.markVersion}). OD is recomputed right away.
          </Banner>
        )}
        {outcome?.kind === 'window' && (
          <div className="window-alert" role="alert">
            <AlertIcon />
            <div>
              <p className="window-alert-title">Refused: outside the Coordinator's {WINDOW_DAYS}-day window</p>
              <p>{outcome.message}</p>
              <p className="window-alert-hint">
                Switch "Signed in as" to Director at the bottom of the sidebar to make this correction.
              </p>
            </div>
          </div>
        )}
        {outcome?.kind === 'error' && (
          <Banner tone="error" onDismiss={() => setOutcome(null)}>
            {outcome.message}
          </Banner>
        )}
      </div>

      <form className="card" onSubmit={submit} noValidate aria-label="New correction">
        <div className="card-body form-stack">
          <div>
            <SessionPicker value={pick} onChange={changePick} withSession />
            {showErrors && errors.session && <p className="field-error">{errors.session}</p>}
            {lookup.state.kind === 'error' && (
              <p className="field-error">Session #{pick.sessionId}: {errorText(lookup.state.error)}</p>
            )}
            {session && (
              <p className="field-helper">
                Session #{session.id} on {formatDriveDate(session.date)}, {formatTimeRange(session.startTime, session.endTime)}
                , {session.room}
                {session.status === 'CANCELLED' ? ' (cancelled; its marks still count)' : ''}. Coordinators may correct
                until {lastDay && formatDriveDate(lastDay)}.
              </p>
            )}
          </div>

          {session && pastWindow && signIn.role === 'COORDINATOR' && (
            <Banner tone="error">
              This session was more than {WINDOW_DAYS} days ago (last day for a Coordinator was{' '}
              {lastDay && formatDriveDate(lastDay)}). attendance-service will refuse it unless you are signed in as
              Director.
            </Banner>
          )}

          <div className="form-grid-2">
            <Field
              id="corr-student"
              label="Student ID"
              error={showErrors ? errors.studentId : undefined}
              helper={
                currentMark
                  ? undefined
                  : STUDENT_ID.test(id) && marks.state.kind === 'ready'
                    ? 'No mark yet for this student in this session'
                    : undefined
              }
            >
              <input
                className="input mono"
                type="text"
                inputMode="numeric"
                maxLength={10}
                placeholder="10 digits"
                autoComplete="off"
                value={studentId}
                onChange={(e) => setStudentId(e.target.value.replace(/\D/g, ''))}
              />
            </Field>

            <div className="field">
              <span className="field-label" id="corr-present-label">
                New value
              </span>
              <div className="segmented segmented-wide" role="radiogroup" aria-labelledby="corr-present-label">
                <button type="button" role="radio" aria-checked={present === true} onClick={() => setPresent(true)}>
                  Present
                </button>
                <button type="button" role="radio" aria-checked={present === false} onClick={() => setPresent(false)}>
                  Absent
                </button>
              </div>
              {showErrors && errors.present && <p className="field-error">{errors.present}</p>}
            </div>
          </div>

          {currentMark && (
            <p className="current-mark">
              Current mark in attendance-service:{' '}
              <strong>{currentMark.present ? 'Present' : 'Absent'}</strong> <SourceBadge source={currentMark.source} /> by{' '}
              {currentMark.markedBy}, {formatInstant(currentMark.markedAt)} (version {currentMark.version})
            </p>
          )}

          <Field
            id="corr-reason"
            label="Reason"
            error={showErrors ? errors.reason : undefined}
            helper={`${reason.length} / ${REASON_MAX}. Kept with the correction for the record.`}
          >
            <textarea
              className="textarea"
              maxLength={REASON_MAX}
              value={reason}
              placeholder="e.g. Card reader in Hall A was down; student signed the paper sheet"
              onChange={(e) => setReason(e.target.value)}
            />
          </Field>
        </div>
        <div className="form-footer">
          <span className="form-footer-note">
            Saving as <strong>{signIn.userId || 'no user id'}</strong>, {ROLE_LABEL[signIn.role]}
          </span>
          <Button type="submit" loading={submitting} loadingText="Saving correction">
            Save correction
          </Button>
        </div>
      </form>

      <section className="card" aria-labelledby="history-title">
        <div className="card-head">
          <div>
            <h2 className="card-title" id="history-title">
              History{pick.sessionId ? ` of session #${pick.sessionId}` : ''}
            </h2>
            <p className="card-head-sub">Newest first. Rows for the student above are highlighted.</p>
          </div>
          {pick.sessionId && (
            <Link to={`/sessions/${pick.sessionId}/attendance`} className="text-link">
              Open check-in
            </Link>
          )}
        </div>
        {!pick.sessionId ? (
          <EmptyState icon={<PencilIcon size={20} />} title="Choose a session to see its corrections" />
        ) : history.state.kind === 'error' ? (
          <ErrorState error={history.state.error} onRetry={history.reload} />
        ) : history.state.kind === 'ready' && history.state.data.length === 0 ? (
          <EmptyState title="No corrections for this session yet" />
        ) : (
          <div className="table-scroll">
            <table className="table compact-table history-table">
              <colgroup>
                <col style={{ width: 140 }} />
                <col style={{ width: 140 }} />
                <col style={{ width: 170 }} />
                <col />
                <col style={{ width: 170 }} />
              </colgroup>
              <thead>
                <tr>
                  <th scope="col">When</th>
                  <th scope="col">Student ID</th>
                  <th scope="col">Change</th>
                  <th scope="col">Reason</th>
                  <th scope="col">By</th>
                </tr>
              </thead>
              <tbody>
                {history.state.kind === 'loading' ? (
                  <SkeletonRows columns={5} />
                ) : (
                  history.state.data.map((c) => (
                    <tr key={c.id} className={c.studentId === id ? 'row-new' : undefined}>
                      <td>{formatInstant(c.correctedAt)}</td>
                      <td className="cell-strong mono">{c.studentId}</td>
                      <td>
                        {c.oldPresent === null ? 'No mark' : c.oldPresent ? 'Present' : 'Absent'} →{' '}
                        <strong>{c.newPresent ? 'Present' : 'Absent'}</strong>
                        <span className="cell-sub">mark version {c.markVersion}</span>
                      </td>
                      <td className="cell-wrap">{c.reason}</td>
                      <td className="cell-truncate" title={`${c.correctedBy} (${c.role})`}>
                        {c.correctedBy}
                        <span className="cell-sub">{c.role === 'DIRECTOR' ? 'Director' : 'Coordinator'}</span>
                      </td>
                    </tr>
                  ))
                )}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </>
  );
}
