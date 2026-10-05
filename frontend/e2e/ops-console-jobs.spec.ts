import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Page } from '@playwright/test';

import { collectErrors, previewOnly } from './support';

// DOC-36 screens/ops-console-jobs §10. Pipeline is for viewers, so the cases need the compose stack with Keycloak
// (E2E_BASE_URL), the simulator running (stream runs every minute) and the demo viewer password of
// deploy/compose/.env.
const password = process.env.KEYCLOAK_DEMO_VIEWER_PASSWORD;

test.skip(previewOnly || !password, 'Needs the compose stack and the demo viewer password');

async function signInAsViewer(page: Page) {
  await page.getByRole('button', { name: 'Sign in' }).first().click();
  await page.locator('#username').fill('viewer');
  await page.locator('#password').fill(password ?? '');
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

// Needs "Run a job" of the Controls screen (P5-11) to start AnalyticsRecomputeJob through E-33 as an operator.
test.fixme('E2E-JOBS-02 an operator stops a running job and restarts it from the failed step', () => undefined);

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
