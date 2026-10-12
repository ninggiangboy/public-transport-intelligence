import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Page } from '@playwright/test';

import { collectErrors, previewOnly } from './support';

// DOC-36 screens/ops-console-jobs §10. Pipeline is for viewers, so the cases need the compose stack with Keycloak
// (E2E_BASE_URL), the simulator running (stream runs every minute) and the demo viewer password of
// deploy/compose/.env (E2E-JOBS-02 also needs the operator password: it starts a job from Controls).
const password = process.env.KEYCLOAK_DEMO_VIEWER_PASSWORD;
const operatorPassword = process.env.KEYCLOAK_DEMO_OPERATOR_PASSWORD;

test.skip(previewOnly || !password, 'Needs the compose stack and the demo viewer password');

async function signInAsViewer(page: Page) {
  await page.getByRole('button', { name: 'Sign in' }).first().click();
  await page.locator('#username').fill('viewer');
  await page.locator('#password').fill(password ?? '');
  await page.locator('#kc-login').click();
}

async function signInAsOperator(page: Page) {
  await page.getByRole('button', { name: 'Sign in' }).first().click();
  await page.locator('#username').fill('operator');
  await page.locator('#password').fill(operatorPassword ?? '');
  await page.locator('#kc-login').click();
}

async function expectNoSeriousViolations(page: Page) {
  const results = await new AxeBuilder({ page }).analyze();
  const blocking = results.violations
    .filter((v) => v.impact === 'serious' || v.impact === 'critical')
    .map((v) => ({ id: v.id, nodes: v.nodes.slice(0, 3).map((n) => n.target.join(' ')) }));
  expect(blocking).toEqual([]);
}

/** Opens the newest stream run of the Streaming tab in the drawer. */
async function openStreamRun(page: Page) {
  await page.getByRole('tab', { name: 'Streaming' }).click();
  await expect(page).toHaveURL(/kind=STREAM/);
  const table = page.getByRole('table', { name: /^Runs started/ });
  await table.getByRole('row').nth(1).click();
  await expect(page).toHaveURL(/run=stream%3A/);
  return page.getByRole('dialog');
}

test('E2E-JOBS-01 a viewer reads the pipeline, changes the period and opens a stream run', async ({ page }) => {
  const errors = collectErrors(page);
  await page.goto('/ops/jobs');
  await expect(page.getByRole('heading', { name: 'Sign in to view this page' })).toBeVisible();
  await signInAsViewer(page);
  await expect(page.getByRole('heading', { level: 1, name: 'Pipeline' })).toBeVisible();
  // AC-5: read-only, no actions.
  await expect(page.getByText('Read-only').first()).toBeVisible();
  await expect(page.getByRole('link', { name: 'Run a job' })).toHaveCount(0);
  // AC-1.
  const throughput = page.getByRole('region', { name: 'Throughput' });
  await expect(throughput.getByText('Read', { exact: true })).toBeVisible();
  await expect(throughput.getByText('Written', { exact: true })).toBeVisible();
  await expectNoSeriousViolations(page);

  await page.getByRole('radio', { name: '6h' }).click();
  await expect(page).toHaveURL(/window=6h/);

  // AC-4: a stream run has micro-batches and no restart.
  const drawer = await openStreamRun(page);
  await expect(drawer.getByRole('heading', { name: 'Micro-batches' })).toBeVisible();
  await expect(drawer.getByRole('button', { name: 'Restart from failed step' })).toHaveCount(0);
  await expectNoSeriousViolations(page);
  expect(errors).toEqual([]);
});

/** "YYYY-MM-DDTHH:mm" in the agency's zone on the business clock of E-60, `offsetMs` from now. */
async function businessLocal(page: Page, offsetMs: number): Promise<string> {
  const response = await page.request.get('/api/v1/system/freshness');
  const freshness = (await response.json()) as { businessNow: string; activeFeed?: { timezone: string } };
  const parts = Object.fromEntries(
    new Intl.DateTimeFormat('en-CA', {
      timeZone: freshness.activeFeed?.timezone ?? 'America/Chicago',
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      hourCycle: 'h23',
    })
      .formatToParts(new Date(Date.parse(freshness.businessNow) + offsetMs))
      .map((part) => [part.type, part.value]),
  );
  return `${parts.year}-${parts.month}-${parts.day}T${parts.hour}:${parts.minute}`;
}

test('E2E-JOBS-02 an operator stops a running job and restarts it from the failed step', async ({ page }) => {
  test.skip(!operatorPassword, 'Needs the demo operator password');
  test.setTimeout(5 * 60_000);
  const errors = collectErrors(page);
  // AnalyticsRecomputeJob over the last business hour, started from "Run a job" of Controls (E-33).
  await page.goto('/ops/controls#run-job');
  await signInAsOperator(page);
  const card = page.getByRole('region', { name: 'Run a job' });
  await card.getByRole('combobox', { name: 'Job' }).selectOption('AnalyticsRecomputeJob');
  await card.getByLabel('From').fill(await businessLocal(page, -60 * 60_000));
  await card.getByLabel('To').fill(await businessLocal(page, -60_000));
  await card.getByRole('button', { name: 'Run job' }).click();
  await page
    .getByRole('dialog', { name: 'Run AnalyticsRecomputeJob?' })
    .getByRole('button', { name: 'Run job' })
    .click();
  const runLink = card.getByRole('region', { name: 'Recent requests' }).getByRole('link', { name: /^Run #\d+$/ });
  await expect(runLink).toBeVisible({ timeout: 30_000 });
  await runLink.click();

  // AC-2: stop it while it runs.
  await expect(page).toHaveURL(/\/ops\/jobs\?run=job%3A\d+/);
  const drawer = page.getByRole('dialog');
  await expect(drawer.getByText('Running').first()).toBeVisible();
  await drawer.getByRole('button', { name: 'Stop run' }).click();
  await page
    .getByRole('dialog', { name: /^Stop AnalyticsRecomputeJob #\d+\?$/ })
    .getByRole('button', { name: 'Stop run' })
    .click();
  await expect(drawer.getByText('Stopped').first()).toBeVisible({ timeout: 60_000 });

  // AC-3: restart it; the new run completes.
  await drawer.getByRole('button', { name: 'Restart from failed step' }).click();
  await page
    .getByRole('dialog', { name: /^Restart AnalyticsRecomputeJob #\d+\?$/ })
    .getByRole('button', { name: 'Restart from failed step' })
    .click();
  await page.getByRole('button', { name: 'Open' }).click({ timeout: 60_000 });
  await expect(page.getByRole('dialog').getByText('Completed').first()).toBeVisible({ timeout: 3 * 60_000 });
  expect(errors).toEqual([]);
});

test('E2E-JOBS-03 a batch id of the drawer opens its lineage, which links back to the run', async ({ page }) => {
  const errors = collectErrors(page);
  await page.goto('/ops/jobs');
  await signInAsViewer(page);
  await expect(page.getByRole('heading', { level: 1, name: 'Pipeline' })).toBeVisible();

  const drawer = await openStreamRun(page);
  const batches = drawer.getByRole('table', { name: /micro-batches of this minute/ });
  await batches.getByRole('row').nth(1).getByRole('link').first().click();
  await expect(page).toHaveURL(/\/ops\/batches\/[0-9a-f-]{36}$/);
  await expect(page.getByRole('heading', { level: 1, name: /^Batch / })).toBeVisible();
  await expect(page.getByText('Stream micro-batch')).toBeVisible();
  await expect(page.getByText('Data quality')).toBeVisible();
  await expectNoSeriousViolations(page);

  await page.getByRole('link', { name: /^stream:/ }).click();
  await expect(page).toHaveURL(/\/ops\/jobs\?run=stream%3A/);
  await expect(page.getByRole('dialog')).toBeVisible();
  expect(errors).toEqual([]);
});
