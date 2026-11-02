import { useRef, useState, type FormEvent, type KeyboardEvent } from 'react';
import { useSearchParams } from 'react-router-dom';
import { ApiError, templateUrl, uploadImport, type ImportReport, type ImportType } from '../api';
import { Banner } from '../components/Banner';
import { Button } from '../components/Button';
import { DropZone } from '../components/DropZone';
import { Field } from '../components/Field';
import { DownloadIcon } from '../components/Icons';
import { StatusPill } from '../components/StatusPill';
import { formatClock } from '../time';

interface TabConfig {
  type: ImportType;
  label: string;
  idLabel: string;
  idPlaceholder: string;
  /** Round and session IDs are whole numbers on the server. */
  numericId: boolean;
  accept: string;
  extensions: string[];
  hint: string;
  targetLabel: string;
}

const TABS: TabConfig[] = [
  {
    type: 'shortlist',
    label: 'Shortlist',
    idLabel: 'Round ID',
    idPlaceholder: '12',
    numericId: true,
    accept: '.xlsx,.csv',
    extensions: ['.xlsx', '.csv'],
    hint: 'One column of student IDs, .xlsx or .csv',
    targetLabel: 'Round',
  },
  {
    type: 'attendance',
    label: 'Attendance',
    idLabel: 'Session ID',
    idPlaceholder: '7',
    numericId: true,
    accept: '.xlsx,.csv',
    extensions: ['.xlsx', '.csv'],
    hint: 'IDs of students present, .xlsx or .csv',
    targetLabel: 'Session',
  },
  {
    type: 'timetable',
    label: 'Timetable',
    idLabel: 'Semester',
    idPlaceholder: '2026-ODD',
    numericId: false,
    accept: '.xlsx',
    extensions: ['.xlsx'],
    hint: 'Sheets slots and enrolments, .xlsx only',
    targetLabel: 'Semester',
  },
];

const MAX_BYTES = 5 * 1024 * 1024;

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

function reasonText(code: string): string {
  if (REASONS[code]) return REASONS[code];
  const words = code.toLowerCase().replace(/_/g, ' ');
  return words.charAt(0).toUpperCase() + words.slice(1);
}

function isTabType(value: string | null): value is ImportType {
  return TABS.some((t) => t.type === value);
}

export function ImportPage() {
  const [params, setParams] = useSearchParams();
  const requested = params.get('type');
  const active: ImportType = isTabType(requested) ? requested : 'shortlist';
  const tabRefs = useRef<Record<string, HTMLButtonElement | null>>({});

  function select(type: ImportType, focus = false) {
    setParams(type === 'shortlist' ? {} : { type }, { replace: true });
    if (focus) tabRefs.current[type]?.focus();
  }

  function handleTabKey(event: KeyboardEvent<HTMLButtonElement>) {
    const index = TABS.findIndex((t) => t.type === active);
    let next: number | null = null;
    if (event.key === 'ArrowRight') next = (index + 1) % TABS.length;
    if (event.key === 'ArrowLeft') next = (index - 1 + TABS.length) % TABS.length;
    if (event.key === 'Home') next = 0;
    if (event.key === 'End') next = TABS.length - 1;
    if (next !== null) {
      event.preventDefault();
      select(TABS[next].type, true);
    }
  }

  return (
    <>
      <header className="page-header">
        <div>
          <h1 className="page-title">Excel import</h1>
          <p className="page-lede">
            Upload a shortlist, attendance sheet or semester timetable. Every row is checked before anything is
            published.
          </p>
        </div>
      </header>

      <div className="tabs" role="tablist" aria-label="Import type">
        {TABS.map((tab) => (
          <button
            key={tab.type}
            ref={(el) => {
              tabRefs.current[tab.type] = el;
            }}
            type="button"
            role="tab"
            id={`tab-${tab.type}`}
            className="tab"
            aria-selected={active === tab.type}
            aria-controls={`panel-${tab.type}`}
            tabIndex={active === tab.type ? 0 : -1}
            onClick={() => select(tab.type)}
            onKeyDown={handleTabKey}
          >
            {tab.label}
          </button>
        ))}
      </div>

      {/* Every panel stays mounted so a chosen file and its report survive tab switches. */}
      {TABS.map((tab) => (
        <div
          key={tab.type}
          role="tabpanel"
          id={`panel-${tab.type}`}
          aria-labelledby={`tab-${tab.type}`}
          hidden={active !== tab.type}
        >
          <ImportPanel config={tab} />
        </div>
      ))}
    </>
  );
}

interface Result {
  report: ImportReport;
  checkedAt: Date;
}

function ImportPanel({ config }: { config: TabConfig }) {
  const [targetId, setTargetId] = useState('');
  const [idTouched, setIdTouched] = useState(false);
  const [file, setFile] = useState<File | null>(null);
  const [fileError, setFileError] = useState<string | null>(null);
  const [submitted, setSubmitted] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<Result | null>(null);

  const idInputId = `${config.type}-target`;
  const trimmedId = targetId.trim();
  const idError = !trimmedId
    ? `Enter the ${config.idLabel.toLowerCase()}`
    : config.numericId && !/^\d+$/.test(trimmedId)
      ? `${config.idLabel} must be a whole number`
      : null;
  const showIdError = (idTouched || submitted) && idError;
  const missingFile = submitted && !file ? 'Choose a file to upload' : null;

  function chooseFile(chosen: File) {
    const name = chosen.name.toLowerCase();
    if (!config.extensions.some((ext) => name.endsWith(ext))) {
      setFile(null);
      setFileError(
        config.extensions.length === 1
          ? `${chosen.name} is not an .xlsx file. Timetables must be .xlsx.`
          : `${chosen.name} is not an .xlsx or .csv file.`,
      );
      return;
    }
    if (chosen.size > MAX_BYTES) {
      setFile(null);
      setFileError(`${chosen.name} is larger than 5 MB.`);
      return;
    }
    setFileError(null);
    setFile(chosen);
  }

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setSubmitted(true);
    setError(null);
    if (idError || !file || fileError) return;

    setUploading(true);
    try {
      const report = await uploadImport(config.type, file, trimmedId);
      setResult({ report, checkedAt: new Date() });
    } catch (err) {
      if (err instanceof ApiError && err.unreachable) {
        setError("Can't reach import-service on port 8090. Start it and try again.");
      } else {
        setError(err instanceof Error ? err.message : 'The upload failed.');
      }
    } finally {
      setUploading(false);
    }
  }

  return (
    <>
      {error && (
        <Banner tone="error" onDismiss={() => setError(null)}>
          {error}
        </Banner>
      )}

      <form className="card card-body import-form" onSubmit={handleSubmit} noValidate>
        <div className="import-id">
          <Field id={idInputId} label={config.idLabel} error={showIdError || null}>
            <input
              className="input"
              type="text"
              inputMode={config.numericId ? 'numeric' : 'text'}
              placeholder={config.idPlaceholder}
              value={targetId}
              onChange={(e) => {
                setTargetId(e.target.value);
                setIdTouched(true);
              }}
              onBlur={() => setIdTouched(true)}
              autoComplete="off"
            />
          </Field>
          <a className="text-link" href={templateUrl(config.type)} download>
            <DownloadIcon size={16} />
            Download template
          </a>
        </div>

        <DropZone
          id={`${config.type}-file`}
          file={file}
          onFile={chooseFile}
          accept={config.accept}
          hint={config.hint}
          error={fileError ?? missingFile}
          disabled={uploading}
        />

        <Button type="submit" className="import-submit" loading={uploading} loadingText="Checking rows">
          Upload and check
        </Button>
      </form>

      {result && <ReportCard result={result} config={config} />}
    </>
  );
}

function ReportCard({ result, config }: { result: Result; config: TabConfig }) {
  const { report, checkedAt } = result;
  const isTimetable = config.type === 'timetable';
  const total = Math.max(report.totalRows, report.acceptedCount + report.rejectedCount);
  const acceptedPct = total > 0 ? (report.acceptedCount / total) * 100 : 0;
  const rejectedPct = total > 0 ? (report.rejectedCount / total) * 100 : 0;

  return (
    <section className="card" aria-labelledby={`${config.type}-report-title`} aria-live="polite">
      <div className="result-head">
        <div>
          <h2 className="result-file" id={`${config.type}-report-title`}>
            {report.fileName}
          </h2>
          <p className="result-sub">
            {config.targetLabel} {report.targetId}, checked at {formatClock(checkedAt)}
          </p>
        </div>
        <ReportPill status={report.status} />
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
      </div>

      {report.rejected.length > 0 ? (
        <div className="table-scroll">
          <table className="table rejected-table">
            <colgroup>
              <col style={{ width: 96 }} />
              {isTimetable && <col style={{ width: 140 }} />}
              <col style={{ width: '34%' }} />
              <col />
            </colgroup>
            <thead>
              <tr>
                <th scope="col">Row</th>
                {isTimetable && <th scope="col">Sheet</th>}
                <th scope="col">Value in the file</th>
                <th scope="col">Why it was rejected</th>
              </tr>
            </thead>
            <tbody>
              {report.rejected.map((row, i) => (
                <tr key={`${row.sheet ?? ''}-${row.row}-${i}`}>
                  <td className="cell-id">{row.row}</td>
                  {isTimetable && <td>{row.sheet ?? ''}</td>}
                  <td className="cell-strong cell-truncate" title={row.value ?? ''}>
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
    </section>
  );
}

function ReportPill({ status }: { status: ImportReport['status'] }) {
  if (status === 'PUBLISHED') return <StatusPill tone="success">Published</StatusPill>;
  if (status === 'NOTHING_TO_PUBLISH') return <StatusPill tone="warning">Nothing to publish</StatusPill>;
  return <StatusPill tone="error">Kafka unavailable, not published</StatusPill>;
}
