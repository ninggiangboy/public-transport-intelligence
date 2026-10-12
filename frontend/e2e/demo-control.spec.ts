import { expect, test } from '@playwright/test';

import { demoProfileOn, expectNoSeriousViolations, openStart, passwords, pickRoute, signInAs } from './demo-support';
import { collectErrors, previewOnly } from './support';

// DOC-36 screens/demo-control §10. The cases need the compose stack with the demo profile (`make up-demo`) and the demo
// passwords. E2E-DEMO-03 changes the emission rate for minutes, so it runs in the `late` project
// (e2e/late/demo-control.spec.ts). AC-2 is in E2E-MAP-02; AC-6 in the component test.
test.skip(previewOnly || !passwords.viewer || !passwords.operator, 'Needs the compose stack and the demo passwords');

test.beforeEach(async ({ page }) => {
  test.skip(!(await demoProfileOn(page)), 'Needs the demo profile (make up-demo)');
});

test('E2E-DEMO-01 an operator runs a disruption, watches it on the map and stops it', async ({ page }) => {
  const errors = collectErrors(page);
  await page.goto('/ops/demo');
  await signInAs(page, 'operator');
  await expect(page.getByRole('heading', { level: 1, name: 'Demo scenarios' })).toBeVisible();

  // AC-1: the simulator band answers and keeps moving.
  const strip = page.getByRole('region', { name: 'Simulator' });
  await expect(strip.getByText('Vehicles')).toBeVisible();
  await expect(strip.getByText('Business time')).toBeVisible();

  const dialog = await openStart(page, 'Service disruption');
  await pickRoute(page, dialog, '18');
  await dialog.getByLabel('Duration', { exact: true }).fill('5');
  await dialog.getByRole('button', { name: 'Start scenario' }).click();
  await expect(dialog).toBeHidden();

  const running = page.getByRole('region', { name: 'Running scenarios' });
  const card = running.getByRole('article', { name: 'Service disruption' });
  await expect(card).toBeVisible();
  await expectNoSeriousViolations(page);

  await card.getByRole('link', { name: 'Watch on map' }).click();
  await expect(page).toHaveURL(/\/map\?route=18/);
  await page.goBack();
  await running.getByRole('article', { name: 'Service disruption' }).getByRole('button', { name: 'Stop' }).click();
  await expect(running.getByRole('article', { name: 'Service disruption' })).toBeHidden({ timeout: 15_000 });

  const history = page.getByRole('table', { name: /^Scenario runs/ });
  await expect(history.getByRole('row').filter({ hasText: 'Service disruption' }).first()).toContainText('Stopped', {
    timeout: 15_000,
  });
  expect(errors).toEqual([]);
});

test('E2E-DEMO-02 a second bunching run on the same route is refused, and pairs = 5 never leaves the form', async ({
  page,
}) => {
  await page.goto('/ops/demo');
  await signInAs(page, 'operator');

  let dialog = await openStart(page, 'Bus bunching');
  await pickRoute(page, dialog, '18');
  await dialog.getByRole('button', { name: 'Start scenario' }).click();
  // The first run may find no eligible pair right now; AC-3 needs it to run.
  const refused = dialog.getByRole('alert');
  if (await refused.isVisible({ timeout: 3_000 }).catch(() => false)) {
    test.skip(true, `The first bunching run did not start: ${await refused.innerText()}`);
  }
  await expect(dialog).toBeHidden();

  // AC-3
  dialog = await openStart(page, 'Bus bunching');
  await pickRoute(page, dialog, '18');
  await dialog.getByRole('button', { name: 'Start scenario' }).click();
  await expect(dialog.getByRole('alert')).toContainText('Bus bunching is already running for this target.');

  // AC-5: the browser refuses, the button stays off.
  await dialog.getByLabel('Vehicle pairs').fill('5');
  await expect(dialog.getByText('Must be at most 3.')).toBeVisible();
  await expect(dialog.getByRole('button', { name: 'Start scenario' })).toBeDisabled();
  await dialog.getByRole('button', { name: 'Cancel' }).click();

  const card = page.getByRole('region', { name: 'Running scenarios' }).getByRole('article', { name: 'Bus bunching' });
  await card.first().getByRole('button', { name: 'Stop' }).click();
});

test('E2E-DEMO-04 a viewer has no Demo entry and no access by URL', async ({ page }) => {
  await page.goto('/ops/demo');
  await signInAs(page, 'viewer');
  // AC-7
  await expect(page.getByRole('heading', { name: "You don't have access to this page" })).toBeVisible();
  await expect(page.getByRole('navigation', { name: 'Primary' }).getByRole('link', { name: 'Demo' })).toHaveCount(0);
});
