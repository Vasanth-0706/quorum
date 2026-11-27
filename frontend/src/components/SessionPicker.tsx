import { getDrive, listDrives, type Drive, type DriveDetail } from '../api';
import { formatDriveDate, formatTimeRange } from '../time';
import { useResource, type Resource } from '../useResource';
import { Field } from './Field';

export interface Pick {
  driveId: number | null;
  roundId: number | null;
  sessionId: number | null;
}

interface SessionPickerProps {
  value: Pick;
  onChange: (next: Pick) => void;
  /** Also choose a session of the round (Corrections), not only the round (Clashes). */
  withSession?: boolean;
}

/** Cascading selects: drive, then one of its rounds, then (optionally) one of the round's sessions. */
export function SessionPicker({ value, onChange, withSession = false }: SessionPickerProps) {
  const drives = useResource<Drive[]>((signal) => listDrives(signal), []);
  const detail = useDriveDetail(value.driveId);

  const rounds = detail.kind === 'ready' ? detail.data.rounds : [];
  const round = rounds.find((r) => r.id === value.roundId) ?? null;

  const driveHelper =
    drives.state.kind === 'error'
      ? "Can't reach placement-service on port 8081"
      : drives.state.kind === 'ready' && drives.state.data.length === 0
        ? 'No drives yet'
        : undefined;
  const roundHelper =
    detail.kind === 'error'
      ? "Can't load this drive's rounds"
      : detail.kind === 'ready' && rounds.length === 0
        ? 'This drive has no rounds yet'
        : undefined;

  return (
    <div className="picker">
      <Field id="pick-drive" label="Drive" error={drives.state.kind === 'error' ? driveHelper : undefined} helper={drives.state.kind === 'error' ? undefined : driveHelper}>
        <select
          className="input"
          value={value.driveId ?? ''}
          disabled={drives.state.kind !== 'ready'}
          onChange={(e) =>
            onChange({ driveId: e.target.value ? Number(e.target.value) : null, roundId: null, sessionId: null })
          }
        >
          <option value="">{drives.state.kind === 'loading' ? 'Loading drives' : 'Choose a drive'}</option>
          {drives.state.kind === 'ready' &&
            drives.state.data.map((d) => (
              <option key={d.id} value={d.id}>
                #{d.id} {d.companyName}, {formatDriveDate(d.driveDate)}
              </option>
            ))}
        </select>
      </Field>

      <Field id="pick-round" label="Round" error={detail.kind === 'error' ? roundHelper : undefined} helper={detail.kind === 'error' ? undefined : roundHelper}>
        <select
          className="input"
          value={value.roundId ?? ''}
          disabled={detail.kind !== 'ready' || rounds.length === 0}
          onChange={(e) =>
            onChange({ ...value, roundId: e.target.value ? Number(e.target.value) : null, sessionId: null })
          }
        >
          <option value="">{value.driveId && detail.kind === 'loading' ? 'Loading rounds' : 'Choose a round'}</option>
          {rounds.map((r) => (
            <option key={r.id} value={r.id}>
              Round {r.sequence} · {r.name} (#{r.id})
            </option>
          ))}
        </select>
      </Field>

      {withSession && (
        <Field
          id="pick-session"
          label="Session"
          helper={round && round.sessions.length === 0 ? 'This round has no sessions yet' : undefined}
        >
          <select
            className="input"
            value={value.sessionId ?? ''}
            disabled={!round || round.sessions.length === 0}
            onChange={(e) => onChange({ ...value, sessionId: e.target.value ? Number(e.target.value) : null })}
          >
            <option value="">Choose a session</option>
            {round?.sessions.map((s) => (
              <option key={s.id} value={s.id}>
                #{s.id} {formatDriveDate(s.date)}, {formatTimeRange(s.startTime, s.endTime)}, {s.room}
                {s.status === 'CANCELLED' ? ' (cancelled)' : ''}
              </option>
            ))}
          </select>
        </Field>
      )}
    </div>
  );
}

/** The chosen drive with its rounds and sessions; shared so pages and the picker agree. */
export function useDriveDetail(driveId: number | null): Resource<DriveDetail> {
  const { state } = useResource<DriveDetail>(driveId ? (signal) => getDrive(driveId, signal) : null, [driveId]);
  return state;
}
