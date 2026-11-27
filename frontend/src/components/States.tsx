import type { ReactNode } from 'react';
import { ApiError, errorText, SERVICES } from '../api';
import { Button } from './Button';
import { AlertIcon } from './Icons';

/** A failed load: names the offline service and its port, or shows the service's message. */
export function ErrorState({ error, onRetry, notFound }: { error: unknown; onRetry?: () => void; notFound?: string }) {
  const offline = error instanceof ApiError && error.unreachable && error.service;
  const missing = error instanceof ApiError && error.status === 404 && notFound;
  return (
    <div className="state">
      <span className="state-icon state-icon-error">
        <AlertIcon size={20} />
      </span>
      <p className="state-title">
        {offline
          ? `Can't reach ${SERVICES[error.service!].label} on port ${SERVICES[error.service!].port}. Start it and refresh.`
          : missing
            ? notFound
            : errorText(error)}
      </p>
      {onRetry && !missing && (
        <Button variant="secondary" small onClick={onRetry}>
          Try again
        </Button>
      )}
    </div>
  );
}

export function EmptyState({ icon, title, children }: { icon?: ReactNode; title: string; children?: ReactNode }) {
  return (
    <div className="state">
      {icon && <span className="state-icon">{icon}</span>}
      <p className="state-title">{title}</p>
      {children}
    </div>
  );
}

/** Grey bars in place of table rows while the first load runs. */
export function SkeletonRows({ columns, rows = 3 }: { columns: number; rows?: number }) {
  return (
    <>
      {Array.from({ length: rows }, (_, r) => (
        <tr key={r} aria-hidden="true">
          {Array.from({ length: columns }, (_, c) => (
            <td key={c}>
              <span className="skeleton" style={{ width: c === 0 ? 56 : `${60 + ((r + c) % 3) * 12}%` }} />
            </td>
          ))}
        </tr>
      ))}
    </>
  );
}
