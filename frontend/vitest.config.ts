import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

// Kept separate from vite.config.ts: that file configures a dev server and a proxy, neither
// of which a test run has any use for.
export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
    // Without this Vitest collects every *.spec.ts as well, including the Playwright
    // journeys under e2e/, and fails on Playwright's own test().
    include: ['src/**/*.test.{ts,tsx}'],
    coverage: {
      provider: 'v8',
      reporter: ['text-summary', 'lcov'],
      include: ['src/lib/**', 'src/app/uiSlice.ts', 'src/features/auth/authSlice.ts'],
    },
  },
})
