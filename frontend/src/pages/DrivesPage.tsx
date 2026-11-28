import { useCallback, useEffect, useMemo, useState } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { listDrives, type Drive } from '../api';
import { Banner } from '../components/Banner';
import { Button, buttonClass } from '../components/Button';
import { DayTrack } from '../components/DayTrack';
import { AlertIcon, CalendarIcon, PlusIcon, SearchIcon } from '../components/Icons';
import { StatusPill } from '../components/StatusPill';
import { useHealth } from '../health';
import { DAY_RANGE_TEXT, formatDriveDate, toMinutes, todayIso } from '../time';

type Filter = 'upcoming' | 'past' | 'all';

const FILTERS: { value: Filter; label: string }[] = [
  { value: 'upcoming', label: 'Upcoming' },
  { value: 'past', label: 'Past' },
  { value: 'all', label: 'All' },
];

const HIGHLIGHT_MS = 2400;

type LoadState = { kind: 'loading' } | { kind: 'error' } | { kind: 'ready'; drives: Drive[] };

interface CreatedState {
  created?: Drive;
}

export function DrivesPage() {
  const location = useLocation();
  const navigate = useNavigate();
  const { reportPlacement } = useHealth();

  // A drive just created on the Create page arrives in the router state.
  const [created, setCreated] = useState<Drive | null>(() => (location.state as CreatedState | null)?.created ?? null);
  const [highlightId, setHighlightId] = useState<number | null>(created?.id ?? null);
  const [filter, setFilter] = useState<Filter>(() =>
    created && created.driveDate < todayIso() ? 'all' : 'upcoming',
  );
  const [query, setQuery] = useState('');
  const [load, setLoad] = useState<LoadState>({ kind: 'loading' });

  // Clear the router state so a refresh does not show the banner again.
  useEffect(() => {
    if ((location.state as CreatedState | null)?.created) {
      navigate(location.pathname, { replace: true, state: null });
    }
  }, [location, navigate]);

  useEffect(() => {
    if (highlightId === null) return;
    const timer = window.setTimeout(() => setHighlightId(null), HIGHLIGHT_MS);
    return () => window.clearTimeout(timer);
  }, [highlightId]);

  const fetchDrives = useCallback(
    (signal?: AbortSignal) => {
      setLoad({ kind: 'loading' });
      listDrives(signal)
        .then((drives) => {
          setLoad({ kind: 'ready', drives });
          reportPlacement(true);
        })
        .catch((error: unknown) => {
          if (signal?.aborted) return;
          if (error instanceof DOMException && error.name === 'AbortError') return;
          setLoad({ kind: 'error' });
          reportPlacement(false);
        });
    },
    [reportPlacement],
  );

  useEffect(() => {
    const controller = new AbortController();
    fetchDrives(controller.signal);
    return () => controller.abort();
  }, [fetchDrives]);

  const today = todayIso();
  const drives = load.kind === 'ready' ? load.drives : [];

  const visible = useMemo(() => {
    const q = query.trim().toLowerCase();
    return drives.filter((d) => {
      if (filter === 'upcoming' && d.driveDate < today) return false;
      if (filter === 'past' && d.driveDate >= today) return false;
      if (!q) return true;
      return d.companyName.toLowerCase().includes(q) || d.venue.toLowerCase().includes(q);
    });
  }, [drives, filter, query, today]);

  const countLabel =
    filter === 'upcoming'
      ? `${visible.length} upcoming ${visible.length === 1 ? 'drive' : 'drives'}`
      : filter === 'past'
        ? `${visible.length} past ${visible.length === 1 ? 'drive' : 'drives'}`
        : `${visible.length} ${visible.length === 1 ? 'drive' : 'drives'}`;

  return (
    <>
      <header className="page-header">
        <div>
          <h1 className="page-title">Drives</h1>
          <p className="page-lede">
            Each drive you create is published as an event. Attendance picks it up even if it was offline at the time.
          </p>
        </div>
        <Link to="/drives/new" className={buttonClass('primary')}>
          <PlusIcon />
          Create drive
        </Link>
      </header>

      {created && (
        <Banner tone="success" onDismiss={() => setCreated(null)}>
          Drive #{created.id} created and published to Kafka
        </Banner>
      )}

      <section className="card" aria-label="Drives">
        <div className="toolbar">
          <div className="segmented" role="group" aria-label="Show drives">
            {FILTERS.map((f) => (
              <button key={f.value} type="button" aria-pressed={filter === f.value} onClick={() => setFilter(f.value)}>
                {f.label}
              </button>
            ))}
          </div>
          <div className="search">
            <SearchIcon size={16} />
            <label htmlFor="drive-search" className="visually-hidden">
              Search company or venue
            </label>
            <input
              id="drive-search"
              className="input"
              type="search"
              placeholder="Search company or venue"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              autoComplete="off"
            />
          </div>
        </div>

        {load.kind === 'error' ? (
          <div className="state">
            <span className="state-icon state-icon-error">
              <AlertIcon size={20} />
            </span>
            <p className="state-title">Can't reach placement-service on port 8081. Start it and refresh.</p>
            <Button variant="secondary" small onClick={() => fetchDrives()}>
              Try again
            </Button>
          </div>
        ) : load.kind === 'ready' && drives.length === 0 ? (
          <div className="state">
            <span className="state-icon">
              <CalendarIcon size={20} />
            </span>
            <p className="state-title">No drives yet</p>
            <p className="state-text">Create the first drive and it will be published to the attendance service.</p>
            <Link to="/drives/new" className={buttonClass('primary')}>
              <PlusIcon />
              Create drive
            </Link>
          </div>
        ) : (
          <>
            <div className="table-scroll">
              <table className="table drives-table">
                <colgroup>
                  <col style={{ width: 72 }} />
                  <col style={{ width: '15%' }} />
                  <col style={{ width: 120 }} />
                  <col style={{ width: '29%' }} />
                  <col />
                  <col style={{ width: 136 }} />
                </colgroup>
                <thead>
                  <tr>
                    <th scope="col">ID</th>
                    <th scope="col">Company</th>
                    <th scope="col">Date</th>
                    <th scope="col">Time in the college day</th>
                    <th scope="col">Venue</th>
                    <th scope="col">Event</th>
                  </tr>
                </thead>
                <tbody>
                  {load.kind === 'loading'
                    ? [0, 1, 2, 3].map((i) => <SkeletonRow key={i} />)
                    : visible.map((drive) => (
                        <tr
                          key={drive.id}
                          className={drive.id === highlightId ? 'row-new row-link' : 'row-link'}
                          onClick={(e) => {
                            // The company link handles its own click; a click anywhere else on the row opens it too.
                            if ((e.target as HTMLElement).closest('a')) return;
                            navigate(`/drives/${drive.id}`);
                          }}
                        >
                          <td className="cell-id">#{drive.id}</td>
                          <td className="cell-strong cell-truncate" title={drive.companyName}>
                            <Link to={`/drives/${drive.id}`} className="cell-link">
                              {drive.companyName}
                            </Link>
                          </td>
                          <td>{formatDriveDate(drive.driveDate)}</td>
                          <td>
                            <DayTrack start={toMinutes(drive.startTime)} end={toMinutes(drive.endTime)} showMeta />
                          </td>
                          <td className="cell-truncate" title={drive.venue}>
                            {drive.venue}
                          </td>
                          <td>
                            <StatusPill tone="success">Published</StatusPill>
                          </td>
                        </tr>
                      ))}
                </tbody>
              </table>
            </div>
            {load.kind === 'ready' && visible.length === 0 && (
              <div className="state">
                <p className="state-title">
                  {query.trim() ? `No drives match "${query.trim()}"` : `No ${filter} drives`}
                </p>
                <Button variant="secondary" small onClick={() => (query.trim() ? setQuery('') : setFilter('all'))}>
                  {query.trim() ? 'Clear search' : 'Show all drives'}
                </Button>
              </div>
            )}
          </>
        )}

        <div className="card-footer">
          <span>Bar shows the drive against the college day, {DAY_RANGE_TEXT}</span>
          {load.kind === 'ready' && <span>{countLabel}</span>}
        </div>
      </section>
    </>
  );
}

function SkeletonRow() {
  return (
    <tr aria-hidden="true">
      <td>
        <span className="skeleton" style={{ width: 24 }} />
      </td>
      <td>
        <span className="skeleton" style={{ width: '70%' }} />
      </td>
      <td>
        <span className="skeleton" style={{ width: 72 }} />
      </td>
      <td>
        <span className="skeleton" style={{ height: 10, borderRadius: 999 }} />
        <span className="skeleton" style={{ width: 88, marginTop: 8 }} />
      </td>
      <td>
        <span className="skeleton" style={{ width: '60%' }} />
      </td>
      <td>
        <span className="skeleton" style={{ width: 84, height: 24, borderRadius: 999 }} />
      </td>
    </tr>
  );
}
