import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Page } from '@playwright/test';

import { readState } from '../global-setup';
import { fillReplay, MINUTE, passwords, signInAs } from '../replay-support';
import { collectErrors, previewOnly } from '../support';

// DOC-36 screens/ops-console-replay §10, project `late`: the raw zone must hold trip updates older than the settle time
// (`pti.replay.raw-settle`, 10 minutes), so the cases wait until now − 11 min ≥ stackStart + 2 min (at most 15 min).
// The settle time is not shortened for the test, to keep the real behaviour.

test.skip(previewOnly || !passwords.operator, 'Needs the compose stack and the demo operator password');
test.describe.configure({ mode: 'serial' });

const SOURCE = 'Trip updates';
const MAX_WAIT_MS = 15 * MINUTE;

/** Waits for raw zone data old enough, then returns the range `[stackStart + 1 min, now − 11 min]`. */
async function settledRange(): Promise<{ from: number; to: number }> {
  const stackStart = Date.parse(readState()?.stackStart ?? new Date().toISOString());
  const ready = stackStart + 2 * MINUTE + 11 * MINUTE;
  const wait = ready - Date.now();
  expect(wait, 'stackStart is too recent for this run').toBeLessThanOrEqual(MAX_WAIT_MS);
  if (wait > 0) await new Promise((resolve) => setTimeout(resolve, wait));
  return { from: stackStart + MINUTE, to: Date.now() - 11 * MINUTE };
}

async function expectNoSeriousViolations(page: Page) {
  const results = await new AxeBuilder({ page }).analyze();
  const blocking = results.violations
    .filter((v) => v.impact === 'serious' || v.impact === 'critical')
    .map((v) => ({ id: v.id, nodes: v.nodes.slice(0, 3).map((n) => n.target.join(' ')) }));
  expect(blocking).toEqual([]);
}

let range: { from: number; to: number } | undefined;
let replayUrl: string | undefined;

test('E2E-REPLAY-01 an operator estimates and starts a replay, which runs', async ({ page }) => {
  test.setTimeout(MAX_WAIT_MS + 5 * MINUTE);
  const errors = collectErrors(page);
  range = await settledRange();
  const form = await fillReplay(page, SOURCE, range.from, range.to);

  // AC-1: the estimate comes within a second of the last change, with every warning of the API.
  const estimate = page.getByRole('region', { name: 'Estimated work' });
  await expect(estimate.getByText('Raw zone coverage')).toBeVisible({ timeout: 2_000 });
  await expectNoSeriousViolations(page);
  await expect(form).toBeVisible();

  await estimate.getByRole('button', { name: 'Start replay' }).click();
  const dialog = page.getByRole('dialog');
  await expect(dialog.getByText(/^Replay Trip updates from /)).toBeVisible();
  await dialog.getByRole('button', { name: 'Start replay' }).click();

  // AC-3: the drawer follows the replay from Queued to Running.
  await expect(page).toHaveURL(/tab=history.*replay=|replay=.*tab=history/);
  replayUrl = page.url();
  const drawer = page.getByRole('dialog');
  await expect(drawer.getByText(/^(Queued|Running|Done)$/).first()).toBeVisible();
  await expect(drawer.getByText(/^(Running|Done)$/).first()).toBeVisible({ timeout: 60_000 });
  expect(errors).toEqual([]);
});

test('E2E-REPLAY-02 a second replay of the source leads to the running one', async ({ page }) => {
  test.skip(range === undefined || replayUrl === undefined, 'E2E-REPLAY-01 did not start a replay');
  const { from, to } = range ?? { from: 0, to: 0 };
  await fillReplay(page, SOURCE, from, to);
  const estimate = page.getByRole('region', { name: 'Estimated work' });
  const running = estimate.getByText('A replay for this source is already running.');
  // While the first one still runs, E-53 warns and the form links to it instead of sending a request that gets 409
  // (FR-12.3, AC-4); the 409 dialog itself is covered by the component test.
  if (await running.isVisible({ timeout: 5_000 }).catch(() => false)) {
    await expect(estimate.getByRole('button', { name: 'Start replay' })).toBeDisabled();
    await estimate.getByRole('button', { name: 'View running replay' }).click();
    await expect(page).toHaveURL(new RegExp(`replay=${new URL(replayUrl ?? '').searchParams.get('replay') ?? ''}`));
  }
});

test('E2E-REPLAY-01 the replay ends with its statistics and its run', async ({ page }) => {
  test.skip(replayUrl === undefined, 'E2E-REPLAY-01 did not start a replay');
  test.setTimeout(15 * MINUTE);
  await page.goto(replayUrl ?? '/ops/replay');
  await signInAs(page, 'operator');
  const drawer = page.getByRole('dialog');
  // AC-5.
  await expect(drawer.getByText('Done', { exact: true })).toBeVisible({ timeout: 12 * MINUTE });
  await expect(drawer.getByRole('table', { name: 'Replay statistics' })).toBeVisible();
  await expect(drawer.getByRole('link', { name: /^Run #\d+/ })).toHaveAttribute('href', /\/ops\/jobs\?run=job%3A\d+/);
  await expectNoSeriousViolations(page);
});
