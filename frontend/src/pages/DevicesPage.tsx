import { useEffect, useRef, useState, type FormEvent } from 'react';
import {
  ApiError,
  enrolCard,
  errorText,
  importCards,
  listDevices,
  listUnknownTaps,
  registerDevice,
  setDeviceActive,
  type CardImportResult,
  type Device,
  type DeviceKey,
  type FieldProblem,
  type UnknownTap,
} from '../api';
import { useSignIn } from '../auth';
import { Banner } from '../components/Banner';
import { Button } from '../components/Button';
import { DropZone } from '../components/DropZone';
import { Field } from '../components/Field';
import { AlertIcon, CheckIcon, CopyIcon, PlusIcon, ReaderIcon, RefreshIcon } from '../components/Icons';
import { EmptyState, ErrorState, SkeletonRows } from '../components/States';
import { StatusPill } from '../components/StatusPill';
import { formatInstant, STUDENT_ID } from '../time';
import { useResource } from '../useResource';

type Notice = { tone: 'success' | 'error'; text: string } | null;

export function DevicesPage() {
  return (
    <>
      <header className="page-header">
        <div>
          <h1 className="page-title">Readers & cards</h1>
          <p className="page-lede">
            RFID readers are fixed in a room; a tap counts for the session running in that room. Cards map a card UID to
            a student ID.
          </p>
        </div>
      </header>
      <Readers />
      <CardImport />
      <UnknownTaps />
    </>
  );
}

// ---------- Readers ----------

function Readers() {
  const { role } = useSignIn();
  const director = role === 'DIRECTOR';
  const { state, reload } = useResource<Device[]>((signal) => listDevices(signal), []);
  const [registering, setRegistering] = useState(false);
  const [newKey, setNewKey] = useState<DeviceKey | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [notice, setNotice] = useState<Notice>(null);

  async function toggle(device: Device) {
    setBusy(device.deviceId);
    setNotice(null);
    try {
      await setDeviceActive(device.deviceId, !device.active);
      setNotice({
        tone: 'success',
        text: device.active
          ? `${device.deviceId} switched off. Its taps are now refused with 403.`
          : `${device.deviceId} switched on again.`,
      });
      reload();
    } catch (error) {
      setNotice({ tone: 'error', text: `${device.deviceId} was not changed. ${errorText(error)}` });
    } finally {
      setBusy(null);
    }
  }

  return (
    <section className="card" aria-labelledby="readers-title">
      <div className="card-head">
        <div>
          <h2 className="card-title" id="readers-title">
            Readers
          </h2>
          <p className="card-head-sub">
            {director
              ? 'Only a Director registers and switches readers.'
              : 'Only a Director registers and switches readers. Switch "Signed in as" to Director to do so.'}
          </p>
        </div>
        {director && !registering && !newKey && (
          <Button small icon={<PlusIcon size={16} />} onClick={() => setRegistering(true)}>
            Register reader
          </Button>
        )}
      </div>

      {newKey && <KeyReveal deviceKey={newKey} onDone={() => setNewKey(null)} />}

      {registering && (
        <RegisterForm
          onCancel={() => setRegistering(false)}
          onRegistered={(key) => {
            setRegistering(false);
            setNewKey(key);
            reload();
          }}
        />
      )}

      {notice && (
        <div className="card-body" style={{ paddingBottom: 0 }}>
          <Banner tone={notice.tone} onDismiss={() => setNotice(null)}>
            {notice.text}
          </Banner>
        </div>
      )}

      {state.kind === 'error' ? (
        <ErrorState error={state.error} onRetry={reload} />
      ) : state.kind === 'ready' && state.data.length === 0 ? (
        <EmptyState icon={<ReaderIcon size={20} />} title="No readers registered">
          <p className="state-text">
            Register a reader for each room. The key it gets goes into the reader's secrets.h as DEVICE_KEY.
          </p>
        </EmptyState>
      ) : (
        <div className="table-scroll">
          <table className="table compact-table devices-table">
            <colgroup>
              <col style={{ width: '20%' }} />
              <col />
              <col style={{ width: 120 }} />
              <col style={{ width: 150 }} />
              <col style={{ width: 170 }} />
              <col style={{ width: 130 }} />
            </colgroup>
            <thead>
              <tr>
                <th scope="col">Reader</th>
                <th scope="col">Room</th>
                <th scope="col">State</th>
                <th scope="col">Last tap</th>
                <th scope="col">Registered</th>
                <th scope="col">
                  <span className="visually-hidden">Actions</span>
                </th>
              </tr>
            </thead>
            <tbody>
              {state.kind === 'loading' ? (
                <SkeletonRows columns={6} rows={2} />
              ) : (
                state.data.map((d) => (
                  <tr key={d.deviceId} className={d.active ? undefined : 'row-muted'}>
                    <td className="cell-strong mono cell-truncate" title={d.deviceId}>
                      {d.deviceId}
                    </td>
                    <td className="cell-truncate" title={d.room}>
                      {d.room}
                    </td>
                    <td>
                      {d.active ? <StatusPill tone="success">Active</StatusPill> : <StatusPill tone="neutral">Off</StatusPill>}
                    </td>
                    <td>{d.lastSeenAt ? formatInstant(d.lastSeenAt) : 'Never'}</td>
                    <td>
                      {formatInstant(d.createdAt)}
                      <span className="cell-sub">by {d.createdBy}</span>
                    </td>
                    <td>
                      <div className="row-actions">
                        <Button
                          variant="secondary"
                          small
                          disabled={!director}
                          title={director ? undefined : 'Director only'}
                          loading={busy === d.deviceId}
                          loadingText="Saving"
                          onClick={() => toggle(d)}
                        >
                          {d.active ? 'Deactivate' : 'Activate'}
                        </Button>
                      </div>
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}

function RegisterForm({ onCancel, onRegistered }: { onCancel: () => void; onRegistered: (key: DeviceKey) => void }) {
  const [deviceId, setDeviceId] = useState('');
  const [room, setRoom] = useState('');
  const [showErrors, setShowErrors] = useState(false);
  const [serverErrors, setServerErrors] = useState<{ deviceId?: string; room?: string }>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const idRef = useRef<HTMLInputElement>(null);

  useEffect(() => idRef.current?.focus(), []);

  const errors = {
    deviceId: !deviceId ? 'Enter a reader id' : !/^[A-Za-z0-9_-]{1,40}$/.test(deviceId) ? "1-40 letters, digits, '-' or '_'" : undefined,
    room: !room.trim() ? 'Enter the room the reader is fixed in' : undefined,
  };

  async function submit(event: FormEvent) {
    event.preventDefault();
    setFormError(null);
    if (errors.deviceId || errors.room) {
      setShowErrors(true);
      return;
    }
    setSubmitting(true);
    try {
      onRegistered(await registerDevice(deviceId, room.trim()));
    } catch (error) {
      setSubmitting(false);
      if (error instanceof ApiError && error.code === 'DEVICE_EXISTS') {
        setServerErrors({ deviceId: error.message });
      } else {
        const byField: { deviceId?: string; room?: string } = {};
        if (error instanceof ApiError) {
          for (const p of error.fieldErrors) if (p.field === 'deviceId' || p.field === 'room') byField[p.field] = p.message;
        }
        setServerErrors(byField);
        setFormError(errorText(error));
      }
    }
  }

  return (
    <form className="panel" onSubmit={submit} noValidate aria-label="Register reader">
      <p className="panel-title">Register reader</p>
      {formError && <Banner tone="error">{formError}</Banner>}
      <div className="form-grid-2">
        <Field
          id="dev-id"
          label="Reader id"
          error={(showErrors ? errors.deviceId : undefined) ?? serverErrors.deviceId}
          helper="Becomes the first part of the key, e.g. R-HALL-A"
        >
          <input
            ref={idRef}
            className="input mono"
            type="text"
            maxLength={40}
            value={deviceId}
            placeholder="R-HALL-A"
            autoComplete="off"
            onChange={(e) => {
              setDeviceId(e.target.value.trim());
              setServerErrors((s) => ({ ...s, deviceId: undefined }));
            }}
          />
        </Field>
        <Field
          id="dev-room"
          label="Room"
          error={(showErrors ? errors.room : undefined) ?? serverErrors.room}
          helper="Exactly as sessions name it; case and outer spaces are ignored"
        >
          <input
            className="input"
            type="text"
            maxLength={60}
            value={room}
            placeholder="Hall A"
            onChange={(e) => {
              setRoom(e.target.value);
              setServerErrors((s) => ({ ...s, room: undefined }));
            }}
          />
        </Field>
      </div>
      <div className="panel-actions">
        <Button variant="secondary" onClick={onCancel} disabled={submitting}>
          Close
        </Button>
        <Button type="submit" loading={submitting} loadingText="Registering">
          Register and show key
        </Button>
      </div>
    </form>
  );
}

function KeyReveal({ deviceKey, onDone }: { deviceKey: DeviceKey; onDone: () => void }) {
  const [copied, setCopied] = useState<'yes' | 'failed' | null>(null);
  const keyRef = useRef<HTMLInputElement>(null);

  useEffect(() => keyRef.current?.focus(), []);

  async function copy() {
    try {
      await navigator.clipboard.writeText(deviceKey.apiKey);
      setCopied('yes');
    } catch {
      // Clipboard API is blocked on plain http from another host; select it for Ctrl+C instead.
      keyRef.current?.select();
      setCopied('failed');
    }
  }

  return (
    <div className="panel key-reveal" role="group" aria-labelledby="key-title">
      <p className="panel-title" id="key-title">
        Key for {deviceKey.deviceId} ({deviceKey.room})
      </p>
      <div className="key-warning" role="alert">
        <AlertIcon size={18} />
        <span>
          <strong>This key is shown only once.</strong> Copy it into the reader's secrets.h as DEVICE_KEY now. Only a hash
          is stored, so it cannot be shown again; a lost key means issuing a new one.
        </span>
      </div>
      <div className="key-row">
        <label className="visually-hidden" htmlFor="device-key">
          Device key
        </label>
        <input
          ref={keyRef}
          id="device-key"
          className="input mono"
          type="text"
          readOnly
          value={deviceKey.apiKey}
          onFocus={(e) => e.target.select()}
        />
        <Button variant="secondary" icon={copied === 'yes' ? <CheckIcon /> : <CopyIcon />} onClick={copy}>
          {copied === 'yes' ? 'Copied' : 'Copy key'}
        </Button>
      </div>
      {copied === 'failed' && <p className="field-helper">The browser blocked copying; the key is selected, press Ctrl+C.</p>}
      <div className="panel-actions">
        <Button onClick={onDone}>I've stored the key</Button>
      </div>
    </div>
  );
}

// ---------- Card CSV import ----------

function CardImport() {
  const [file, setFile] = useState<File | null>(null);
  const [fileError, setFileError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [result, setResult] = useState<CardImportResult | null>(null);
  const [problems, setProblems] = useState<{ message: string; lines: FieldProblem[] } | null>(null);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setResult(null);
    setProblems(null);
    if (!file) {
      setFileError('Choose a .csv file');
      return;
    }
    setSubmitting(true);
    try {
      setResult(await importCards(file));
      setFile(null);
    } catch (error) {
      setProblems({
        message: errorText(error),
        lines: error instanceof ApiError ? error.fieldErrors : [],
      });
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <section className="card" aria-labelledby="import-title">
      <div className="card-head">
        <div>
          <h2 className="card-title" id="import-title">
            Import cards
          </h2>
          <p className="card-head-sub">
            CSV with columns studentId,cardUid (header optional). Any bad line refuses the whole file.
          </p>
        </div>
      </div>
      <form className="card-body card-import" onSubmit={submit} noValidate>
        <DropZone
          id="cards-file"
          file={file}
          accept=".csv,text/csv"
          hint="studentId,cardUid per line, .csv"
          error={fileError}
          disabled={submitting}
          onFile={(f) => {
            setFileError(f.name.toLowerCase().endsWith('.csv') ? null : 'Only .csv files can be imported');
            setFile(f);
            setResult(null);
            setProblems(null);
          }}
        />
        <Button type="submit" className="card-import-submit" loading={submitting} loadingText="Importing" disabled={!!fileError}>
          Import cards
        </Button>
      </form>

      {result && (
        <div className="card-body" style={{ paddingTop: 0 }}>
          <Banner tone="success" onDismiss={() => setResult(null)}>
            {result.imported} new {result.imported === 1 ? 'card' : 'cards'} enrolled, {result.unchanged} already
            enrolled, {result.total} in the file.
          </Banner>
        </div>
      )}

      {problems && (
        <>
          <div className="card-body" style={{ paddingTop: 0, paddingBottom: problems.lines.length ? 0 : undefined }}>
            <Banner tone="error" onDismiss={() => setProblems(null)}>
              {problems.message}
            </Banner>
          </div>
          {problems.lines.length > 0 && (
            <div className="table-scroll">
              <table className="table rejected-table">
                <colgroup>
                  <col style={{ width: 140 }} />
                  <col />
                </colgroup>
                <thead>
                  <tr>
                    <th scope="col">Where</th>
                    <th scope="col">Problem</th>
                  </tr>
                </thead>
                <tbody>
                  {problems.lines.map((p, i) => (
                    <tr key={i}>
                      <td className="cell-strong">{p.field}</td>
                      <td>{p.message}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </>
      )}
    </section>
  );
}

// ---------- Unknown taps ----------

function UnknownTaps() {
  const { state, reload } = useResource<UnknownTap[]>((signal) => listUnknownTaps(signal), []);
  const [notice, setNotice] = useState<Notice>(null);
  const [refreshing, setRefreshing] = useState(false);

  return (
    <section className="card" aria-labelledby="unknown-title">
      <div className="card-head">
        <div>
          <h2 className="card-title" id="unknown-title">
            Unknown taps
          </h2>
          <p className="card-head-sub">Cards tapped on a reader but not enrolled yet, most recent first.</p>
        </div>
        <Button
          variant="secondary"
          small
          icon={<RefreshIcon size={16} />}
          loading={refreshing}
          loadingText="Refreshing"
          onClick={() => {
            setRefreshing(true);
            reload();
            window.setTimeout(() => setRefreshing(false), 400);
          }}
        >
          Refresh
        </Button>
      </div>

      {notice && (
        <div className="card-body" style={{ paddingBottom: 0 }}>
          <Banner tone={notice.tone} onDismiss={() => setNotice(null)}>
            {notice.text}
          </Banner>
        </div>
      )}

      {state.kind === 'error' ? (
        <ErrorState error={state.error} onRetry={reload} />
      ) : state.kind === 'ready' && state.data.length === 0 ? (
        <EmptyState icon={<ReaderIcon size={20} />} title="No unknown taps">
          <p className="state-text">When someone taps a card that is not enrolled, it is listed here to enrol.</p>
        </EmptyState>
      ) : (
        <div className="table-scroll">
          <table className="table compact-table unknown-table">
            <colgroup>
              <col style={{ width: 170 }} />
              <col style={{ width: 80 }} />
              <col style={{ width: 140 }} />
              <col style={{ width: '18%' }} />
              <col />
            </colgroup>
            <thead>
              <tr>
                <th scope="col">Card UID</th>
                <th scope="col">Taps</th>
                <th scope="col">Last tap</th>
                <th scope="col">Last reader</th>
                <th scope="col">Enrol to student</th>
              </tr>
            </thead>
            <tbody>
              {state.kind === 'loading' ? (
                <SkeletonRows columns={5} rows={2} />
              ) : (
                state.data.map((t) => (
                  <tr key={t.cardUid}>
                    <td className="cell-strong mono">{t.cardUid}</td>
                    <td>{t.tapCount}</td>
                    <td>
                      {formatInstant(t.lastSeenAt)}
                      {t.tapCount > 1 && <span className="cell-sub">first {formatInstant(t.firstSeenAt)}</span>}
                    </td>
                    <td className="cell-truncate" title={t.lastRoom ?? undefined}>
                      {t.lastDeviceId ?? '—'}
                      {t.lastRoom && <span className="cell-sub">{t.lastRoom}</span>}
                    </td>
                    <td>
                      <EnrolInline
                        cardUid={t.cardUid}
                        onEnrolled={(studentId) => {
                          setNotice({ tone: 'success', text: `Card ${t.cardUid} enrolled to ${studentId}. Their next tap counts.` });
                          reload();
                        }}
                      />
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}

function EnrolInline({ cardUid, onEnrolled }: { cardUid: string; onEnrolled: (studentId: string) => void }) {
  const [studentId, setStudentId] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const inputId = `enrol-${cardUid}`;

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!STUDENT_ID.test(studentId)) {
      setError('Exactly 10 digits');
      return;
    }
    setError(null);
    setSubmitting(true);
    try {
      await enrolCard(cardUid, studentId);
      onEnrolled(studentId);
    } catch (err) {
      setError(errorText(err));
      setSubmitting(false);
    }
  }

  return (
    <form className="enrol-inline" onSubmit={submit} noValidate>
      <label className="visually-hidden" htmlFor={inputId}>
        Student ID for card {cardUid}
      </label>
      <input
        id={inputId}
        className="input mono"
        type="text"
        inputMode="numeric"
        maxLength={10}
        placeholder="Student ID"
        value={studentId}
        aria-invalid={error ? true : undefined}
        aria-describedby={error ? `${inputId}-error` : undefined}
        onChange={(e) => {
          setStudentId(e.target.value.replace(/\D/g, ''));
          setError(null);
        }}
      />
      <Button type="submit" variant="secondary" loading={submitting} loadingText="Enrolling">
        Enrol
      </Button>
      {error && (
        <p className="field-error enrol-error" id={`${inputId}-error`}>
          {error}
        </p>
      )}
    </form>
  );
}
