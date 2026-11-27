import { useMemo, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { downloadOdCsv, errorText, listOd, type OdRecord } from '../api';
import { Banner } from '../components/Banner';
import { Button } from '../components/Button';
import { Field } from '../components/Field';
import { ClipboardCheckIcon, DownloadIcon, SearchIcon } from '../components/Icons';
import { EmptyState, ErrorState, SkeletonRows } from '../components/States';
import { StatusPill } from '../components/StatusPill';
import { Switch } from '../components/Switch';
import { formatDriveDate, formatTimeRange, todayIso, weekdayOf } from '../time';
import { useResource } from '../useResource';

const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/;

export function OdPage() {
  const [params, setParams] = useSearchParams();
  const requested = params.get('date');
  const date = requested && ISO_DATE.test(requested) ? requested : todayIso();
  const showRevoked = params.get('revoked') === '1';
  const [query, setQuery] = useState(params.get('studentId') ?? '');
  const [downloading, setDownloading] = useState(false);
  const [downloadError, setDownloadError] = useState<string | null>(null);

  const { state, reload } = useResource<OdRecord[]>((signal) => listOd(date, showRevoked, signal), [date, showRevoked]);

  function setParam(key: string, value: string | null) {
    const next = new URLSearchParams(params);
    if (value) next.set(key, value);
    else next.delete(key);
    setParams(next, { replace: true });
  }

  const records = state.kind === 'ready' ? state.data : [];
  const q = query.replace(/\D/g, '');
  const visible = useMemo(() => (q ? records.filter((r) => r.studentId.includes(q)) : records), [records, q]);
  const students = new Set(visible.filter((r) => r.status === 'GRANTED').map((r) => r.studentId)).size;
  const granted = visible.filter((r) => r.status === 'GRANTED').length;

  async function download() {
    setDownloadError(null);
    setDownloading(true);
    try {
      await downloadOdCsv(date);
    } catch (error) {
      setDownloadError(`The CSV was not downloaded. ${errorText(error)}`);
    } finally {
      setDownloading(false);
    }
  }

  return (
    <>
      <header className="page-header">
        <div>
          <h1 className="page-title">OD records</h1>
          <p className="page-lede">
            On-Duty granted by attendance-service for class slots that clashed with a placement session the student
            attended. No faculty approval is needed.
          </p>
        </div>
        <Button
          variant="secondary"
          icon={<DownloadIcon />}
          onClick={download}
          loading={downloading}
          loadingText="Preparing CSV"
        >
          Download CSV
        </Button>
      </header>

      {downloadError && (
        <Banner tone="error" onDismiss={() => setDownloadError(null)}>
          {downloadError}
        </Banner>
      )}

      <section className="card" aria-label="OD records">
        <div className="toolbar od-toolbar">
          <div className="od-filters">
            <Field id="od-date" label="Date">
              <input
                className="input"
                type="date"
                value={date}
                onChange={(e) => setParam('date', e.target.value || null)}
              />
            </Field>
            <div className="field">
              <label className="field-label" htmlFor="od-student">
                Student ID
              </label>
              <div className="search">
                <SearchIcon size={16} />
                <input
                  id="od-student"
                  className="input mono"
                  type="search"
                  inputMode="numeric"
                  maxLength={10}
                  placeholder="10 digits"
                  value={query}
                  autoComplete="off"
                  onChange={(e) => setQuery(e.target.value.replace(/\D/g, ''))}
                />
              </div>
            </div>
          </div>
          <Switch
            id="od-revoked"
            label="Show revoked"
            checked={showRevoked}
            onChange={(on) => setParam('revoked', on ? '1' : null)}
          />
        </div>

        {state.kind === 'error' ? (
          <ErrorState error={state.error} onRetry={reload} />
        ) : state.kind === 'ready' && records.length === 0 ? (
          <EmptyState icon={<ClipboardCheckIcon size={20} />} title={`No OD on ${formatDriveDate(date)}`}>
            <p className="state-text">
              OD appears once a shortlisted student with a clashing class is marked present in a session of that date.
            </p>
          </EmptyState>
        ) : state.kind === 'ready' && visible.length === 0 ? (
          <EmptyState title={`No OD for a student ID containing "${q}"`}>
            <Button variant="secondary" small onClick={() => setQuery('')}>
              Clear filter
            </Button>
          </EmptyState>
        ) : (
          <div className="table-scroll">
            <table className="table compact-table od-table">
              <colgroup>
                <col style={{ width: 140 }} />
                <col style={{ width: '22%' }} />
                <col style={{ width: 130 }} />
                <col style={{ width: 100 }} />
                <col />
                <col style={{ width: 120 }} />
              </colgroup>
              <thead>
                <tr>
                  <th scope="col">Student ID</th>
                  <th scope="col">Course</th>
                  <th scope="col">Class slot</th>
                  <th scope="col">Session</th>
                  <th scope="col">Reason</th>
                  <th scope="col">Status</th>
                </tr>
              </thead>
              <tbody>
                {state.kind === 'loading' ? (
                  <SkeletonRows columns={6} rows={4} />
                ) : (
                  visible.map((r) => (
                    <tr
                      key={`${r.studentId}-${r.sectionCode}-${r.slotStart}`}
                      className={r.status === 'REVOKED' ? 'row-muted' : undefined}
                    >
                      <td className="cell-strong mono">{r.studentId}</td>
                      <td className="cell-truncate" title={`${r.courseName} (${r.sectionCode})`}>
                        {r.courseName}
                        <span className="cell-sub">{r.sectionCode}</span>
                      </td>
                      <td>{formatTimeRange(r.slotStart, r.slotEnd)}</td>
                      <td>
                        <Link to={`/sessions/${r.sessionId}`} className="text-link">
                          #{r.sessionId}
                        </Link>
                        <span className="cell-sub">round #{r.roundId}</span>
                      </td>
                      <td className="cell-truncate" title={r.reason}>
                        {r.reason}
                      </td>
                      <td>
                        {r.status === 'GRANTED' ? (
                          <StatusPill tone="success">Granted</StatusPill>
                        ) : (
                          <StatusPill tone="error">Revoked</StatusPill>
                        )}
                        {r.version > 1 && <span className="cell-sub">version {r.version}</span>}
                      </td>
                    </tr>
                  ))
                )}
              </tbody>
            </table>
          </div>
        )}

        <div className="card-footer">
          <span>
            {weekdayOf(date)} {formatDriveDate(date).split(', ')[1]} · the CSV holds the granted OD of this date
          </span>
          {state.kind === 'ready' && (
            <span>
              {granted} granted {granted === 1 ? 'slot' : 'slots'} · {students} {students === 1 ? 'student' : 'students'}
            </span>
          )}
        </div>
      </section>
    </>
  );
}
