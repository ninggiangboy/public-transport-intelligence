import AxeBuilder from '@axe-core/playwright';
import { expect, test } from '@playwright/test';

import { collectErrors, previewOnly } from './support';

// DOC-36 screens/alert-feed §10. The scenarios start incidents through the simulator (/sim/**), which the demo control
// screen (P5-13) wraps; until then only the page itself is checked against the compose stack.
test.skip(previewOnly, 'Needs the compose stack (E2E_BASE_URL) with the API');

test('the alert feed opens for anonymous users without staff filters', async ({ page }) => {
  const errors = collectErrors(page);
  await page.goto('/alerts');
  await expect(page.getByRole('heading', { level: 1, name: 'Alerts' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Audience' })).toHaveCount(0);
  await expect(page.getByRole('tab', { name: /Unacknowledged/ })).toHaveCount(0);
  const results = await new AxeBuilder({ page }).analyze();
  expect(results.violations.filter((v) => v.impact === 'serious' || v.impact === 'critical')).toEqual([]);
  expect(errors).toEqual([]);
});

test.fixme('E2E-ALERT-01 a new disruption reaches anonymous and operator; ack and "Show on map"', () => undefined);
test.fixme('E2E-ALERT-02 an operator accepts a dispatch suggestion on a bunching alert', () => undefined);
test.fixme('E2E-ALERT-03 with the event stream blocked, a ticketing alert arrives by polling', () => undefined);
