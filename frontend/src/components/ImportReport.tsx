import type { ImportReport } from '../api';
import { formatClock } from '../time';
import { StatusPill } from './StatusPill';

const REASONS: Record<string, string> = {
  EMPTY: 'Empty cell',
  NOT_NUMERIC: 'Contains something other than digits',
  NOT_10_DIGITS: 'Not 10 digits',
  DUPLICATE: 'Duplicate of an earlier row',
  EMPTY_SECTION_CODE: 'Section code is empty',
  INVALID_DAY: 'Day is not Monday to Saturday',
  BAD_TIME_FORMAT: 'Time is not in HH:mm format',
  START_NOT_BEFORE_END: 'Start time is not before the end time',
  UNKNOWN_SECTION: 'Section is not in the slots sheet',
};

export function reasonText(code: string): string {
  if (REASONS[code]) return REASONS[code];
  const words = code.toLowerCase().replace(/_/g, ' ');
  return words.charAt(0).toUpperCase() + words.slice(1);
}

export function ReportPill({ status }: { status: ImportReport['status'] }) {
  if (status === 'PUBLISHED') return <StatusPill tone="success">Published</StatusPill>;
  if (status === 'NOTHING_TO_PUBLISH') return <StatusPill tone="warning">Nothing to publish</StatusPill>;
  return <StatusPill tone="error">Kafka unavailable, not published</StatusPill>;
}

export const MAX_UPLOAD_BYTES = 5 * 1024 * 1024;

/** null when the file can be sent, else the sentence to show under the drop zone. */
export function fileProblem(file: File, extensions: string[]): string | null {
  const name = file.name.toLowerCase();
  if (!extensions.some((ext) => name.endsWith(ext))) {
    return extensions.length === 1
      ? `${file.name} is not an ${extensions[0]} file.`
      : `${file.name} is not an .xlsx or .csv file.`;
  }
  if (file.size > MAX_UPLOAD_BYTES) return `${file.name} is larger than 5 MB.`;
  return null;
}

interface ImportReportViewProps {
  report: ImportReport;
  checkedAt: Date;
  /** "Round", "Session" or "Semester", shown before the target id. */
  targetLabel: string;
  /** Show the Sheet column (timetables have two sheets). */
  showSheet?: boolean;
  /** Inside another card: no card frame of its own. */
  embedded?: boolean;
  onDismiss?: () => void;
  headingId: string;
}

/** The upload report: totals, an accepted/rejected bar and every rejected row with its Excel row. */
export function ImportReportView({
  report,
  checkedAt,
  targetLabel,
  showSheet = false,
  embedded = false,
  onDismiss,
  headingId,
}: ImportReportViewProps) {
  const total = Math.max(report.totalRows, report.acceptedCount + report.rejectedCount);
  const acceptedPct = total > 0 ? (report.acceptedCount / total) * 100 : 0;
  const rejectedPct = total > 0 ? (report.rejectedCount / total) * 100 : 0;

  const body = (
    <>
      <div className="result-head">
        <div>
          <h3 className="result-file" id={headingId}>
            {report.fileName}
          </h3>
          <p className="result-sub">
            {targetLabel} {report.targetId}, checked at {formatClock(checkedAt)}
          </p>
        </div>
        <div className="result-head-actions">
          <ReportPill status={report.status} />
          {onDismiss && (
            <button type="button" className="text-link text-link-button" onClick={onDismiss}>
              Hide report
            </button>
          )}
        </div>
      </div>

      <div className="card-body">
        <div className="figures">
          <div>
            <div className="figure-label">Total rows</div>
            <div className="figure-value">{report.totalRows}</div>
          </div>
          <div>
            <div className="figure-label">Accepted</div>
            <div className="figure-value figure-accepted">{report.acceptedCount}</div>
          </div>
          <div>
            <div className="figure-label">Rejected</div>
            <div className={`figure-value${report.rejectedCount > 0 ? ' figure-rejected' : ''}`}>
              {report.rejectedCount}
            </div>
          </div>
        </div>
        <div
          className="stack-bar"
          role="img"
          aria-label={`${report.acceptedCount} accepted and ${report.rejectedCount} rejected of ${report.totalRows} rows`}
        >
          {acceptedPct > 0 && <span className="stack-accepted" style={{ width: `${acceptedPct}%` }} />}
          {rejectedPct > 0 && <span className="stack-rejected" style={{ width: `${rejectedPct}%` }} />}
        </div>
        {report.status === 'PUBLISH_FAILED' && (
          <p className="field-error" style={{ marginTop: 12 }}>
            The rows were checked but Kafka did not confirm delivery, so nothing changed. Upload the file again once
            Kafka is back.
          </p>
        )}
      </div>

      {report.rejected.length > 0 ? (
        <div className="table-scroll">
          <table className="table compact-table rejected-table">
            <colgroup>
              <col style={{ width: 96 }} />
              {showSheet && <col style={{ width: 140 }} />}
              <col style={{ width: '34%' }} />
              <col />
            </colgroup>
            <thead>
              <tr>
                <th scope="col">Excel row</th>
                {showSheet && <th scope="col">Sheet</th>}
                <th scope="col">Value in the file</th>
                <th scope="col">Why it was rejected</th>
              </tr>
            </thead>
            <tbody>
              {report.rejected.map((row, i) => (
                <tr key={`${row.sheet ?? ''}-${row.row}-${i}`}>
                  <td className="cell-id">{row.row}</td>
                  {showSheet && <td>{row.sheet ?? ''}</td>}
                  <td className="cell-strong cell-truncate mono" title={row.value ?? ''}>
                    {row.value === null || row.value === '' ? <span className="value-empty">(empty)</span> : row.value}
                  </td>
                  <td>{reasonText(row.reason)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        <p className="all-accepted">Every row was accepted.</p>
      )}
    </>
  );

  return embedded ? (
    <div className="report-embedded" aria-labelledby={headingId} role="region" aria-live="polite">
      {body}
    </div>
  ) : (
    <section className="card" aria-labelledby={headingId} aria-live="polite">
      {body}
    </section>
  );
}
