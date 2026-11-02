import { useRef, useState, type DragEvent } from 'react';
import { Button } from './Button';
import { FileCheckIcon, UploadIcon } from './Icons';
import { formatFileSize } from '../time';

interface DropZoneProps {
  id: string;
  file: File | null;
  onFile: (file: File) => void;
  /** e.g. ".xlsx,.csv" */
  accept: string;
  /** e.g. ".xlsx or .csv, up to 5 MB" */
  hint: string;
  error?: string | null;
  disabled?: boolean;
}

export function DropZone({ id, file, onFile, accept, hint, error, disabled }: DropZoneProps) {
  const inputRef = useRef<HTMLInputElement>(null);
  const [dragging, setDragging] = useState(false);
  const labelId = `${id}-label`;
  const errorId = `${id}-error`;

  function handleDrop(event: DragEvent<HTMLDivElement>) {
    event.preventDefault();
    setDragging(false);
    if (disabled) return;
    const dropped = event.dataTransfer.files[0];
    if (dropped) onFile(dropped);
  }

  function handleDragOver(event: DragEvent<HTMLDivElement>) {
    event.preventDefault();
    if (!disabled) setDragging(true);
  }

  function handleDragLeave(event: DragEvent<HTMLDivElement>) {
    // Only reset when the pointer leaves the zone itself, not a child.
    if (!event.currentTarget.contains(event.relatedTarget as Node | null)) setDragging(false);
  }

  const className = ['dropzone', dragging && 'dropzone-dragging', error && 'dropzone-invalid'].filter(Boolean).join(' ');

  return (
    <div className="field">
      <span className="field-label" id={labelId}>
        File
      </span>
      <div
        className={className}
        role="group"
        aria-labelledby={labelId}
        aria-describedby={error ? errorId : undefined}
        onDragOver={handleDragOver}
        onDragEnter={handleDragOver}
        onDragLeave={handleDragLeave}
        onDrop={handleDrop}
      >
        <span className={`dropzone-icon${file ? ' dropzone-icon-file' : ''}`}>
          {file ? <FileCheckIcon /> : <UploadIcon />}
        </span>
        <div className="dropzone-text">
          {file ? (
            <>
              <div className="dropzone-title" title={file.name}>
                {file.name}
              </div>
              <div className="dropzone-sub">{formatFileSize(file.size)}, ready to check</div>
            </>
          ) : (
            <>
              <div className="dropzone-title">{dragging ? 'Drop to add the file' : 'Drag the file here'}</div>
              <div className="dropzone-sub">{hint}</div>
            </>
          )}
        </div>
        <Button variant="secondary" small onClick={() => inputRef.current?.click()} disabled={disabled}>
          {file ? 'Change file' : 'Choose file'}
        </Button>
        <input
          ref={inputRef}
          id={id}
          type="file"
          accept={accept}
          className="visually-hidden"
          tabIndex={-1}
          aria-hidden="true"
          onChange={(event) => {
            const chosen = event.target.files?.[0];
            if (chosen) onFile(chosen);
            event.target.value = '';
          }}
        />
      </div>
      {error && (
        <p className="field-error" id={errorId}>
          {error}
        </p>
      )}
    </div>
  );
}
