// Typed calls to the backend services. Vite proxies /api/placement to
// placement-service (8081) and /api/import to import-service (8090).

const PLACEMENT = '/api/placement';
const IMPORT = '/api/import';

export interface Drive {
  id: number;
  companyName: string;
  driveDate: string; // YYYY-MM-DD
  startTime: string; // HH:mm or HH:mm:ss
  endTime: string;
  venue: string;
}

export interface NewDrive {
  companyName: string;
  driveDate: string;
  startTime: string; // HH:mm
  endTime: string; // HH:mm
  venue: string;
}

export type ImportType = 'shortlist' | 'attendance' | 'timetable';

export type ImportStatus = 'PUBLISHED' | 'NOTHING_TO_PUBLISH' | 'PUBLISH_FAILED';

export interface RejectedRow {
  row: number;
  value: string | null;
  reason: string;
  sheet?: 'slots' | 'enrolments';
}

export interface ImportReport {
  uploadId: string;
  type: string;
  targetId: string;
  fileName: string;
  totalRows: number;
  acceptedCount: number;
  rejectedCount: number;
  rejected: RejectedRow[];
  status: ImportStatus;
  uploadedAt: string;
}

/** A failed request. `unreachable` means the service itself did not answer. */
export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly unreachable: boolean,
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

// When a proxied service is down, the Vite dev proxy answers with an empty
// 5xx body (or fetch itself fails), so both count as "unreachable".
async function send(url: string, init?: RequestInit): Promise<Response> {
  try {
    return await fetch(url, init);
  } catch {
    throw new ApiError('Network error', 0, true);
  }
}

async function readJson(res: Response): Promise<unknown> {
  const text = await res.text();
  if (!text) return null;
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}

function isUnreachable(res: Response, body: unknown): boolean {
  return body === null && (res.status === 500 || res.status === 502 || res.status === 503 || res.status === 504);
}

/** Pulls a readable message out of FastAPI or Spring error bodies. */
function messageFrom(body: unknown, fallback: string): string {
  if (body && typeof body === 'object') {
    const b = body as Record<string, unknown>;
    if (typeof b.detail === 'string') return b.detail;
    if (Array.isArray(b.detail) && b.detail.length > 0) {
      const first = b.detail[0] as Record<string, unknown>;
      const field = Array.isArray(first.loc) ? String(first.loc[first.loc.length - 1]) : '';
      const msg = typeof first.msg === 'string' ? first.msg : '';
      if (msg) return field ? `${field}: ${msg}` : msg;
    }
    if (typeof b.message === 'string' && b.message) return b.message;
    if (typeof b.error === 'string' && b.error) return b.error;
  }
  return fallback;
}

export async function listDrives(signal?: AbortSignal): Promise<Drive[]> {
  const res = await send(`${PLACEMENT}/drives`, { signal });
  const body = await readJson(res);
  if (!res.ok) {
    throw new ApiError(messageFrom(body, `placement-service answered ${res.status}`), res.status, isUnreachable(res, body));
  }
  if (!Array.isArray(body)) {
    throw new ApiError('placement-service sent an unexpected response', res.status, false);
  }
  return body as Drive[];
}

export async function createDrive(drive: NewDrive): Promise<Drive> {
  const res = await send(`${PLACEMENT}/drives`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(drive),
  });
  const body = await readJson(res);
  if (!res.ok) {
    throw new ApiError(messageFrom(body, `placement-service answered ${res.status}`), res.status, isUnreachable(res, body));
  }
  return body as Drive;
}

export const importIdField: Record<ImportType, 'roundId' | 'sessionId' | 'semester'> = {
  shortlist: 'roundId',
  attendance: 'sessionId',
  timetable: 'semester',
};

export function templateUrl(type: ImportType): string {
  return `${IMPORT}/imports/templates/${type}`;
}

function isReport(body: unknown): body is ImportReport {
  return !!body && typeof body === 'object' && 'uploadId' in body && 'status' in body;
}

/**
 * Uploads a file for checking. 200, 422 and 503 all carry a saved report,
 * so they resolve; anything else (400 included) throws an ApiError.
 */
export async function uploadImport(type: ImportType, file: File, id: string): Promise<ImportReport> {
  const form = new FormData();
  form.append('file', file);
  form.append(importIdField[type], id);

  const res = await send(`${IMPORT}/imports/${type}`, { method: 'POST', body: form });
  const body = await readJson(res);

  if ((res.ok || res.status === 422 || res.status === 503) && isReport(body)) {
    return body;
  }
  throw new ApiError(messageFrom(body, `import-service answered ${res.status}`), res.status, isUnreachable(res, body));
}
