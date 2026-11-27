import { useEffect, useRef, useState, type FormEvent } from 'react';
import { Link, useParams } from 'react-router-dom';
import {
  ApiError,
  cancelSession,
  createRound,
  createSession,
  errorText,
  getDrive,
  rescheduleSession,
  type DriveDetail,
  type Round,
  type Session,
  type SessionInput,
} from '../api';
import { Banner } from '../components/Banner';
import { Button, buttonClass } from '../components/Button';
import { DayTrack } from '../components/DayTrack';
import { Field } from '../components/Field';
import { CalendarIcon, ChevronLeftIcon, ChevronRightIcon, PlusIcon } from '../components/Icons';
import { EmptyState, ErrorState } from '../components/States';
import { StatusPill } from '../components/StatusPill';
import { formatDriveDate, formatTimeRange, shortTime, toMinutes } from '../time';
import { useResource } from '../useResource';

type Notice = { tone: 'success' | 'error'; text: string } | null;

/** What is open under a session row: the reschedule form or the cancel confirm step. */
type RowAction = { sessionId: number; kind: 'reschedule' | 'cancel' } | null;

export function DriveDetailPage() {
  const params = useParams();
  const driveId = Number(params.id);
  const validId = Number.isInteger(driveId) && driveId > 0;

  const { state, reload } = useResource<DriveDetail>(
    validId ? (signal) => getDrive(driveId, signal) : null,
    [driveId],
  );

  const [notice, setNotice] = useState<Notice>(null);
  const [addingRound, setAddingRound] = useState(false);
  const [addingSessionTo, setAddingSessionTo] = useState<number | null>(null);
  const [rowAction, setRowAction] = useState<RowAction>(null);

  if (!validId) {
    return (
      <>
        <BackLink />
        <section className="card">
          <EmptyState title={`"${params.id}" is not a drive id`}>
            <Link to="/drives" className={buttonClass('secondary', true)}>
              Show all drives
            </Link>
          </EmptyState>
        </section>
      </>
    );
  }

  if (state.kind !== 'ready') {
    return (
      <>
        <BackLink />
        <header className="page-header">
          <div>
            <h1 className="page-title">Drive #{driveId}</h1>
          </div>
        </header>
        <section className="card" aria-label="Rounds">
          {state.kind === 'loading' ? (
            <div className="card-body" aria-busy="true">
              <span className="skeleton" style={{ width: '40%' }} />
              <span className="skeleton" style={{ width: '70%', marginTop: 16 }} />
              <span className="skeleton" style={{ width: '55%', marginTop: 16 }} />
            </div>
          ) : (
            <ErrorState
              error={state.error}
              onRetry={reload}
              notFound={`Drive #${driveId} does not exist in placement-service.`}
            />
          )}
        </section>
      </>
    );
  }

  const drive = state.data;
  const nextSequence = drive.rounds.reduce((max, r) => Math.max(max, r.sequence), 0) + 1;

  function done(text: string) {
    setNotice({ tone: 'success', text });
    setRowAction(null);
    setAddingSessionTo(null);
    setAddingRound(false);
    reload();
  }

  return (
    <>
      <BackLink />
      <header className="page-header">
        <div>
          <h1 className="page-title">{drive.companyName}</h1>
          <div className="meta-list">
            <span>
              Drive <strong>#{drive.id}</strong>
            </span>
            <span>{formatDriveDate(drive.driveDate)}</span>
            <span>{formatTimeRange(drive.startTime, drive.endTime)}</span>
            <span>{drive.venue}</span>
          </div>
        </div>
        {!addingRound && (
          <Button icon={<PlusIcon />} onClick={() => setAddingRound(true)}>
            Add round
          </Button>
        )}
      </header>

      {notice && (
        <Banner tone={notice.tone} onDismiss={() => setNotice(null)}>
          {notice.text}
        </Banner>
      )}

      {addingRound && (
        <section className="card" aria-label="New round">
          <RoundForm
            driveId={drive.id}
            defaultSequence={nextSequence}
            taken={drive.rounds.map((r) => r.sequence)}
            onCancel={() => setAddingRound(false)}
            onDone={(round) => done(`Round ${round.sequence}, ${round.name} (#${round.id}) added and published.`)}
          />
        </section>
      )}

      {drive.rounds.length === 0 && !addingRound ? (
        <section className="card">
          <EmptyState icon={<CalendarIcon size={20} />} title="No rounds yet">
            <p className="state-text">
              Add the rounds of this drive (Online test, Technical, HR ...), then give each round its sessions.
            </p>
            <Button icon={<PlusIcon />} onClick={() => setAddingRound(true)}>
              Add round
            </Button>
          </EmptyState>
        </section>
      ) : (
        drive.rounds.map((round) => (
          <RoundCard
            key={round.id}
            drive={drive}
            round={round}
            adding={addingSessionTo === round.id}
            rowAction={rowAction}
            onAdd={() => {
              setAddingSessionTo(round.id);
              setRowAction(null);
            }}
            onCloseAdd={() => setAddingSessionTo(null)}
            onRowAction={(action) => {
              setRowAction(action);
              setAddingSessionTo(null);
            }}
            onDone={done}
            onError={(text) => setNotice({ tone: 'error', text })}
          />
        ))
      )}
    </>
  );
}

function BackLink() {
  return (
    <Link to="/drives" className="back-link">
      <ChevronLeftIcon size={16} />
      Drives
    </Link>
  );
}

// ---------- Round card ----------

interface RoundCardProps {
  drive: DriveDetail;
  round: Round;
  adding: boolean;
  rowAction: RowAction;
  onAdd: () => void;
  onCloseAdd: () => void;
  onRowAction: (action: RowAction) => void;
  onDone: (text: string) => void;
  onError: (text: string) => void;
}

function RoundCard({ drive, round, adding, rowAction, onAdd, onCloseAdd, onRowAction, onDone, onError }: RoundCardProps) {
  const lastSession = round.sessions[round.sessions.length - 1];
  const defaults: SessionInput = lastSession
    ? { date: lastSession.date, startTime: shortTime(lastSession.endTime), endTime: '', room: lastSession.room }
    : { date: drive.driveDate, startTime: shortTime(drive.startTime), endTime: '', room: drive.venue };
  const firstDate = round.sessions.find((s) => s.status === 'SCHEDULED')?.date ?? round.sessions[0]?.date;
  const titleId = `round-${round.id}-title`;

  return (
    <section className="card" aria-labelledby={titleId}>
      <div className="card-head">
        <div>
          <h2 className="card-title" id={titleId}>
            Round {round.sequence} · {round.name}
          </h2>
          <p className="card-head-sub">
            Round #{round.id} · {round.sessions.length} {round.sessions.length === 1 ? 'session' : 'sessions'}
          </p>
        </div>
        <div className="card-head-actions">
          {round.sessions.length > 0 && (
            <Link
              to={`/clashes?driveId=${drive.id}&roundId=${round.id}${firstDate ? `&date=${firstDate}` : ''}`}
              className="text-link"
            >
              Class clashes
              <ChevronRightIcon size={14} />
            </Link>
          )}
          {!adding && (
            <Button variant="secondary" small icon={<PlusIcon size={16} />} onClick={onAdd}>
              Add session
            </Button>
          )}
        </div>
      </div>

      {adding && (
        <SessionForm
          title={`New session for round ${round.sequence}`}
          initial={defaults}
          submitLabel="Add session"
          submittingLabel="Adding session"
          onCancel={onCloseAdd}
          onSubmit={async (input) => {
            const session = await createSession(round.id, input);
            onDone(`Session #${session.id} on ${formatDriveDate(session.date)} added and published.`);
          }}
        />
      )}

      {round.sessions.length === 0 ? (
        !adding && (
          <EmptyState title="No sessions in this round">
            <p className="state-text">Add a session with its date, time and room so students can check in.</p>
          </EmptyState>
        )
      ) : (
        <div className="table-scroll">
          <table className="table sessions-table">
            <colgroup>
              <col style={{ width: 96 }} />
              <col style={{ width: 128 }} />
              <col style={{ width: '24%' }} />
              <col />
              <col style={{ width: 128 }} />
              <col style={{ width: 290 }} />
            </colgroup>
            <thead>
              <tr>
                <th scope="col">Session</th>
                <th scope="col">Date</th>
                <th scope="col">Time</th>
                <th scope="col">Room</th>
                <th scope="col">Status</th>
                <th scope="col">
                  <span className="visually-hidden">Actions</span>
                </th>
              </tr>
            </thead>
            <tbody>
              {round.sessions.map((session) => (
                <SessionRows
                  key={session.id}
                  session={session}
                  action={rowAction?.sessionId === session.id ? rowAction.kind : null}
                  onAction={(kind) => onRowAction(kind ? { sessionId: session.id, kind } : null)}
                  onDone={onDone}
                  onError={onError}
                />
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}

interface SessionRowsProps {
  session: Session;
  action: 'reschedule' | 'cancel' | null;
  onAction: (kind: 'reschedule' | 'cancel' | null) => void;
  onDone: (text: string) => void;
  onError: (text: string) => void;
}

function SessionRows({ session, action, onAction, onDone, onError }: SessionRowsProps) {
  const cancelled = session.status === 'CANCELLED';
  const [cancelling, setCancelling] = useState(false);
  const confirmRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    if (action === 'cancel') confirmRef.current?.focus();
  }, [action]);

  async function confirmCancel() {
    setCancelling(true);
    try {
      await cancelSession(session.id);
      onDone(`Session #${session.id} cancelled. Marks already recorded for it are kept.`);
    } catch (error) {
      onError(`Session #${session.id} was not cancelled. ${errorText(error)}`);
      onAction(null);
    } finally {
      setCancelling(false);
    }
  }

  return (
    <>
      <tr className={cancelled ? 'row-muted' : undefined}>
        <td className="cell-id">#{session.id}</td>
        <td className="cell-strong">{formatDriveDate(session.date)}</td>
        <td>
          <DayTrack start={toMinutes(session.startTime)} end={toMinutes(session.endTime)} showMeta />
        </td>
        <td className="cell-truncate" title={session.room}>
          {session.room}
        </td>
        <td>
          {cancelled ? <StatusPill tone="error">Cancelled</StatusPill> : <StatusPill tone="success">Scheduled</StatusPill>}
        </td>
        <td>
          <div className="row-actions">
            <Link to={`/sessions/${session.id}`} className={buttonClass('secondary', true)}>
              Check-in
            </Link>
            {!cancelled && (
              <>
                <Button
                  variant="ghost"
                  small
                  aria-expanded={action === 'reschedule'}
                  onClick={() => onAction(action === 'reschedule' ? null : 'reschedule')}
                >
                  Reschedule
                </Button>
                <Button
                  variant="ghost"
                  small
                  aria-expanded={action === 'cancel'}
                  onClick={() => onAction(action === 'cancel' ? null : 'cancel')}
                >
                  Cancel
                </Button>
              </>
            )}
          </div>
        </td>
      </tr>

      {action === 'reschedule' && (
        <tr>
          <td colSpan={6} className="cell-panel">
            <SessionForm
              title={`Reschedule session #${session.id}`}
              initial={{
                date: session.date,
                startTime: shortTime(session.startTime),
                endTime: shortTime(session.endTime),
                room: session.room,
              }}
              submitLabel="Save new time"
              submittingLabel="Saving"
              onCancel={() => onAction(null)}
              onSubmit={async (input) => {
                const unchanged =
                  input.date === session.date &&
                  input.startTime === shortTime(session.startTime) &&
                  input.endTime === shortTime(session.endTime) &&
                  input.room === session.room;
                await rescheduleSession(session.id, input);
                onDone(
                  unchanged
                    ? `Session #${session.id} already had that time and room; nothing was published.`
                    : `Session #${session.id} moved to ${formatDriveDate(input.date)}, ${input.startTime} – ${input.endTime}, ${input.room}.`,
                );
              }}
            />
          </td>
        </tr>
      )}

      {action === 'cancel' && (
        <tr>
          <td colSpan={6} className="cell-panel">
            <div className="panel panel-confirm" role="group" aria-labelledby={`cancel-${session.id}-title`}>
              <p className="panel-title" id={`cancel-${session.id}-title`}>
                Cancel session #{session.id} on {formatDriveDate(session.date)},{' '}
                {formatTimeRange(session.startTime, session.endTime)}?
              </p>
              <p>
                Readers stop accepting taps for it and it cannot be rescheduled afterwards. Students already marked
                present keep their mark and their OD.
              </p>
              <div className="panel-actions">
                <Button variant="secondary" onClick={() => onAction(null)} disabled={cancelling}>
                  Keep session
                </Button>
                <Button
                  ref={confirmRef}
                  variant="danger"
                  onClick={confirmCancel}
                  loading={cancelling}
                  loadingText="Cancelling"
                >
                  Cancel session
                </Button>
              </div>
            </div>
          </td>
        </tr>
      )}
    </>
  );
}

// ---------- Forms ----------

type SessionField = keyof SessionInput;

function validateSession(v: SessionInput): Partial<Record<SessionField, string>> {
  const errors: Partial<Record<SessionField, string>> = {};
  if (!v.date) errors.date = 'Pick the date';
  if (!v.startTime) errors.startTime = 'Pick a start time';
  if (!v.endTime) errors.endTime = 'Pick an end time';
  const start = toMinutes(v.startTime);
  const end = toMinutes(v.endTime);
  if (start !== null && end !== null && end <= start) errors.endTime = 'End must be after the start';
  if (!v.room.trim()) errors.room = 'Enter the room';
  else if (v.room.trim().length > 60) errors.room = 'At most 60 characters';
  return errors;
}

/** Maps a 400's field errors onto the form; anything else becomes a form-level message. */
function serverProblems<F extends string>(error: unknown, fields: readonly F[]) {
  const byField: Partial<Record<F, string>> = {};
  if (error instanceof ApiError && error.status === 400) {
    for (const p of error.fieldErrors) {
      if ((fields as readonly string[]).includes(p.field)) byField[p.field as F] = p.message;
    }
  }
  return byField;
}

interface SessionFormProps {
  title: string;
  initial: SessionInput;
  submitLabel: string;
  submittingLabel: string;
  onCancel: () => void;
  onSubmit: (input: SessionInput) => Promise<void>;
}

function SessionForm({ title, initial, submitLabel, submittingLabel, onCancel, onSubmit }: SessionFormProps) {
  const [values, setValues] = useState<SessionInput>(initial);
  const [showErrors, setShowErrors] = useState(false);
  const [serverFieldErrors, setServerFieldErrors] = useState<Partial<Record<SessionField, string>>>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const formRef = useRef<HTMLFormElement>(null);
  const idBase = useRef(`sf-${Math.random().toString(36).slice(2, 8)}`).current;

  useEffect(() => {
    formRef.current?.querySelector<HTMLInputElement>('input')?.focus();
  }, []);

  const errors = validateSession(values);
  const shown = (f: SessionField) => (showErrors ? errors[f] : undefined) ?? serverFieldErrors[f];

  function set(field: SessionField, value: string) {
    setValues((v) => ({ ...v, [field]: value }));
    setServerFieldErrors((e) => ({ ...e, [field]: undefined }));
  }

  async function submit(event: FormEvent) {
    event.preventDefault();
    setFormError(null);
    if (Object.keys(errors).length > 0) {
      setShowErrors(true);
      return;
    }
    setSubmitting(true);
    try {
      await onSubmit({ ...values, room: values.room.trim() });
    } catch (error) {
      setSubmitting(false);
      const byField = serverProblems(error, ['date', 'startTime', 'endTime', 'room'] as const);
      setServerFieldErrors(byField);
      setFormError(errorText(error));
    }
  }

  return (
    <form ref={formRef} className="panel" onSubmit={submit} noValidate aria-label={title}>
      <p className="panel-title">{title}</p>
      {formError && <Banner tone="error">{formError}</Banner>}
      <div className="form-grid">
        <Field id={`${idBase}-date`} label="Date" error={shown('date')}>
          <input className="input" type="date" value={values.date} onChange={(e) => set('date', e.target.value)} />
        </Field>
        <Field id={`${idBase}-start`} label="Starts" error={shown('startTime')}>
          <input
            className="input"
            type="time"
            value={values.startTime}
            onChange={(e) => set('startTime', e.target.value)}
          />
        </Field>
        <Field id={`${idBase}-end`} label="Ends" error={shown('endTime')}>
          <input className="input" type="time" value={values.endTime} onChange={(e) => set('endTime', e.target.value)} />
        </Field>
        <Field id={`${idBase}-room`} label="Room" error={shown('room')}>
          <input
            className="input"
            type="text"
            value={values.room}
            maxLength={60}
            placeholder="e.g. Room 101"
            onChange={(e) => set('room', e.target.value)}
          />
        </Field>
      </div>
      <p className="field-helper">The room must match the room set on its RFID reader, or taps will not find it.</p>
      <div className="panel-actions">
        <Button variant="secondary" onClick={onCancel} disabled={submitting}>
          Close
        </Button>
        <Button type="submit" loading={submitting} loadingText={submittingLabel}>
          {submitLabel}
        </Button>
      </div>
    </form>
  );
}

interface RoundFormProps {
  driveId: number;
  defaultSequence: number;
  taken: number[];
  onCancel: () => void;
  onDone: (round: Round) => void;
}

function RoundForm({ driveId, defaultSequence, taken, onCancel, onDone }: RoundFormProps) {
  const [name, setName] = useState('');
  const [sequence, setSequence] = useState(String(defaultSequence));
  const [showErrors, setShowErrors] = useState(false);
  const [serverErrors, setServerErrors] = useState<{ name?: string; sequence?: string }>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const nameRef = useRef<HTMLInputElement>(null);

  useEffect(() => nameRef.current?.focus(), []);

  const seq = Number(sequence);
  const errors: { name?: string; sequence?: string } = {};
  if (!name.trim()) errors.name = 'Enter the round name';
  else if (name.trim().length > 60) errors.name = 'At most 60 characters';
  if (!/^\d+$/.test(sequence) || seq < 1 || seq > 99) errors.sequence = 'A whole number from 1 to 99';
  else if (taken.includes(seq)) errors.sequence = `Round ${seq} already exists in this drive`;

  async function submit(event: FormEvent) {
    event.preventDefault();
    setFormError(null);
    if (errors.name || errors.sequence) {
      setShowErrors(true);
      return;
    }
    setSubmitting(true);
    try {
      onDone(await createRound(driveId, { name: name.trim(), sequence: seq }));
    } catch (error) {
      setSubmitting(false);
      if (error instanceof ApiError && error.status === 409) {
        setServerErrors({ sequence: error.message });
      } else {
        setServerErrors(serverProblems(error, ['name', 'sequence'] as const));
        setFormError(errorText(error));
      }
    }
  }

  return (
    <form className="panel" onSubmit={submit} noValidate aria-label="New round" style={{ borderBottom: 0 }}>
      <p className="panel-title">New round</p>
      {formError && <Banner tone="error">{formError}</Banner>}
      <div className="form-grid-2">
        <Field id="round-name" label="Name" error={(showErrors ? errors.name : undefined) ?? serverErrors.name}>
          <input
            ref={nameRef}
            className="input"
            type="text"
            value={name}
            maxLength={60}
            placeholder="e.g. Technical interview"
            onChange={(e) => {
              setName(e.target.value);
              setServerErrors((s) => ({ ...s, name: undefined }));
            }}
          />
        </Field>
        <Field
          id="round-sequence"
          label="Order in the drive"
          error={(showErrors ? errors.sequence : undefined) ?? serverErrors.sequence}
          helper="1 for the first round, 2 for the next ..."
        >
          <input
            className="input"
            type="number"
            min={1}
            max={99}
            value={sequence}
            onChange={(e) => {
              setSequence(e.target.value);
              setServerErrors((s) => ({ ...s, sequence: undefined }));
            }}
          />
        </Field>
      </div>
      <div className="panel-actions">
        <Button variant="secondary" onClick={onCancel} disabled={submitting}>
          Close
        </Button>
        <Button type="submit" loading={submitting} loadingText="Adding round">
          Add round
        </Button>
      </div>
    </form>
  );
}
