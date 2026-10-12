import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Page } from '@playwright/test';

import { collectErrors, previewOnly } from '../support';

// DOC-36 screens/ops-console-ticketing §10, the cases that wait for a scenario to make an anomaly: a 15-minute window
// closes and the detection job, every 5 minutes, picks it up (AC-1, at most about 20 minutes). They need the compose
// stack with Keycloak (E2E_BASE_URL), the simulator, and the demo passwords of deploy/compose/.env; the scenarios are
// started with the operator's token from the smoke client, like the bad-data of e2e/ops-console-dlq.spec.ts.
const passwords = {
  viewer: process.env.KEYCLOAK_DEMO_VIEWER_PASSWORD,
  operator: process.env.KEYCLOAK_DEMO_OPERATOR_PASSWORD,
};
const keycloakUrl = process.env.E2E_KEYCLOAK_URL ?? 'http://localhost:8180';
/** A window of 15 minutes, the job every 5 minutes and a little slack (AC-1, §10). */
const WAIT_MS = 22 * 60_000;

test.skip(previewOnly || !passwords.viewer || !passwords.operator, 'Needs the compose stack and the demo passwords');

async function signInAsViewer(page: Page) {
  await page.getByRole('button', { name: 'Sign in' }).first().click();
  await page.locator('#username').fill('viewer');
  await page.locator('#password').fill(passwords.viewer ?? '');
  await page.locator('#kc-login').click();
}

/** Starts a simulator scenario (DOC-25 §7.6, §7.7) with the operator's token. */
async function startScenario(page: Page, name: 'ticket-spike' | 'refund-burst', body: Record<string, unknown>) {
  const token = await page.request.post(`${keycloakUrl}/realms/pti/protocol/openid-connect/token`, {
    form: { grant_type: 'password', client_id: 'pti-smoke', username: 'operator', password: passwords.operator ?? '' },
  });
  expect(token.ok()).toBe(true);
  const { access_token: accessToken } = (await token.json()) as { access_token: string };
  const started = await page.request.post(`/api/v1/sim/scenarios/${name}`, {
    headers: { Authorization: `Bearer ${accessToken}` },
    data: body,
  });
  expect(started.status()).toBe(201);
}

async function expectNoSeriousViolations(page: Page) {
  const results = await new AxeBuilder({ page }).analyze();
  const blocking = results.violations
    .filter((v) => v.impact === 'serious' || v.impact === 'critical')
    .map((v) => ({ id: v.id, nodes: v.nodes.slice(0, 3).map((n) => n.target.join(' ')) }));
  expect(blocking).toEqual([]);
}

/** The first row of the table whose Trigger column reads `trigger`, waited for by reloading every 30 s. */
async function waitForTrigger(page: Page, trigger: string) {
  const row = page.getByRole('table', { name: 'Ticketing anomalies' }).getByRole('row').filter({ hasText: trigger });
  await expect
    .poll(
      async () => {
        if ((await row.count()) > 0) return true;
        await page.reload();
        return false;
      },
      { timeout: WAIT_MS, intervals: [30_000] },
    )
    .toBe(true);
  return row.first();
}

test('E2E-TIX-01 a ticket spike shows up as a Sales volume anomaly with its previous windows', async ({ page }) => {
  test.setTimeout(WAIT_MS + 3 * 60_000);
  const errors = collectErrors(page);
  await page.goto('/ops/ticketing?window=6h');
  await signInAsViewer(page);
  await expect(page.getByRole('heading', { level: 1, name: 'Ticketing anomalies' })).toBeVisible();
  await startScenario(page, 'ticket-spike', { duration: 'PT20M' });

  // AC-1 (volume): the row, its detail with "Previous windows", and the chart's "Sales volume" series.
  const row = await waitForTrigger(page, 'Sales volume');
  await row.click();
  await expect(page).toHaveURL(/anomaly=/);
  const detail = page.getByRole('article', { name: 'Anomaly detail' });
  await expect(detail.getByText(/^Previous windows · last \d+ windows$/)).toBeVisible();
  await page.getByRole('button', { name: 'View as table' }).click();
  await expect(page.getByRole('columnheader', { name: 'Sales volume' })).toBeVisible();
  await expectNoSeriousViolations(page);
  expect(errors).toEqual([]);
});

test('E2E-TIX-02 a refund burst shows up as a Refund ratio anomaly and the trigger filter keeps it', async ({
  page,
}) => {
  test.setTimeout(WAIT_MS + 3 * 60_000);
  const errors = collectErrors(page);
  await page.goto('/ops/ticketing?window=6h');
  await signInAsViewer(page);
  await expect(page.getByRole('heading', { level: 1, name: 'Ticketing anomalies' })).toBeVisible();
  await startScenario(page, 'refund-burst', {});

  await waitForTrigger(page, 'Refund ratio');
  await page.getByRole('button', { name: 'Trigger' }).click();
  await page.getByRole('menuitemradio', { name: 'Refund ratio' }).click();
  await expect(page).toHaveURL(/trigger=REFUND_RATIO/);
  const rows = page.getByRole('table', { name: 'Ticketing anomalies' }).getByRole('row');
  await expect(rows.filter({ hasText: 'Refund ratio' }).first()).toBeVisible();
  await expect(rows.filter({ hasText: 'Sales volume' })).toHaveCount(0);
  expect(errors).toEqual([]);
});
