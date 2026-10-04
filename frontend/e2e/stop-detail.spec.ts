import AxeBuilder from '@axe-core/playwright';
import { expect, test } from '@playwright/test';

import { collectErrors, previewOnly } from './support';

// DOC-36 screens/stop-detail §10. They read real stops and predictions, so they run against the compose stack.
test.skip(previewOnly, 'Needs the compose stack (E2E_BASE_URL) with the API and a loaded feed');

test('E2E-STOP-01 a passenger on a phone finds a stop and sees its departures', async ({ page }) => {
  const errors = collectErrors(page);
  await page.setViewportSize({ width: 375, height: 740 });
  await page.goto('/stops');
  await page.getByRole('searchbox', { name: 'Search stops' }).fill('nicollet');
  await expect(page).toHaveURL(/\/stops\?q=nicollet$/);
  const first = page
    .getByRole('main')
    .getByRole('link', { name: /Nicollet/ })
    .first();
  await first.click();
  await expect(page).toHaveURL(/\/stops\/[^/?]+$/);
  await expect(page.getByRole('heading', { level: 1 })).toContainText(/Nicollet/);

  const departures = page.getByRole('region', { name: 'Departures' });
  await expect(
    departures.getByRole('listitem').first().or(departures.getByText('No upcoming departures')),
  ).toBeVisible();
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
  expect(overflow).toBeLessThanOrEqual(0);

  const results = await new AxeBuilder({ page }).analyze();
  expect(results.violations.filter((v) => v.impact === 'serious' || v.impact === 'critical')).toEqual([]);
  expect(errors).toEqual([]);
});

test('E2E-STOP-03 an unknown stop says so and offers the search', async ({ page }) => {
  await page.goto('/stops/does-not-exist');
  await expect(page.getByRole('heading', { level: 1, name: 'Stop not found' })).toBeVisible();
  await expect(page.getByRole('searchbox', { name: 'Search stops' })).toBeVisible();
});

// Needs a disruption started and stopped through the simulator (/sim/**), which the demo control screen (P5-13) wraps.
test.fixme('E2E-STOP-02 the disruption callout appears and turns "back to normal"', () => undefined);
