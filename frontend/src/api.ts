// Typed calls to the backend services. Vite proxies /api/<service> to each
// service (see vite.config.ts) and strips the prefix.

import { authHeaders } from './auth';

export type ServiceName = 'placement' | 'attendance' | 'timetable' | 'checkin' | 'import';

export const SERVICES: Record<ServiceName, { label: string; port: number; base: string }> = {
  placement: { label: 'placement-service', port: 8081, base: '/api/placement' },
  attendance: { label: 'attendance-service', port: 8082, base: '/api/attendance' },
  timetable: { label: 'timetable-service', port: 8083, base: '/api/timetable' },
  checkin: { label: 'checkin-service', port: 8084, base: '/api/checkin' },
  import: { label: 'import-service', port: 8090, base: '/api/import' },
};

// ---------- Types: placement-service ----------

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

export type SessionStatus = 'SCHEDULED' | 'CANCELLED';

export interface Session {
  id: number;
  roundId: number;
  driveId: number;
  date: string;
  startTime: string;
  endTime: string;
  room: string;
  status: SessionStatus;
}

export interface Round {
  id: number;
  driveId: number;
  name: string;
  sequence: number;
  sessions: Session[];
}

export interface DriveDetail extends Drive {
  rounds: Round[];
}

export interface NewRound {
  name: string;
  sequence: number;
}

export interface SessionInput {
  date: string;
  startTime: string;
  endTime: string;
  room: string;
}

// ---------- Types: timetable-service ----------

export interface Clash {
  studentId: string;
  sectionCode: string;
  courseName: string;
  dayOfWeek: string;
  slotStart: string;
  slotEnd: string;
  room: string;
}

export interface ClashResult {
  roundId: number;
  date: string;
  semester: string | null;
  windowStart: string | null;
  windowEnd: string | null;
  clashes: Clash[];
  lastPublished: { eventId: string; queuedAt: string; clashCount: number; current: boolean } | null;
}

// ---------- Types: attendance-service ----------

export interface OdRecord {
  studentId: string;
  date: string;
  sectionCode: string;
  courseName: string;
  slotStart: string;
  slotEnd: string;
  sessionId: number;
  roundId: number;
  reason: string;
  status: 'GRANTED' | 'REVOKED';
  version: number;
  updatedAt: string;
}

export interface Correction {
  id: number;
  sessionId: number;
  studentId: string;
  oldPresent: boolean | null;
  newPresent: boolean;
  reason: string;
  correctedBy: string;
  role: string;
  correctedAt: string;
  markId: string;
  markVersion: number;
}

export interface NewCorrection {
  sessionId: number;
  studentId: string;
  present: boolean;
  reason: string;
}

export interface AttendanceMark {
  markId: string;
  sessionId: number;
  studentId: string;
  version: number;
  present: boolean;
  source: string; // RFID | MANUAL | FILE | CORRECTION
  markedBy: string;
  markedAt: string;
  receivedAt: string;
}

// ---------- Types: checkin-service ----------

export type MarkSource = 'RFID' | 'MANUAL' | 'FILE';

export interface CheckinMark {
  markId: string;
  sessionId: number;
  roundId: number;
  studentId: string;
  version: number;
  present: boolean;
  source: MarkSource;
  markedBy: string;
  markedAt: string;
  createdAt: string;
}

export interface Device {
  deviceId: string;
  room: string;
  active: boolean;
  createdBy: string;
  createdAt: string;
  lastSeenAt: string | null;
}

export interface DeviceKey {
  deviceId: string;
  room: string;
  active: boolean;
  apiKey: string;
  note: string;
}

export interface Card {
  cardUid: string;
  studentId: string;
  enrolledBy: string;
  enrolledAt: string;
}

export interface CardImportResult {
  imported: number;
  unchanged: number;
  total: number;
}

export interface UnknownTap {
  cardUid: string;
  firstSeenAt: string;
  lastSeenAt: string;
  tapCount: number;
  lastDeviceId: string | null;
  lastRoom: string | null;
}

// ---------- Types: import-service ----------

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

// ---------- Errors ----------

export interface FieldProblem {
  field: string;
  message: string;
}

/** A failed request. `unreachable` means the service itself did not answer. */
export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly unreachable: boolean,
    readonly service: ServiceName | null = null,
    readonly code: string | null = null,
    readonly fieldErrors: FieldProblem[] = [],
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

export function offlineMessage(service: ServiceName): string {
  const s = SERVICES[service];
  return `Can't reach ${s.label} on port ${s.port}. Start it and try again.`;
}

/** A sentence for the person: the offline hint, or the service's own message. */
export function errorText(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.unreachable && error.service) return offlineMessage(error.service);
    return error.message;
  }
  return error instanceof Error ? error.message : 'Something went wrong';
}

export function isAbort(error: unknown): boolean {
  return error instanceof DOMException && error.name === 'AbortError';
}

// ---------- Service health, fed by every request ----------

type HealthListener = (service: ServiceName, reachable: boolean) => void;
const healthListeners = new Set<HealthListener>();

export function onServiceHealth(listener: HealthListener): () => void {
  healthListeners.add(listener);
  return () => healthListeners.delete(listener);
}

function reportHealth(service: ServiceName, reachable: boolean) {
  healthListeners.forEach((l) => l(service, reachable));
}

// ---------- Request plumbing ----------

async function readJson(res: Response): Promise<unknown> {
  const text = await res.text();
  if (!text) return null;
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}

// When a proxied service is down, the Vite dev proxy answers with an empty
// 5xx body (or fetch itself fails), so both count as "unreachable".
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

function fieldErrorsFrom(body: unknown): FieldProblem[] {
  if (!body || typeof body !== 'object') return [];
  const errors = (body as Record<string, unknown>).errors;
  if (!Array.isArray(errors)) return [];
  return errors
    .filter((e): e is FieldProblem => !!e && typeof e === 'object' && typeof (e as FieldProblem).message === 'string')
    .map((e) => ({ field: String(e.field ?? ''), message: e.message }));
}

function codeFrom(body: unknown): string | null {
  if (!body || typeof body !== 'object') return null;
  const code = (body as Record<string, unknown>).code;
  return typeof code === 'string' ? code : null;
}

interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH';
  json?: unknown;
  form?: FormData;
  signal?: AbortSignal;
  /** Send the temporary X-User headers. */
  write?: boolean;
}

interface Answer<T> {
  status: number;
  data: T;
}

async function request<T>(service: ServiceName, path: string, options: RequestOptions = {}): Promise<Answer<T>> {
  const { method = 'GET', json, form, signal, write = false } = options;
  const headers: Record<string, string> = {};
  if (json !== undefined) headers['Content-Type'] = 'application/json';
  if (write) {
    const auth = authHeaders();
    if (!auth) {
      throw new ApiError('Enter a user id under "Signed in as" at the bottom of the sidebar first.', 401, false, service);
    }
    Object.assign(headers, auth);
  }

  let res: Response;
  try {
    res = await fetch(`${SERVICES[service].base}${path}`, {
      method,
      headers,
      body: form ?? (json !== undefined ? JSON.stringify(json) : undefined),
      signal,
    });
  } catch (error) {
    if (isAbort(error)) throw error;
    reportHealth(service, false);
    throw new ApiError('Network error', 0, true, service);
  }

  const body = await readJson(res);
  if (isUnreachable(res, body)) {
    reportHealth(service, false);
    throw new ApiError(`${SERVICES[service].label} did not answer`, res.status, true, service);
  }
  reportHealth(service, true);
  if (!res.ok) {
    throw new ApiError(
      messageFrom(body, `${SERVICES[service].label} answered ${res.status}`),
      res.status,
      false,
      service,
      codeFrom(body),
      fieldErrorsFrom(body),
    );
  }
  return { status: res.status, data: body as T };
}

async function list<T>(service: ServiceName, path: string, signal?: AbortSignal): Promise<T[]> {
  const { status, data } = await request<unknown>(service, path, { signal });
  if (!Array.isArray(data)) {
    throw new ApiError(`${SERVICES[service].label} sent an unexpected response`, status, false, service);
  }
  return data as T[];
}

async function get<T>(service: ServiceName, path: string, signal?: AbortSignal): Promise<T> {
  return (await request<T>(service, path, { signal })).data;
}

/** A cheap read used by the sidebar to see whether a service is up. Resolves to false only when it is offline. */
export async function ping(service: ServiceName, signal?: AbortSignal): Promise<boolean> {
  const path = service === 'import' ? '/openapi.json' : '/outbox?limit=1';
  try {
    await request<unknown>(service, path, { signal });
    return true;
  } catch (error) {
    if (isAbort(error)) throw error;
    return !(error instanceof ApiError && error.unreachable);
  }
}

// ---------- placement-service ----------

export function listDrives(signal?: AbortSignal): Promise<Drive[]> {
  return list<Drive>('placement', '/drives', signal);
}

export async function createDrive(drive: NewDrive): Promise<Drive> {
  return (await request<Drive>('placement', '/drives', { method: 'POST', json: drive, write: true })).data;
}

export function getDrive(id: number, signal?: AbortSignal): Promise<DriveDetail> {
  return get<DriveDetail>('placement', `/drives/${id}`, signal);
}

export async function createRound(driveId: number, round: NewRound): Promise<Round> {
  return (await request<Round>('placement', `/drives/${driveId}/rounds`, { method: 'POST', json: round, write: true }))
    .data;
}

export async function createSession(roundId: number, session: SessionInput): Promise<Session> {
  return (
    await request<Session>('placement', `/rounds/${roundId}/sessions`, { method: 'POST', json: session, write: true })
  ).data;
}

export function getSession(id: number, signal?: AbortSignal): Promise<Session> {
  return get<Session>('placement', `/sessions/${id}`, signal);
}

export async function rescheduleSession(id: number, session: SessionInput): Promise<Session> {
  return (await request<Session>('placement', `/sessions/${id}`, { method: 'PUT', json: session, write: true })).data;
}

export async function cancelSession(id: number): Promise<Session> {
  return (await request<Session>('placement', `/sessions/${id}/cancel`, { method: 'POST', write: true })).data;
}

// ---------- timetable-service ----------

export function getClashes(roundId: number, date: string | null, signal?: AbortSignal): Promise<ClashResult[]> {
  const query = new URLSearchParams({ roundId: String(roundId) });
  if (date) query.set('date', date);
  return list<ClashResult>('timetable', `/clashes?${query}`, signal);
}

// ---------- attendance-service ----------

export function listOd(date: string, includeRevoked: boolean, signal?: AbortSignal): Promise<OdRecord[]> {
  const query = new URLSearchParams({ date, includeRevoked: String(includeRevoked) });
  return list<OdRecord>('attendance', `/od?${query}`, signal);
}

/** Fetches the CSV first, so an offline service gives a message, not a broken file. */
export async function downloadOdCsv(date: string): Promise<void> {
  const service: ServiceName = 'attendance';
  let res: Response;
  try {
    res = await fetch(`${SERVICES[service].base}/od/export?${new URLSearchParams({ date })}`);
  } catch {
    reportHealth(service, false);
    throw new ApiError('Network error', 0, true, service);
  }
  if (!res.ok) {
    const body = await readJson(res);
    if (isUnreachable(res, body)) {
      reportHealth(service, false);
      throw new ApiError('attendance-service did not answer', res.status, true, service);
    }
    throw new ApiError(messageFrom(body, `attendance-service answered ${res.status}`), res.status, false, service);
  }
  reportHealth(service, true);
  const blob = await res.blob();
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = `od-${date}.csv`;
  document.body.appendChild(link);
  link.click();
  link.remove();
  window.setTimeout(() => URL.revokeObjectURL(url), 1000);
}

export async function createCorrection(correction: NewCorrection): Promise<Correction> {
  return (await request<Correction>('attendance', '/corrections', { method: 'POST', json: correction, write: true }))
    .data;
}

export function listCorrections(sessionId: number, signal?: AbortSignal): Promise<Correction[]> {
  return list<Correction>('attendance', `/corrections?sessionId=${sessionId}`, signal);
}

export function listAttendanceMarks(sessionId: number, signal?: AbortSignal): Promise<AttendanceMark[]> {
  return list<AttendanceMark>('attendance', `/marks/sessions/${sessionId}`, signal);
}

// ---------- checkin-service ----------

export function listMarks(sessionId: number, signal?: AbortSignal): Promise<CheckinMark[]> {
  return list<CheckinMark>('checkin', `/sessions/${sessionId}/marks`, signal);
}

/** `created` is false when the latest mark already said the same (200, nothing stored). */
export async function markStudent(
  sessionId: number,
  studentId: string,
  present: boolean,
): Promise<{ mark: CheckinMark; created: boolean }> {
  const { status, data } = await request<CheckinMark>('checkin', `/sessions/${sessionId}/marks`, {
    method: 'POST',
    json: { studentId, present },
    write: true,
  });
  return { mark: data, created: status === 201 };
}

export function listDevices(signal?: AbortSignal): Promise<Device[]> {
  return list<Device>('checkin', '/devices', signal);
}

export async function registerDevice(deviceId: string, room: string): Promise<DeviceKey> {
  return (await request<DeviceKey>('checkin', '/devices', { method: 'POST', json: { deviceId, room }, write: true }))
    .data;
}

export async function setDeviceActive(deviceId: string, active: boolean): Promise<Device> {
  return (
    await request<Device>('checkin', `/devices/${encodeURIComponent(deviceId)}`, {
      method: 'PATCH',
      json: { active },
      write: true,
    })
  ).data;
}

export async function importCards(file: File): Promise<CardImportResult> {
  const form = new FormData();
  form.append('file', file);
  return (await request<CardImportResult>('checkin', '/cards/import', { method: 'POST', form, write: true })).data;
}

export async function enrolCard(cardUid: string, studentId: string): Promise<{ card: Card; created: boolean }> {
  const { status, data } = await request<Card>('checkin', '/cards', {
    method: 'POST',
    json: { cardUid, studentId },
    write: true,
  });
  return { card: data, created: status === 201 };
}

export function listUnknownTaps(signal?: AbortSignal): Promise<UnknownTap[]> {
  return list<UnknownTap>('checkin', '/cards/unknown-taps', signal);
}

// ---------- import-service ----------

export const importIdField: Record<ImportType, 'roundId' | 'sessionId' | 'semester'> = {
  shortlist: 'roundId',
  attendance: 'sessionId',
  timetable: 'semester',
};

export function templateUrl(type: ImportType): string {
  return `${SERVICES.import.base}/imports/templates/${type}`;
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

  let res: Response;
  try {
    res = await fetch(`${SERVICES.import.base}/imports/${type}`, { method: 'POST', body: form });
  } catch {
    reportHealth('import', false);
    throw new ApiError('Network error', 0, true, 'import');
  }
  const body = await readJson(res);

  if ((res.ok || res.status === 422 || res.status === 503) && isReport(body)) {
    reportHealth('import', true);
    return body;
  }
  const unreachable = isUnreachable(res, body);
  reportHealth('import', !unreachable);
  throw new ApiError(messageFrom(body, `import-service answered ${res.status}`), res.status, unreachable, 'import');
}
