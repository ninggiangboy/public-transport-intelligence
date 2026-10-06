import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Page } from '@playwright/test';

import { collectErrors, previewOnly } from './support';

// DOC-36 screens/ops-console-dlq §10. The cases need the compose stack with Keycloak (E2E_BASE_URL), the simulator
// running and the demo passwords of deploy/compose/.env. The 10,000-row scroll of AC-1 is measured by the component
// test of DataTable (DS-06) and by the Playwright trace of the nightly run (DOC-44 §10).
const passwords = {
  viewer: process.env.KEYCLOAK_DEMO_VIEWER_PASSWORD,
  operator: process.env.KEYCLOAK_DEMO_OPERATOR_PASSWORD,
};
const keycloakUrl = process.env.E2E_KEYCLOAK_URL ?? 'http://localhost:8180';

test.skip(previewOnly || !passwords.viewer, 'Needs the compose stack and the demo passwords');

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

/** The simulator's `bad-data` scenario (DOC-25 §7.4), started with the operator's token from the smoke client. */
async function injectBadData(page: Page, kinds: string[]) {
  const token = await page.request.post(`${keycloakUrl}/realms/pti/protocol/openid-connect/token`, {
    form: {
      grant_type: 'password',
      client_id: 'pti-smoke',
      username: 'operator',
      password: passwords.operator ?? '',
    },
  });
  expect(token.ok()).toBe(true);
  const { access_token: accessToken } = (await token.json()) as { access_token: string };
  const started = await page.request.post('/sim/scenarios/bad-data', {
    headers: { Authorization: `Bearer ${accessToken}` },
    data: { ratio: 0.01, kinds, entityTypes: ['VEHICLE_POSITION'], duration: 'PT1M' },
  });
  expect(started.status()).toBe(201);
}

test('E2E-DLQ-01 a viewer filters the records, opens one and finds no action', async ({ page }) => {
  const errors = collectErrors(page);
  await page.goto('/ops/dlq');
  await expect(page.getByRole('heading', { name: 'Sign in to view this page' })).toBeVisible();
  await signInAs(page, 'viewer');
  await expect(page.getByRole('heading', { level: 1, name: 'Dead letters' })).toBeVisible();
  // AC-5: no checkbox, no action, a badge.
  await expect(page.getByText('Read-only').first()).toBeVisible();
  await expect(page.getByRole('checkbox')).toHaveCount(0);

  await page.getByRole('button', { name: 'Source' }).click();
  await page.getByRole('checkbox', { name: 'Vehicle positions' }).click();
  await expect(page).toHaveURL(/source=GTFS_RT_VEHICLE_POSITION/);
  await page.keyboard.press('Escape');

  const list = page.getByRole('table', { name: 'Dead letters list' });
  const rows = list.getByRole('row');
  if ((await rows.count()) > 1) {
    await rows.nth(1).click();
    await expect(page).toHaveURL(/id=/);
    const detail = page.getByRole('article', { name: 'Dead letter detail' });
    await expect(detail.getByRole('heading', { name: 'Payload' })).toBeVisible();
    await expect(detail.getByRole('button', { name: 'Replay' })).toHaveCount(0);
  }
  await expectNoSeriousViolations(page);
  expect(errors).toEqual([]);
});

test.describe('an operator fixes a record', () => {
  test.skip(!passwords.operator, 'Needs the demo operator password');
  const fixed = '{"vehicle":{"id":"1742"},"position":{"latitude":44.97,"longitude":-93.27}}';

  test('E2E-DLQ-02 a payload that breaks the schema is refused on the field and not saved (AC-3)', async ({ page }) => {
    await page.goto('/ops/dlq?stage=QUALITY');
    await signInAs(page, 'operator');
    await injectBadData(page, ['out_of_bbox']);
    const list = page.getByRole('table', { name: 'Dead letters list' });
    await expect(list.getByRole('row').nth(1)).toBeVisible({ timeout: 90_000 });
    await list.getByRole('row').nth(1).click();
    await page.getByRole('button', { name: 'Edit payload' }).click();
    const editor = page.getByRole('textbox', { name: 'Payload JSON' });
    await editor.click();
    await page.keyboard.press('ControlOrMeta+A');
    await page.keyboard.type('{"vehicle":{"id":"1742"},"position":{"latitude":"abc","longitude":-93.27}}');
    await page.getByRole('button', { name: 'Save', exact: true }).click();
    await expect(page.getByRole('alert')).toContainText('The payload was not saved');
    await expect(page.getByRole('textbox', { name: 'Payload JSON' })).toBeVisible();
  });

  test('E2E-DLQ-03 the corrected record is replayed and ends up replayed (AC-2)', async ({ page }) => {
    await page.goto('/ops/dlq?stage=QUALITY');
    await signInAs(page, 'operator');
    await injectBadData(page, ['out_of_bbox']);
    const list = page.getByRole('table', { name: 'Dead letters list' });
    await expect(list.getByRole('row').nth(1)).toBeVisible({ timeout: 90_000 });
    await list.getByRole('row').nth(1).click();
    await page.getByRole('button', { name: 'Edit payload' }).click();
    const editor = page.getByRole('textbox', { name: 'Payload JSON' });
    await editor.click();
    await page.keyboard.press('ControlOrMeta+A');
    await page.keyboard.type(fixed);
    await page.getByRole('button', { name: 'Save & replay' }).click();
    const dialog = page.getByRole('dialog', { name: 'Replay this record?' });
    await dialog.getByRole('button', { name: 'Replay' }).click();
    const detail = page.getByRole('article', { name: 'Dead letter detail' });
    await expect(detail.getByText('Replay requested').first()).toBeVisible();
    await expect(detail.getByText('Replayed').first()).toBeVisible({ timeout: 60_000 });
    await expect(detail.getByText('Payload edited')).toBeVisible();
  });
});

// Needs the triage worker (DOC-24), which arrives in P6; AC-4 is checked by the component test with MSW until then.
test.fixme('E2E-DLQ-04 an operator confirms the records auto-triage sent for confirmation', () => undefined);
