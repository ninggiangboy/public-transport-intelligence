import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Page } from '@playwright/test';

import { collectErrors, previewOnly } from './support';

// DOC-36 screens/route-scorecard §10. The scorecard is for viewers, so both cases need the compose stack with Keycloak
// (E2E_BASE_URL) and the demo viewer password of deploy/compose/.env. Running OtpScorecardJob for yesterday through
// E-33 is left to the operator; without scores the ranking shows its empty state, which the scenario accepts.
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

test('E2E-SCORE-01 ranking, range, drawer, route details, table view and stop profile', async ({ page }) => {
  const errors = collectErrors(page);
  await page.goto('/scorecard');
  await expect(page.getByRole('heading', { name: 'Sign in to view this page' })).toBeVisible();
  await signInAsViewer(page);
  await expect(page.getByRole('heading', { level: 1, name: 'Route scorecard' })).toBeVisible();
  await expectNoSeriousViolations(page);

  await page.getByRole('radio', { name: 'Month' }).click();
  await expect(page).toHaveURL(/from=\d{4}-\d{2}-\d{2}&to=\d{4}-\d{2}-\d{2}/);

  const table = page.getByRole('table', { name: /On-time performance by route/ });
  const empty = page.getByText('No on-time data for this period');
  await expect(table.getByRole('row').nth(1).or(empty)).toBeVisible();
  if (await empty.isVisible()) {
    expect(errors).toEqual([]);
    return;
  }

  await table.getByRole('row').nth(1).click();
  await expect(page).toHaveURL(/route=/);
  const drawer = page.getByRole('dialog');
  await expect(drawer.getByText('Typical delay along the route')).toBeVisible();
  await expect(drawer.getByText('Disruptions in this period')).toBeVisible();
  await expectNoSeriousViolations(page);

  await drawer.getByRole('link', { name: 'Open route details' }).click();
  await expect(page).toHaveURL(/\/scorecard\/[^/?]+\?from=/);
  const heatmap = page.getByRole('region', { name: 'Average delay by hour and weekday' });
  await expect(heatmap).toBeVisible();
  const asTable = heatmap.getByRole('button', { name: 'View as table' });
  if (await asTable.isVisible()) {
    await asTable.click();
    await expect(heatmap.getByRole('table').getByRole('row')).toHaveCount(8);
  }

  await page.getByRole('tab', { name: 'Stop profile' }).click();
  await expect(page.getByRole('table', { name: /Typical delay at each stop/ })).toBeVisible();
  await expectNoSeriousViolations(page);
  expect(errors).toEqual([]);
});

test('E2E-SCORE-02 a 60-day range is cut to 31 days', async ({ page }) => {
  await page.goto('/scorecard?from=2026-07-01&to=2026-08-30');
  await signInAsViewer(page);
  await expect(page.getByText('Max range is 31 days. Showing Jul 31, 2026 – Aug 30, 2026.')).toBeVisible();
});
