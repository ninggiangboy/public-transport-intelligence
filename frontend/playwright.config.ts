import { defineConfig, devices } from '@playwright/test';

// E2E runs against the compose stack (E2E_BASE_URL=http://localhost:8080, DOC-41 §10.3) or, by default, against
// `vite preview` of the current build (DOC-44 §10).
const baseURL = process.env.E2E_BASE_URL ?? 'http://localhost:4173';
const isCi = Boolean(process.env.CI);

export default defineConfig({
  testDir: './e2e',
  fullyParallel: true,
  forbidOnly: isCi,
  retries: isCi ? 1 : 0,
  reporter: isCi ? [['github'], ['html', { open: 'never' }]] : 'list',
  use: {
    baseURL,
    trace: 'on-first-retry',
    video: 'retain-on-failure',
  },
  projects: [{ name: 'main', use: { ...devices['Desktop Chrome'] } }],
  // The preview build keeps the /_ui design-system catalogue (DOC-35 §10, DS-01); the image build does not set the flag.
  webServer: process.env.E2E_BASE_URL
    ? undefined
    : {
        command: 'pnpm build && pnpm preview',
        url: baseURL,
        reuseExistingServer: !isCi,
        timeout: 120_000,
        env: { VITE_PTI_UI_CATALOG: 'true' },
      },
});
