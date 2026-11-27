import { cloneElement, type ReactElement } from 'react';

interface FieldProps {
  id: string;
  label: string;
  error?: string | null;
  helper?: string;
  /** A single input; it receives aria-invalid and aria-describedby. */
  children: ReactElement<{ id?: string; 'aria-invalid'?: boolean; 'aria-describedby'?: string }>;
}

export function Field({ id, label, error, helper, children }: FieldProps) {
  const errorId = `${id}-error`;
  const helperId = `${id}-helper`;
  const describedBy = [error ? errorId : null, helper ? helperId : null].filter(Boolean).join(' ') || undefined;

  return (
    <div className="field">
      <label className="field-label" htmlFor={id}>
        {label}
      </label>
      {cloneElement(children, {
        id,
        'aria-invalid': error ? true : undefined,
        'aria-describedby': describedBy,
      })}
      {error && (
        <p className="field-error" id={errorId}>
          {error}
        </p>
      )}
      {helper && !error && (
        <p className="field-helper" id={helperId}>
          {helper}
        </p>
      )}
    </div>
  );
}
