import { defineConfig, devices } from '@playwright/test'

// Runs against PRODUCTION builds (only they carry the service worker and the CSP), served the way
// makensi.es serves them: /jdcm/ (beta) with /jdcm/alpha/ nested inside it. iPhone (WebKit) first:
// the families this is for use Safari. PW_WEBKIT_EXECUTABLE points at a WebKit wrapper on hosts
// whose system libraries Playwright's WebKit lacks (no root to install them).
const PORT = 4318
const webkitExe = process.env.PW_WEBKIT_EXECUTABLE

export default defineConfig({
  testDir: './e2e',
  timeout: 60_000,
  expect: { timeout: 10_000 },
  workers: 1,
  fullyParallel: false,
  retries: 0,
  reporter: [['list']],
  use: {
    baseURL: `http://localhost:${PORT}`,
    trace: 'retain-on-failure',
    serviceWorkers: 'allow',
    // Families in Madrid: Spanish phones on Madrid time (the app must not depend on the latter).
    locale: 'es-ES',
    timezoneId: 'Europe/Madrid',
  },
  projects: [
    {
      name: 'iphone-webkit',
      use: { ...devices['iPhone 15'], locale: 'es-ES', launchOptions: webkitExe ? { executablePath: webkitExe } : {} },
    },
    {
      name: 'android-chromium',
      use: { ...devices['Pixel 7'], locale: 'es-ES' },
    },
  ],
  webServer: {
    command: 'node e2e/builds.mjs && node e2e/server.mjs',
    url: `http://localhost:${PORT}/jdcm/`,
    reuseExistingServer: false,
    timeout: 300_000,
    stdout: 'ignore',
    stderr: 'pipe',
  },
})
