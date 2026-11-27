import type { ReactNode } from 'react';
import { AlertIcon, CircleCheckIcon, CloseIcon } from './Icons';

interface BannerProps {
  tone: 'success' | 'error';
  children: ReactNode;
  onDismiss?: () => void;
}

export function Banner({ tone, children, onDismiss }: BannerProps) {
  return (
    <div className={`banner banner-${tone}`} role={tone === 'error' ? 'alert' : 'status'}>
      {tone === 'success' ? <CircleCheckIcon /> : <AlertIcon />}
      <div className="banner-text">{children}</div>
      {onDismiss && (
        <button type="button" className="banner-dismiss" onClick={onDismiss} aria-label="Dismiss">
          <CloseIcon size={16} />
        </button>
      )}
    </div>
  );
}
