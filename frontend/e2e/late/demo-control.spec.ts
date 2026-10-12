import { expect, test } from '@playwright/test';

import { demoProfileOn, openStart, passwords, signInAs } from '../demo-support';
import { previewOnly } from '../support';

// DOC-36 screens/demo-control §10, E2E-DEMO-03: a load ramp owns the emission rate for about 3 minutes, so it runs
// after every other project (playwright.config.ts `late`).
test.skip(previewOnly || !passwords.operator, 'Needs the compose stack and the demo operator password');

test('E2E-DEMO-03 Speed is locked while a load ramp runs and comes back after Stop all', async ({ page }) => {
  test.skip(!(await demoProfileOn(page)), 'Needs the demo profile (make up-demo)');
  await page.goto('/ops/demo');
  await signInAs(page, 'operator');
  const strip = page.getByRole('region', { name: 'Simulator' });
  const speed = strip.getByRole('radiogroup', { name: 'Speed' });
  await expect(speed).toBeVisible();
  const before = await speed.getByRole('radio', { checked: true }).innerText();

  const dialog = await openStart(page, 'Load ramp');
  await dialog.getByLabel('Rate multipliers').fill('1, 2');
  await dialog.getByLabel('Step duration', { exact: true }).fill('1');
  await expect(dialog.getByText('Total duration 3 min')).toBeVisible();
  await dialog.getByRole('button', { name: 'Start scenario' }).click();
  await expect(dialog).toBeHidden();

  // AC-4: the control is disabled with its tooltip.
  await expect(speed.getByRole('radio', { name: '2×' })).toBeDisabled({ timeout: 10_000 });
  // The wrapper of the disabled control takes focus and shows why.
  await speed.locator('xpath=../..').focus();
  await expect(page.getByRole('tooltip')).toContainText('Rate is controlled by the running load ramp.');

  await page.getByRole('button', { name: 'Stop all' }).click();
  await page.getByRole('menuitem', { name: 'Load ramp' }).click();
  const confirm = page.getByRole('dialog', { name: 'Stop all Load ramp runs?' });
  await confirm.getByRole('button', { name: 'Stop all' }).click();

  // The multipliers return to what they were before the ramp (DOC-25 §7.8).
  await expect(speed.getByRole('radio', { name: '2×' })).toBeEnabled({ timeout: 15_000 });
  await expect(speed.getByRole('radio', { checked: true })).toHaveText(before);
});
