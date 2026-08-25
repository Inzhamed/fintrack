import { defineConfig, devices } from '@playwright/test'

/**
 * E2E against a running stack.
 *
 * These tests assume the API and its dependencies are already up - `docker compose up -d db
 * redis storage` plus the backend. Playwright starts only the frontend, because starting the
 * whole backend per run would make the suite far too slow to use while developing.
 */
export default defineConfig({
  testDir: './e2e',
  // A shared database means parallel workers would delete each other's fixtures mid-test.
  // The suite is small; correctness is worth more here than wall-clock time.
  workers: 1,
  fullyParallel: false,
  // Fail the run if a test.only is committed by accident.
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [['github'], ['html', { open: 'never' }]] : [['list']],

  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:5173',
    // Kept only for failures: a trace per passing test is a lot of disk for no information.
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'off',
  },

  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],

  webServer: {
    command: 'npm run dev',
    url: 'http://localhost:5173',
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
  },
})
