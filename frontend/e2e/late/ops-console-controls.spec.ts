import { expect, test, type Page } from '@playwright/test';

import { collectErrors, previewOnly } from '../support';

// DOC-36 screens/ops-console-controls §10, E2E-CTRL-01, and screens/shell-and-navigation §10, E2E-SHELL-03: pausing
// the GTFS-realtime consumer makes the live data stale for minutes, so the cases run in the `late` project, after every
// other one, and one after the other, since both turn the same flag.
const password = process.env.KEYCLOAK_DEMO_OPERATOR_PASSWORD;
const viewerPassword = process.env.KEYCLOAK_DEMO_VIEWER_PASSWORD;

test.skip(previewOnly || !password, 'Needs the compose stack and the demo operator password');
test.describe.configure({ mode: 'serial' });

async function signInAsOperator(page: Page) {
  await page.getByRole('button', { name: 'Sign in' }).first().click();
  await page.locator('#username').fill('operator');
  await page.locator('#password').fill(password ?? '');
  await page.locator('#kc-login').click();
}

/** Resumes the consumer from Controls without a question, whatever state the case left it in. */
async function resume(page: Page) {
  await page.goto('/ops/controls');
  const consumer = page
    .getByRole('region', { name: 'Pipeline' })
    .getByRole('switch', { name: 'GTFS-realtime consumer' });
  if ((await consumer.getAttribute('aria-checked')) === 'false') await consumer.click();
  await expect(consumer).toHaveAttribute('aria-checked', 'true');
}

const MINUTE = 60_000;

test('E2E-CTRL-01 an operator pauses the GTFS-realtime consumer and resumes it', async ({ page }) => {
  test.setTimeout(12 * MINUTE);
  const errors = collectErrors(page);
  await page.goto('/ops/controls');
  await signInAsOperator(page);
  await expect(page.getByRole('heading', { level: 1, name: 'Controls' })).toBeVisible();

  const pipeline = page.getByRole('region', { name: 'Pipeline' });
  const consumer = pipeline.getByRole('switch', { name: 'GTFS-realtime consumer' });
  await expect(consumer).toHaveAttribute('aria-checked', 'true');
  await consumer.click();
  const dialog = page.getByRole('dialog', { name: 'Pause the GTFS-realtime consumer?' });
  await dialog.getByRole('button', { name: 'Pause' }).click();
  const pausedAt = Date.now();

  try {
    // AC-2: the row, its callout and the sidebar chip (shell AC-7).
    await expect(consumer).toHaveAttribute('aria-checked', 'false');
    await expect(pipeline.getByText(/^Paused by operator /)).toBeVisible();
    const nav = page.getByRole('navigation', { name: 'Primary' });
    await expect(nav.getByRole('link', { name: /Controls/ }).getByText('Paused')).toBeVisible({ timeout: 35_000 });

    // AC-1: no micro-batch after 5 s, so the listener line stops moving; the StaleBanner shows within 3 minutes.
    const lastProcessed = pipeline
      .getByText(/^Vehicle positions · last processed/)
      .locator('time')
      .first();
    await page.waitForTimeout(10_000);
    const frozen = await lastProcessed.getAttribute('datetime');
    await page.goto('/ops/jobs');
    await expect(page.getByRole('status').filter({ hasText: 'Live data is delayed' })).toBeVisible({
      timeout: 3 * MINUTE,
    });

    // Jobs AC-7: after 5 minutes the etl-stream stage says Paused (the throughput gap is checked by eye in the trace).
    await page.waitForTimeout(Math.max(0, pausedAt + 5 * MINUTE - Date.now()));
    await expect(page.getByRole('region', { name: 'Pipeline stages' }).getByText('Paused')).toBeVisible();

    await page.goto('/ops/controls');
    await expect(lastProcessed).toHaveAttribute('datetime', frozen ?? '');
  } finally {
    await resume(page);
  }

  // The listener line moves again once micro-batches resume.
  await expect(
    page
      .getByRole('region', { name: 'Pipeline' })
      .getByText(/^Vehicle positions · last processed (just now|\d+ s ago|1 min ago)/),
  ).toBeVisible({ timeout: 2 * MINUTE });
  expect(errors).toEqual([]);
});

test('E2E-SHELL-03 banner and "Paused" follow the pause flag', async ({ browser, page }) => {
  test.skip(!viewerPassword, 'Needs the demo viewer password');
  test.setTimeout(10 * MINUTE);
  const errors = collectErrors(page);
  await page.goto('/ops/controls');
  await signInAsOperator(page);
  const consumer = page
    .getByRole('region', { name: 'Pipeline' })
    .getByRole('switch', { name: 'GTFS-realtime consumer' });
  await expect(consumer).toHaveAttribute('aria-checked', 'true');

  // A viewer watches the live map in a session of their own.
  const viewer = await (await browser.newContext()).newPage();
  await viewer.goto('/map');
  await viewer.getByRole('button', { name: 'Sign in' }).first().click();
  await viewer.locator('#username').fill('viewer');
  await viewer.locator('#password').fill(viewerPassword ?? '');
  await viewer.locator('#kc-login').click();
  await expect(viewer.getByRole('heading', { level: 1, name: 'Live map' })).toBeVisible();
  const banner = viewer.getByRole('status').filter({ hasText: 'Live data is delayed' });
  const chip = viewer
    .getByRole('navigation', { name: 'Primary' })
    .getByRole('link', { name: /Controls/ })
    .getByText('Paused');
  await expect(banner).toHaveCount(0);

  await consumer.click();
  await page
    .getByRole('dialog', { name: 'Pause the GTFS-realtime consumer?' })
    .getByRole('button', { name: 'Pause' })
    .click();
  try {
    // AC-7 within one flag poll (30 s), AC-6 once E-60 says stale (at most 3 minutes).
    await expect(chip).toBeVisible({ timeout: 35_000 });
    await expect(banner).toBeVisible({ timeout: 3 * MINUTE });
  } finally {
    await resume(page);
  }
  // Fresh data again: the banner and the chip go away.
  await expect(chip).toHaveCount(0, { timeout: 35_000 });
  await expect(banner).toHaveCount(0, { timeout: 2 * MINUTE });
  await viewer.context().close();
  expect(errors).toEqual([]);
});
