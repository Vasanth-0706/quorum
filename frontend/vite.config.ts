import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Each backend service is proxied under /api/<name>; the prefix is stripped,
// so /api/checkin/devices reaches checkin-service at /devices.
const SERVICES: Record<string, number> = {
  placement: 8081,
  attendance: 8082,
  timetable: 8083,
  checkin: 8084,
  import: 8090,
};

const proxy = Object.fromEntries(
  Object.entries(SERVICES).map(([name, port]) => {
    const prefix = new RegExp(`^/api/${name}`);
    return [
      `/api/${name}`,
      { target: `http://localhost:${port}`, changeOrigin: true, rewrite: (path: string) => path.replace(prefix, '') },
    ];
  }),
);

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy,
  },
});
