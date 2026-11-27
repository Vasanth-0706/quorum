import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from 'react';
import { listDrives } from './api';

export type Health = 'checking' | 'ok' | 'down';

interface HealthContextValue {
  placement: Health;
  /** Pages that load drives report the outcome so the sidebar stays in step. */
  reportPlacement: (ok: boolean) => void;
}

const HealthContext = createContext<HealthContextValue>({
  placement: 'checking',
  reportPlacement: () => {},
});

const RECHECK_MS = 30_000;

export function HealthProvider({ children }: { children: ReactNode }) {
  const [placement, setPlacement] = useState<Health>('checking');
  const reportPlacement = useCallback((ok: boolean) => setPlacement(ok ? 'ok' : 'down'), []);

  useEffect(() => {
    let cancelled = false;
    const check = () => {
      listDrives()
        .then(() => !cancelled && setPlacement('ok'))
        .catch(() => !cancelled && setPlacement('down'));
    };
    check();
    const timer = window.setInterval(check, RECHECK_MS);
    return () => {
      cancelled = true;
      window.clearInterval(timer);
    };
  }, []);

  return <HealthContext.Provider value={{ placement, reportPlacement }}>{children}</HealthContext.Provider>;
}

export function useHealth(): HealthContextValue {
  return useContext(HealthContext);
}
