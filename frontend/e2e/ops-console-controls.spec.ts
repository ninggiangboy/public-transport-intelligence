import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Page } from '@playwright/test';

import { collectErrors, previewOnly } from './support';

// DOC-36 screens/ops-console-controls §10. The cases need the compose stack with Keycloak (E2E_BASE_URL) and the demo
// passwords of deploy/compose/.env. E2E-CTRL-01 pauses the live feed for minutes, so it runs in the `late` project
// (e2e/late/ops-console-controls.spec.ts).
const passwords = {
  viewer: process.env.KEYCLOAK_DEMO_VIEWER_PASSWORD,
  operator: process.env.KEYCLOAK_DEMO_OPERATOR_PASSWORD,
};

test.skip(previewOnly || !passwords.viewer || !passwords.operator, 'Needs the compose stack and the demo passwords');

async function signInAs(page: Page, user: 'viewer' | 'operator') {
  await page.getByRole('button', { name: 'Sign in' }).first().click();
  await page.locator('#username').fill(user);
  await page.locator('#password').fill(passwords[user] ?? '');
  await page.locator('#kc-login').click();
}

async function expectNoSeriousViolations(page: Page) {
  const results = await new AxeBuilder({ page }).analyze();
  const blocking = results.violations
    .filter((v) => v.impact === 'serious' || v.impact === 'critical')
    .map((v) => ({ id: v.id, nodes: v.nodes.slice(0, 3).map((n) => n.target.join(' ')) }));
  expect(blocking).toEqual([]);
}

/** Today in the agency's zone on the business clock of E-60 ("YYYY-MM-DD"). */
async function businessToday(page: Page): Promise<string> {
  const response = await page.request.get('/api/v1/system/freshness');
  const freshness = (await response.json()) as { businessNow: string; activeFeed?: { timezone: string } };
  return new Intl.DateTimeFormat('en-CA', {
    timeZone: freshness.activeFeed?.timezone ?? 'America/Chicago',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(new Date(freshness.businessNow));
}

test('E2E-CTRL-02 an operator runs OtpScorecardJob for yesterday and cannot pick today', async ({ page }) => {
  const errors = collectErrors(page);
  await page.goto('/ops/controls#run-job');
  await signInAs(page, 'operator');
  await expect(page.getByRole('heading', { level: 1, name: 'Controls' })).toBeVisible();

  const card = page.getByRole('region', { name: 'Run a job' });
  await expect(card.getByRole('combobox', { name: 'Job' })).toHaveValue('OtpScorecardJob');
  await card.getByRole('button', { name: 'Run job' }).click();
  const dialog = page.getByRole('dialog', { name: 'Run OtpScorecardJob?' });
  await dialog.getByRole('button', { name: 'Run job' }).click();
  await expect(dialog).toBeHidden();

  // AC-5: Queued, then Running with a link to the run.
  const recent = card.getByRole('region', { name: 'Recent requests' });
  await expect(recent.getByText('OtpScorecardJob')).toBeVisible();
  await expect(recent.getByText(/^(Running|Done)$/)).toBeVisible({ timeout: 30_000 });
  await expect(recent.getByRole('link', { name: /^Run #\d+$/ })).toHaveAttribute('href', /\/ops\/jobs\?run=job%3A\d+/);

  // AC-6: today is refused before any request.
  let posted = false;
  page.on('request', (request) => {
    if (request.method() === 'POST' && request.url().endsWith('/api/v1/etl/jobs')) posted = true;
  });
  await card.getByLabel('Add date').fill(await businessToday(page));
  await card.getByRole('button', { name: 'Add date' }).click();
  await expect(card.getByText('Dates must be before today.')).toBeVisible();
  await expect(card.getByRole('button', { name: 'Run job' })).toBeDisabled();
  expect(posted).toBe(false);
  expect(errors).toEqual([]);
});

test('E2E-CTRL-03 a viewer sees every block read-only', async ({ page }) => {
  const errors = collectErrors(page);
  await page.goto('/ops/controls');
  await expect(page.getByRole('heading', { name: 'Sign in to view this page' })).toBeVisible();
  await signInAs(page, 'viewer');
  await expect(page.getByRole('heading', { level: 1, name: 'Controls' })).toBeVisible();

  // AC-4.
  await expect(page.getByRole('main').getByText('Read-only')).toBeVisible();
  const switches = page.getByRole('switch');
  await expect(switches).toHaveCount(7);
  for (const control of await switches.all()) await expect(control).toBeDisabled();
  await expect(page.getByRole('button', { name: 'Run job' })).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Load feed' })).toHaveCount(0);
  await page.getByRole('switch', { name: 'GTFS-realtime consumer' }).locator('..').focus();
  await expect(page.getByRole('tooltip')).toHaveText('Requires the operator role.');
  await expectNoSeriousViolations(page);
  expect(errors).toEqual([]);
});
