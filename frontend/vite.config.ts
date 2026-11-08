import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// In development, Vite forwards /api to Spring Boot so the browser sees a single origin (no CORS setup needed).
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
});
