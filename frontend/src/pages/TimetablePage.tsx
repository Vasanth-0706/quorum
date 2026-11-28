import { useState, type FormEvent } from 'react';
import { errorText, listSemesters, templateUrl, uploadTimetable, type ImportReport, type Semester } from '../api';
import { Banner } from '../components/Banner';
import { Button } from '../components/Button';
import { DropZone } from '../components/DropZone';
import { Field } from '../components/Field';
import { DownloadIcon } from '../components/Icons';
import { fileProblem, ImportReportView } from '../components/ImportReport';
import { EmptyState, ErrorState, SkeletonRows } from '../components/States';
import { StatusPill } from '../components/StatusPill';
import { formatInstant } from '../time';
import { useResource } from '../useResource';

interface Result {
  report: ImportReport;
  checkedAt: Date;
}

/**
 * The semester timetable is the only upload that is not tied to a drive. Shortlists are uploaded
 * on their round and attendance files on their session's attendance sheet.
 */
export function TimetablePage() {
  const [semester, setSemester] = useState('');
  const [touched, setTouched] = useState(false);
  const [file, setFile] = useState<File | null>(null);
  const [fileError, setFileError] = useState<string | null>(null);
  const [submitted, setSubmitted] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<Result | null>(null);
  const semesters = useResource<Semester[]>((signal) => listSemesters(signal), []);

  const code = semester.trim();
  const semesterError = !code ? 'Enter the semester, e.g. 2026-ODD' : code.length > 32 ? 'At most 32 characters' : null;

  function choose(chosen: File) {
    const problem = fileProblem(chosen, ['.xlsx']);
    setFile(problem ? null : chosen);
    setFileError(problem ? `${problem} Timetables must be .xlsx.` : null);
  }

  async function submit(event: FormEvent) {
    event.preventDefault();
    setSubmitted(true);
    setError(null);
    if (semesterError || !file || fileError) return;
    setUploading(true);
    try {
      const report = await uploadTimetable(code, file);
      setResult({ report, checkedAt: new Date() });
      setFile(null);
      setSubmitted(false);
      // timetable-service applies the upload from Kafka; look again shortly.
      window.setTimeout(semesters.reload, 1500);
    } catch (err) {
      setError(errorText(err));
    } finally {
      setUploading(false);
    }
  }

  return (
    <>
      <header className="page-header">
        <div>
          <h1 className="page-title">Timetable</h1>
          <p className="page-lede">
            Upload the semester timetable: its class slots and which students are in each section. The latest timetable
            applied becomes the active one and is used for every clash. Shortlists are uploaded on their round, attendance
            on its session.
          </p>
        </div>
      </header>

      {error && (
        <Banner tone="error" onDismiss={() => setError(null)}>
          {error}
        </Banner>
      )}

      <form className="card card-body import-form" onSubmit={submit} noValidate aria-label="Upload timetable">
        <div className="import-id">
          <Field id="timetable-semester" label="Semester" error={(touched || submitted) && semesterError ? semesterError : null}>
            <input
              className="input"
              type="text"
              placeholder="2026-ODD"
              value={semester}
              maxLength={32}
              autoComplete="off"
              onChange={(e) => {
                setSemester(e.target.value);
                setTouched(true);
              }}
              onBlur={() => setTouched(true)}
            />
          </Field>
          <a className="text-link" href={templateUrl('timetable')} download>
            <DownloadIcon size={16} />
            Download template
          </a>
        </div>
        <DropZone
          id="timetable-file"
          file={file}
          onFile={choose}
          accept=".xlsx"
          hint="Sheets slots and enrolments, .xlsx only"
          error={fileError ?? (submitted && !file ? 'Choose a file to upload' : null)}
          disabled={uploading}
        />
        <Button type="submit" className="import-submit" loading={uploading} loadingText="Checking rows">
          Upload and check
        </Button>
      </form>

      {result && (
        <ImportReportView
          report={result.report}
          checkedAt={result.checkedAt}
          targetLabel="Semester"
          showSheet
          headingId="timetable-report-title"
        />
      )}

      <section className="card" aria-labelledby="semesters-title">
        <div className="card-head">
          <div>
            <h2 className="card-title" id="semesters-title">
              Semesters in timetable-service
            </h2>
            <p className="card-head-sub">The active semester is the one applied last.</p>
          </div>
          <Button variant="secondary" small onClick={semesters.reload}>
            Refresh
          </Button>
        </div>
        {semesters.state.kind === 'error' ? (
          <ErrorState error={semesters.state.error} onRetry={semesters.reload} />
        ) : semesters.state.kind === 'ready' && semesters.state.data.length === 0 ? (
          <EmptyState title="No timetable uploaded yet">
            <p className="state-text">Upload the semester timetable above. Clashes need it.</p>
          </EmptyState>
        ) : (
          <div className="table-scroll">
            <table className="table compact-table">
              <colgroup>
                <col style={{ width: '24%' }} />
                <col style={{ width: 120 }} />
                <col />
                <col />
                <col style={{ width: 180 }} />
              </colgroup>
              <thead>
                <tr>
                  <th scope="col">Semester</th>
                  <th scope="col">Status</th>
                  <th scope="col">Class slots</th>
                  <th scope="col">Enrolments</th>
                  <th scope="col">Applied</th>
                </tr>
              </thead>
              <tbody>
                {semesters.state.kind === 'loading' ? (
                  <SkeletonRows columns={5} rows={2} />
                ) : (
                  semesters.state.data.map((s) => (
                    <tr key={s.semester}>
                      <td className="cell-strong">{s.semester}</td>
                      <td>
                        {s.active ? <StatusPill tone="success">Active</StatusPill> : <StatusPill tone="neutral">Inactive</StatusPill>}
                      </td>
                      <td>{s.slotCount}</td>
                      <td>{s.enrolmentCount}</td>
                      <td>{formatInstant(s.appliedAt)}</td>
                    </tr>
                  ))
                )}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </>
  );
}
