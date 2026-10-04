import type { Page } from '@playwright/test';

/** Against `vite preview` (no E2E_BASE_URL) there is no API and no Keycloak behind the app. */
export const previewOnly = !process.env.E2E_BASE_URL;

/**
 * Console errors and uncaught exceptions of the page. Under the nginx CSP (E2E_BASE_URL) a blocked script, style or
 * connection shows up here. Failed requests are left out: under `vite preview` every API call fails by design, and the
 * screens' tests check how those failures look.
 */
export function collectErrors(page: Page): string[] {
  const errors: string[] = [];
  page.on('console', (message) => {
    if (message.type() === 'error' && !message.text().startsWith('Failed to load resource')) {
      errors.push(message.text());
    }
  });
  page.on('pageerror', (error) => errors.push(error.message));
  return errors;
}

/**
 * Under `vite preview`: no Keycloak in env.js (the dev file points at localhost:8180, which is not running), so the app
 * starts anonymous without waiting for a silent sign-in; and the API answers 503, as if it were down.
 */
export async function withoutBackend(page: Page) {
  if (!previewOnly) return;
  await page.route('**/env.js', (route) =>
    route.fulfill({
      contentType: 'text/javascript',
      body: 'window.__PTI_ENV__ = { keycloakUrl: "", mapStyle: "offline", demoControl: false };',
    }),
  );
  await page.route('**/api/v1/**', (route) =>
    route.fulfill({
      status: 503,
      contentType: 'application/problem+json',
      body: JSON.stringify({ type: 'urn:pti:problem:service-unavailable', title: 'Service Unavailable', status: 503 }),
    }),
  );
}
