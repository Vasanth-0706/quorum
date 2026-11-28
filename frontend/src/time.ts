// Date and time helpers. Dates stay as local YYYY-MM-DD strings so that no
// timezone shifts a drive onto the wrong day.

export const DAY_START = 7 * 60 + 10; // 07:10
export const DAY_END = 17 * 60 + 30; // 17:30

const WEEKDAYS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

/** "09:30" or "09:30:00" to minutes after midnight; null if not a time. */
export function toMinutes(time: string | null | undefined): number | null {
  if (!time) return null;
  const match = /^(\d{1,2}):(\d{2})/.exec(time);
  if (!match) return null;
  const h = Number(match[1]);
  const m = Number(match[2]);
  if (h > 23 || m > 59) return null;
  return h * 60 + m;
}

/** Minutes after midnight to H:MM: 430 to "7:10", 1050 to "17:30". */
export function formatHM(minutes: number): string {
  return `${Math.floor(minutes / 60)}:${String(minutes % 60).padStart(2, '0')}`;
}

/** The college day as "7:10 to 17:30". */
export const DAY_RANGE_TEXT = `${formatHM(DAY_START)} to ${formatHM(DAY_END)}`;

/** "09:30:00" to "09:30". */
export function shortTime(time: string): string {
  return time.slice(0, 5);
}

export function todayIso(): string {
  const d = new Date();
  const mm = String(d.getMonth() + 1).padStart(2, '0');
  const dd = String(d.getDate()).padStart(2, '0');
  return `${d.getFullYear()}-${mm}-${dd}`;
}

/** "2026-11-12" to "Thu, 12 Nov". */
export function formatDriveDate(iso: string): string {
  const [y, m, d] = iso.split('-').map(Number);
  if (!y || !m || !d) return iso;
  const date = new Date(y, m - 1, d);
  return `${WEEKDAYS[date.getDay()]}, ${d} ${MONTHS[m - 1]}`;
}

/** 180 to "3 h", 150 to "2.5 h", 80 to "1 h 20 min", 45 to "45 min". */
export function formatDuration(minutes: number): string {
  if (minutes < 60) return `${minutes} min`;
  const hours = Math.floor(minutes / 60);
  const rest = minutes % 60;
  if (rest === 0) return `${hours} h`;
  if (rest === 30) return `${hours}.5 h`;
  return `${hours} h ${rest} min`;
}

export function formatClock(date: Date): string {
  return `${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`;
}

export function formatFileSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} bytes`;
  if (bytes < 1024 * 1024) return `${Math.max(1, Math.round(bytes / 1024))} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

/** "09:30:00", "11:00" to "09:30 – 11:00". */
export function formatTimeRange(start: string | null | undefined, end: string | null | undefined): string {
  if (!start || !end) return '—';
  return `${shortTime(start)} – ${shortTime(end)}`;
}

/** An ISO instant to local "27 Nov, 10:15:30"; with withDate=false only the clock. */
export function formatInstant(iso: string | null | undefined, withDate = true): string {
  if (!iso) return '—';
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return iso;
  const clock = `${formatClock(date)}:${String(date.getSeconds()).padStart(2, '0')}`;
  return withDate ? `${date.getDate()} ${MONTHS[date.getMonth()]}, ${clock}` : clock;
}

/** "2026-12-04" to "Friday". */
export function weekdayOf(iso: string): string {
  const [y, m, d] = iso.split('-').map(Number);
  if (!y || !m || !d) return '';
  return ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday'][new Date(y, m - 1, d).getDay()];
}

/** Whole days from the date to today; positive when the date is in the past. */
export function daysSince(iso: string): number {
  const [y, m, d] = iso.split('-').map(Number);
  const then = new Date(y, m - 1, d);
  const now = new Date();
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate());
  return Math.round((today.getTime() - then.getTime()) / 86_400_000);
}

export const STUDENT_ID = /^\d{10}$/;
