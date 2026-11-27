import { useCallback, useEffect, useRef, useState, type DependencyList } from 'react';
import { isAbort } from './api';

export type Resource<T> =
  | { kind: 'loading' }
  | { kind: 'error'; error: unknown }
  | { kind: 'ready'; data: T };

/**
 * Loads data when the dependencies change. `reload` fetches again but keeps
 * showing the current data until the answer arrives; `pollMs` repeats that.
 */
export function useResource<T>(
  fetcher: ((signal: AbortSignal) => Promise<T>) | null,
  deps: DependencyList,
  pollMs?: number,
): { state: Resource<T>; reload: () => void; refreshError: unknown; lastLoaded: Date | null } {
  const [state, setState] = useState<Resource<T>>({ kind: 'loading' });
  // An error while data is already shown (e.g. one failed poll) does not wipe the data.
  const [refreshError, setRefreshError] = useState<unknown>(null);
  const [lastLoaded, setLastLoaded] = useState<Date | null>(null);
  const [tick, setTick] = useState(0);
  const hasData = useRef(false);
  const fetcherRef = useRef(fetcher);
  fetcherRef.current = fetcher;

  // A change of dependencies starts over with a loading state.
  useEffect(() => {
    hasData.current = false;
    setState({ kind: 'loading' });
    setRefreshError(null);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps);

  useEffect(() => {
    const run = fetcherRef.current;
    if (!run) return;
    const controller = new AbortController();
    run(controller.signal)
      .then((data) => {
        hasData.current = true;
        setState({ kind: 'ready', data });
        setRefreshError(null);
        setLastLoaded(new Date());
      })
      .catch((error: unknown) => {
        if (controller.signal.aborted || isAbort(error)) return;
        if (hasData.current) setRefreshError(error);
        else setState({ kind: 'error', error });
      });
    return () => controller.abort();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...deps, tick]);

  useEffect(() => {
    if (!pollMs || !fetcherRef.current) return;
    const timer = window.setInterval(() => setTick((t) => t + 1), pollMs);
    return () => window.clearInterval(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [pollMs, ...deps]);

  const reload = useCallback(() => setTick((t) => t + 1), []);
  return { state, reload, refreshError, lastLoaded };
}
