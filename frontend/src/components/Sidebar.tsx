import { Link, useLocation } from 'react-router-dom';
import type { ReactNode } from 'react';
import { CalendarIcon, PlusIcon, QuorumMark, UploadIcon } from './Icons';
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
    isActive: (path) => path === '/' || path === '/drives',
  },
  {
    to: '/drives/new',
    label: 'Create drive',
    icon: <PlusIcon />,
    isActive: (path) => path === '/drives/new',
  },
  {
    to: '/imports',
    label: 'Excel import',
    icon: <UploadIcon />,
    isActive: (path) => path.startsWith('/imports'),
  },
];

export function Sidebar() {
  const { pathname } = useLocation();
  const { placement } = useHealth();

  return (
    <aside className="sidebar">
      <Link to="/drives" className="brand" aria-label="Quorum, placement office, go to drives">
        <span className="brand-mark">
          <QuorumMark size={18} />
        </span>
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

      <div className="service-status" role="status" aria-live="polite">
        {placement === 'down' ? (
          <>
            <div className="service-status-line">
              <span className="dot dot-down" />
              <span>placement-service offline</span>
            </div>
            <div className="service-status-sub">Start it on port 8081</div>
          </>
        ) : placement === 'ok' ? (
          <>
            <div className="service-status-line">
              <span className="dot dot-ok" />
              <span>Services connected</span>
            </div>
            <div className="service-status-sub">Drives publish to drive-events</div>
          </>
        ) : (
          <div className="service-status-line">
            <span className="dot" />
            <span>Checking services</span>
          </div>
        )}
      </div>
    </aside>
  );
}
