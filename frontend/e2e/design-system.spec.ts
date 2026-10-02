/// <reference lib="dom" />
import AxeBuilder from '@axe-core/playwright';
import { expect, test } from '@playwright/test';

// The catalogue is built into the e2e build only (playwright.config.ts sets VITE_PTI_UI_CATALOG); the image does not have it.
test.skip(Boolean(process.env.E2E_BASE_URL), 'The /_ui catalogue is not part of the image');

for (const theme of ['light', 'dark'] as const) {
  test(`DS-01 /_ui has no serious accessibility violations in ${theme}`, async ({ page }) => {
    const errors: string[] = [];
    page.on('console', (message) => {
      if (message.type() === 'error') errors.push(message.text());
    });
    page.on('pageerror', (error) => errors.push(error.message));

    await page.addInitScript((value) => {
      window.localStorage.setItem('pti.theme', value);
    }, theme);
    await page.goto('/_ui');
    await expect(page.getByRole('heading', { level: 1, name: 'Design system' })).toBeVisible();
    if (theme === 'dark') await expect(page.locator('html')).toHaveClass(/dark/);
    else await expect(page.locator('html')).not.toHaveClass(/dark/);

    const results = await new AxeBuilder({ page }).analyze();
    const blocking = results.violations
      .filter((v) => v.impact === 'serious' || v.impact === 'critical')
      .map((v) => ({
        id: v.id,
        impact: v.impact,
        nodes: v.nodes.slice(0, 5).map((n) => n.target.join(' ') + ' :: ' + (n.any[0]?.message ?? '')),
      }));
    expect(blocking).toEqual([]);
    expect(errors).toEqual([]);
  });
}

test('/_ui renders the same specimens in a light and a dark panel', async ({ page }) => {
  await page.goto('/_ui');
  await expect(page.getByRole('heading', { level: 1, name: 'Design system' })).toBeVisible();
  await expect(page.locator('.light').first()).toBeVisible();
  await expect(page.locator('.dark').first()).toBeVisible();
  // The dark panel really re-themes: its canvas differs from the light one.
  const [light, dark] = await Promise.all([
    page
      .locator('.light')
      .first()
      .evaluate((el) => getComputedStyle(el).backgroundColor),
    page
      .locator('.dark')
      .first()
      .evaluate((el) => getComputedStyle(el).backgroundColor),
  ]);
  expect(light).not.toBe(dark);
});
