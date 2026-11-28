import { useEffect, useRef, useState, type FormEvent } from 'react';
import { ApiError, errorText, templateUrl, type ImportReport, type ImportType } from '../api';
import { Banner } from './Banner';
import { Button } from './Button';
import { DropZone } from './DropZone';
import { DownloadIcon } from './Icons';
import { fileProblem } from './ImportReport';

interface InlineUploadProps {
  id: string;
  type: Exclude<ImportType, 'timetable'>;
  title: string;
  /** e.g. "Round OA (#4)": where the file goes, so nobody types an id. */
  targetText: string;
  hint: string;
  upload: (file: File) => Promise<ImportReport>;
  onReport: (report: ImportReport) => void;
  onClose: () => void;
}

/** A small upload form inside a card: the target is fixed by the page. */
export function InlineUpload({ id, type, title, targetText, hint, upload, onReport, onClose }: InlineUploadProps) {
  const [file, setFile] = useState<File | null>(null);
  const [fileError, setFileError] = useState<string | null>(null);
  const [submitted, setSubmitted] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const formRef = useRef<HTMLFormElement>(null);

  useEffect(() => {
    formRef.current?.querySelector<HTMLButtonElement>('.dropzone button')?.focus();
  }, []);

  function choose(chosen: File) {
    const problem = fileProblem(chosen, ['.xlsx', '.csv']);
    setFile(problem ? null : chosen);
    setFileError(problem);
  }

  async function submit(event: FormEvent) {
    event.preventDefault();
    setSubmitted(true);
    setError(null);
    if (!file || fileError) return;
    setUploading(true);
    try {
      const report = await upload(file);
      onReport(report);
      setFile(null);
      setSubmitted(false);
    } catch (err) {
      setError(err instanceof ApiError && err.status === 400 ? `The file was refused: ${err.message}` : errorText(err));
    } finally {
      setUploading(false);
    }
  }

  return (
    <form ref={formRef} className="panel inline-upload" onSubmit={submit} noValidate aria-label={title}>
      <div className="inline-upload-head">
        <div>
          <p className="panel-title">{title}</p>
          <p className="field-helper">
            Goes to <strong>{targetText}</strong>. {hint}
          </p>
        </div>
        <a className="text-link" href={templateUrl(type)} download>
          <DownloadIcon size={16} />
          Download template
        </a>
      </div>
      {error && (
        <Banner tone="error" onDismiss={() => setError(null)}>
          {error}
        </Banner>
      )}
      <DropZone
        id={id}
        file={file}
        onFile={choose}
        accept=".xlsx,.csv"
        hint=".xlsx or .csv, up to 5 MB"
        error={fileError ?? (submitted && !file ? 'Choose a file to upload' : null)}
        disabled={uploading}
      />
      <div className="panel-actions">
        <Button variant="secondary" onClick={onClose} disabled={uploading}>
          Close
        </Button>
        <Button type="submit" loading={uploading} loadingText="Checking rows">
          Upload and check
        </Button>
      </div>
    </form>
  );
}
