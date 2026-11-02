import type { ReactNode } from 'react';
import { AlertIcon, CheckIcon } from './Icons';

interface StatusPillProps {
  tone: 'success' | 'warning' | 'error';
  children: ReactNode;
}

export function StatusPill({ tone, children }: StatusPillProps) {
  return (
    <span className={`pill pill-${tone}`}>
      {tone === 'success' ? <CheckIcon size={14} /> : <AlertIcon size={14} />}
      {children}
    </span>
  );
}
