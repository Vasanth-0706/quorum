import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from 'react';
import { onServiceHealth, ping, SERVICES, type ServiceName } from './api';

export type Health = 'checking' | 'ok' | 'down';

type HealthMap = Record<ServiceName, Health>;

interface HealthContextValue {
  services: HealthMap;
  /** Kept for the drive pages; every request already reports its outcome. */
  placement: Health;
  reportPlacement: (ok: boolean) => void;
}

const SERVICE_NAMES = Object.keys(SERVICES) as ServiceName[];
const INITIAL = Object.fromEntries(SERVICE_NAMES.map((s) => [s, 'checking'])) as HealthMap;

const HealthContext = createContext<HealthContextValue>({
  services: INITIAL,
  placement: 'checking',
  reportPlacement: () => {},
});

const RECHECK_MS = 30_000;

export function HealthProvider({ children }: { children: ReactNode }) {
  const [services, setServices] = useState<HealthMap>(INITIAL);

  const set = useCallback((service: ServiceName, ok: boolean) => {
    setServices((prev) => {
      const next: Health = ok ? 'ok' : 'down';
      return prev[service] === next ? prev : { ...prev, [service]: next };
    });
  }, []);

  const reportPlacement = useCallback((ok: boolean) => set('placement', ok), [set]);

  // Every request in api.ts reports whether its service answered.
  useEffect(() => onServiceHealth(set), [set]);

  useEffect(() => {
    const controller = new AbortController();
    const check = () => {
      SERVICE_NAMES.forEach((service) => {
        ping(service, controller.signal)
          .then((ok) => set(service, ok))
          .catch(() => {});
      });
    };
    check();
    const timer = window.setInterval(check, RECHECK_MS);
    return () => {
      controller.abort();
      window.clearInterval(timer);
    };
  }, [set]);

  return (
    <HealthContext.Provider value={{ services, placement: services.placement, reportPlacement }}>
      {children}
    </HealthContext.Provider>
  );
}

export function useHealth(): HealthContextValue {
  return useContext(HealthContext);
}
