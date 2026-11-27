import { Navigate, Route, Routes } from 'react-router-dom';
import { Sidebar } from './components/Sidebar';
import { HealthProvider } from './health';
import { ClashesPage } from './pages/ClashesPage';
import { CorrectionsPage } from './pages/CorrectionsPage';
import { CreateDrivePage } from './pages/CreateDrivePage';
import { DriveDetailPage } from './pages/DriveDetailPage';
import { DrivesPage } from './pages/DrivesPage';
import { ImportPage } from './pages/ImportPage';
import { OdPage } from './pages/OdPage';
import { SessionCheckinPage } from './pages/SessionCheckinPage';

export function App() {
  return (
    <HealthProvider>
      <div className="app">
        <Sidebar />
        <main className="main">
          <div className="container">
            <Routes>
              <Route path="/" element={<DrivesPage />} />
              <Route path="/drives" element={<DrivesPage />} />
              <Route path="/drives/new" element={<CreateDrivePage />} />
              <Route path="/drives/:id" element={<DriveDetailPage />} />
              <Route path="/imports" element={<ImportPage />} />
              <Route path="/sessions/:id" element={<SessionCheckinPage />} />
              <Route path="/clashes" element={<ClashesPage />} />
              <Route path="/od" element={<OdPage />} />
              <Route path="/corrections" element={<CorrectionsPage />} />
              <Route path="*" element={<Navigate to="/drives" replace />} />
            </Routes>
          </div>
        </main>
      </div>
    </HealthProvider>
  );
}
