import AxeBuilder from '@axe-core/playwright';
import { expect, test } from '@playwright/test';

test('E2E-SCAFFOLD-01 serves the app and the 404 page', async ({ page }) => {
  await page.goto('/');
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('Public Transport Intelligence');

  await page.goto('/no/such/page');
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('Page not found');
});

test('E2E-SCAFFOLD-02 start page has no serious accessibility violations', async ({ page }) => {
  await page.goto('/');
  const results = await new AxeBuilder({ page }).analyze();
  const blocking = results.violations.filter((v) => v.impact === 'serious' || v.impact === 'critical');
  expect(blocking).toEqual([]);
});
