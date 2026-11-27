import type { ReactNode } from 'react';
import { AlertIcon, CheckIcon } from './Icons';

interface StatusPillProps {
  tone: 'success' | 'warning' | 'error' | 'neutral';
  children: ReactNode;
}

export function StatusPill({ tone, children }: StatusPillProps) {
  return (
    <span className={`pill pill-${tone}`}>
      {tone === 'success' ? <CheckIcon size={14} /> : tone === 'neutral' ? null : <AlertIcon size={14} />}
      {children}
    </span>
  );
}
