import AxeBuilder from '@axe-core/playwright';
import { expect, test } from '@playwright/test';

import { collectErrors, withoutBackend } from './support';

test.beforeEach(async ({ page }) => {
  await withoutBackend(page);
});

test('E2E-SCAFFOLD-01 serves the app and the 404 page', async ({ page }) => {
  const errors = collectErrors(page);

  // Anonymous users start on the live map (DOC-34 §5.2).
  await page.goto('/');
  await expect(page).toHaveURL(/\/map$/);
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('Live map');

  await page.goto('/no/such/page');
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('Page not found');
  expect(errors).toEqual([]);
});

test('E2E-SCAFFOLD-02 start page has no serious accessibility violations', async ({ page }) => {
  await page.goto('/');
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('Live map');
  const results = await new AxeBuilder({ page }).analyze();
  const blocking = results.violations.filter((v) => v.impact === 'serious' || v.impact === 'critical');
  expect(blocking).toEqual([]);
});
