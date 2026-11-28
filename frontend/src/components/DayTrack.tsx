import { DAY_END, DAY_RANGE_TEXT, DAY_START, formatDuration, formatHM } from '../time';

const SPAN = DAY_END - DAY_START;
/** Tick positions in minutes after midnight: the day's edges plus every two hours between. */
const TICKS = [DAY_START, 9 * 60, 11 * 60, 13 * 60, 15 * 60, DAY_END];

function percent(minutes: number): number {
  const clamped = Math.min(Math.max(minutes, DAY_START), DAY_END);
  return ((clamped - DAY_START) / SPAN) * 100;
}

function clock(minutes: number): string {
  return `${String(Math.floor(minutes / 60)).padStart(2, '0')}:${String(minutes % 60).padStart(2, '0')}`;
}

interface DayTrackProps {
  /** Minutes after midnight; null when not known yet. */
  start: number | null;
  end: number | null;
  /** Track thickness in px: 10 in the table, 14 on the create page. */
  height?: number;
  /** Show the range and duration under the track. */
  showMeta?: boolean;
  /** Show time labels (day start, 9:00, 11:00, ..., day end) under the track. */
  showTicks?: boolean;
}

/**
 * The college day (DAY_START to DAY_END in time.ts) as a grey track, with the drive drawn
 * on it in maroon. Times outside the day are clamped to the track edges.
 */
export function DayTrack({ start, end, height = 10, showMeta = false, showTicks = false }: DayTrackProps) {
  const valid = start !== null && end !== null && end > start;
  const left = start !== null ? percent(start) : 0;
  const right = valid ? percent(end) : left;

  const label = valid
    ? `${clock(start)} to ${clock(end)}, ${formatDuration(end - start)}, against the college day from ${DAY_RANGE_TEXT}`
    : `College day from ${DAY_RANGE_TEXT}`;

  return (
    <div>
      <div className="track" style={{ height }} role="img" aria-label={label}>
        {valid && right > left && <span className="track-bar" style={{ left: `${left}%`, width: `${right - left}%` }} />}
        {valid && right <= left && <span className="track-marker" style={{ left: `${left}%` }} />}
        {!valid && start !== null && <span className="track-marker" style={{ left: `${left}%` }} />}
      </div>
      {showMeta && valid && (
        <div className="track-meta">
          <span className="track-range">
            {clock(start)} – {clock(end)}
          </span>
          <span className="track-duration">{formatDuration(end - start)}</span>
        </div>
      )}
      {showTicks && (
        <div className="track-ticks" aria-hidden="true">
          {TICKS.map((m) => (
            <span key={m} style={{ left: `${percent(m)}%` }}>
              {formatHM(m)}
            </span>
          ))}
        </div>
      )}
    </div>
  );
}
