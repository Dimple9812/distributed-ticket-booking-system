import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// In development, API calls are proxied to the Spring Boot app on :8080.
// In production the built files are served by Spring Boot itself (same origin, no CORS).
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: { '/api': 'http://localhost:8080', '/actuator': 'http://localhost:8080' },
  },
  build: { outDir: 'dist', emptyOutDir: true },
});
