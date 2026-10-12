import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Page } from '@playwright/test';

import { collectErrors, previewOnly } from './support';

// DOC-36 screens/ops-console-ticketing §10. Ticketing is for viewers, so the case needs the compose stack with Keycloak
// (E2E_BASE_URL) and the demo viewer password of deploy/compose/.env. E2E-TIX-01 and -02 wait for a scenario to make
// an anomaly (up to 22 minutes), so they run in the `late` project (e2e/late/ops-console-ticketing.spec.ts).
const password = process.env.KEYCLOAK_DEMO_VIEWER_PASSWORD;

test.skip(previewOnly || !password, 'Needs the compose stack and the demo viewer password');

async function signInAsViewer(page: Page) {
  await page.getByRole('button', { name: 'Sign in' }).first().click();
  await page.locator('#username').fill('viewer');
  await page.locator('#password').fill(password ?? '');
  await page.locator('#kc-login').click();
}

test('E2E-TIX-03 category=unclassified keeps only unclassified anomalies, and the URL keeps the filter', async ({
  page,
}) => {
  const errors = collectErrors(page);
  const lists: URL[] = [];
  page.on('request', (request) => {
    if (request.url().includes('/api/v1/insights/ticketing-anomalies?')) lists.push(new URL(request.url()));
  });
  await page.goto('/ops/ticketing?category=unclassified');
  await signInAsViewer(page);
  await expect(page.getByRole('heading', { level: 1, name: 'Ticketing anomalies' })).toBeVisible();
  await expect(page).toHaveURL(/category=unclassified/);
  await expect(page.getByRole('button', { name: 'Category: Unclassified' })).toBeVisible();
  // AC-5: the API was asked for unclassified anomalies only, and every row says so.
  await expect.poll(() => lists.some((url) => url.searchParams.getAll('category').includes('unclassified'))).toBe(true);
  const table = page.getByRole('table', { name: 'Ticketing anomalies' });
  const rows = table.getByRole('row');
  const count = await rows.count();
  for (let index = 1; index < count; index += 1) {
    const row = rows.nth(index);
    if ((await row.getByRole('button').count()) === 0) continue; // the empty state
    await expect(row.getByText('Unclassified')).toBeVisible();
  }

  const results = await new AxeBuilder({ page }).analyze();
  const blocking = results.violations
    .filter((v) => v.impact === 'serious' || v.impact === 'critical')
    .map((v) => ({ id: v.id, nodes: v.nodes.slice(0, 3).map((n) => n.target.join(' ')) }));
  expect(blocking).toEqual([]);
  expect(errors).toEqual([]);
});
