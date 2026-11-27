import type { ReactNode } from 'react';

export type BadgeTone = 'accent' | 'success' | 'warning' | 'error' | 'neutral';

/** A small square-cornered label, e.g. the source of a mark. */
export function Badge({ tone = 'neutral', children, title }: { tone?: BadgeTone; children: ReactNode; title?: string }) {
  return (
    <span className={`badge badge-${tone}`} title={title}>
      {children}
    </span>
  );
}

const SOURCE_TONE: Record<string, BadgeTone> = {
  RFID: 'accent',
  MANUAL: 'warning',
  FILE: 'neutral',
  CORRECTION: 'error',
};

const SOURCE_TITLE: Record<string, string> = {
  RFID: 'Card tapped on a reader',
  MANUAL: 'Marked by hand on the check-in page',
  FILE: 'From an uploaded attendance file',
  CORRECTION: 'Corrected with a reason',
};

export function SourceBadge({ source }: { source: string }) {
  return (
    <Badge tone={SOURCE_TONE[source] ?? 'neutral'} title={SOURCE_TITLE[source]}>
      {source}
    </Badge>
  );
}
