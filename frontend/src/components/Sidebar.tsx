import { Link, useLocation } from 'react-router-dom';
import type { ReactNode } from 'react';
import { SERVICES, type ServiceName } from '../api';
import quorumMark from '../assets/quorum-mark-white.svg';
import {
  CalendarIcon,
  ClashIcon,
  ClipboardCheckIcon,
  PencilIcon,
  PlusIcon,
  ReaderIcon,
  UploadIcon,
} from './Icons';
import { SignInPanel } from './SignInPanel';
import { useHealth } from '../health';

interface NavItem {
  to: string;
  label: string;
  icon: ReactNode;
  isActive: (path: string) => boolean;
}

const NAV: NavItem[] = [
  {
    to: '/drives',
    label: 'Drives',
    icon: <CalendarIcon />,
    isActive: (path) => path === '/' || path === '/drives' || /^\/drives\/\d+/.test(path) || path.startsWith('/sessions/'),
  },
  {
    to: '/drives/new',
    label: 'Create drive',
    icon: <PlusIcon />,
    isActive: (path) => path === '/drives/new',
  },
  {
    to: '/timetable',
    label: 'Timetable',
    icon: <UploadIcon />,
    isActive: (path) => path.startsWith('/timetable'),
  },
  {
    to: '/clashes',
    label: 'Clashes',
    icon: <ClashIcon />,
    isActive: (path) => path.startsWith('/clashes'),
  },
  {
    to: '/od',
    label: 'OD records',
    icon: <ClipboardCheckIcon />,
    isActive: (path) => path.startsWith('/od'),
  },
  {
    to: '/corrections',
    label: 'Corrections',
    icon: <PencilIcon />,
    isActive: (path) => path.startsWith('/corrections'),
  },
  {
    to: '/devices',
    label: 'Readers & cards',
    icon: <ReaderIcon />,
    isActive: (path) => path.startsWith('/devices'),
  },
];

export function Sidebar() {
  const { pathname } = useLocation();
  const { services } = useHealth();

  const names = Object.keys(services) as ServiceName[];
  const down = names.filter((s) => services[s] === 'down');
  const checking = names.some((s) => services[s] === 'checking');

  return (
    <aside className="sidebar">
      <Link to="/drives" className="brand" aria-label="Quorum, placement office, go to drives">
        <img className="brand-mark" src={quorumMark} alt="" width={32} height={32} />
        <span>
          <span className="brand-name">Quorum</span>
          <span className="brand-sub">Placement office</span>
        </span>
      </Link>

      <nav className="nav" aria-label="Main">
        {NAV.map((item) => (
          <Link
            key={item.to}
            to={item.to}
            className="nav-link"
            aria-current={item.isActive(pathname) ? 'page' : undefined}
          >
            {item.icon}
            <span>{item.label}</span>
          </Link>
        ))}
      </nav>

      <div className="sidebar-foot">
        <div className="service-status" role="status" aria-live="polite">
          {down.length > 0 ? (
            <>
              <div className="service-status-line">
                <span className="dot dot-down" />
                <span>
                  {down.length === 1 ? `${SERVICES[down[0]].label} offline` : `${down.length} services offline`}
                </span>
              </div>
              <ul className="service-status-list">
                {down.length === 1 ? (
                  <li>Start it on port {SERVICES[down[0]].port}</li>
                ) : (
                  down.map((s) => (
                    <li key={s}>
                      {SERVICES[s].label.replace('-service', '')} · {SERVICES[s].port}
                    </li>
                  ))
                )}
              </ul>
            </>
          ) : checking ? (
            <div className="service-status-line">
              <span className="dot" />
              <span>Checking services</span>
            </div>
          ) : (
            <>
              <div className="service-status-line">
                <span className="dot dot-ok" />
                <span>Services connected</span>
              </div>
              <div className="service-status-sub">Publishing to Kafka</div>
            </>
          )}
        </div>

        <SignInPanel />
      </div>
    </aside>
  );
}
