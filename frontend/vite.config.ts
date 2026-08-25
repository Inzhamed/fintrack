import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    port: 5173,
    // Proxying /api in dev keeps the browser on a single origin, so no CORS preflight
    // and no absolute URLs baked into the client. Production serves both behind nginx.
    proxy: {
      '/api': {
        target: process.env.VITE_API_TARGET ?? 'http://localhost:8080',
        changeOrigin: true,
      },
      '/actuator': {
        target: process.env.VITE_API_TARGET ?? 'http://localhost:8080',
        changeOrigin: true,
      },
      // ws: true, or the dev server answers the upgrade itself instead of forwarding it.
      '/ws': {
        target: process.env.VITE_API_TARGET ?? 'http://localhost:8080',
        ws: true,
        changeOrigin: true,
      },
    },
  },
})
