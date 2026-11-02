import { DAY_END, DAY_START, formatDuration } from '../time';

const SPAN = DAY_END - DAY_START;
const TICKS = [8, 10, 12, 14, 17];

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
  /** Show 8:00 / 10:00 / ... labels under the track. */
  showTicks?: boolean;
}

/**
 * The college day from 8:00 to 17:00 as a grey track, with the drive drawn
 * on it in maroon. Times outside the day are clamped to the track edges.
 */
export function DayTrack({ start, end, height = 10, showMeta = false, showTicks = false }: DayTrackProps) {
  const valid = start !== null && end !== null && end > start;
  const left = start !== null ? percent(start) : 0;
  const right = valid ? percent(end) : left;

  const label = valid
    ? `${clock(start)} to ${clock(end)}, ${formatDuration(end - start)}, against the college day from 8:00 to 17:00`
    : 'College day from 8:00 to 17:00';

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
          {TICKS.map((h) => (
            <span key={h} style={{ left: `${percent(h * 60)}%` }}>
              {h}:00
            </span>
          ))}
        </div>
      )}
    </div>
  );
}
