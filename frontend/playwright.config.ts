import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: './tests',
  fullyParallel: false,
  workers: 1,
  timeout: 60_000,
  expect: { timeout: 10_000 },
  use: {
    baseURL: process.env.RECON_BASE_URL || 'http://127.0.0.1:8096',
    channel: process.env.RECON_BROWSER_CHANNEL || (process.env.CI ? 'chromium' : 'msedge'),
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  reporter: [['list'], ['html', { open: 'never' }]],
});
