import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Page } from '@playwright/test';

import { collectErrors, previewOnly, withoutBackend } from './support';

// screens/shell-and-navigation §10. The sign-in cases need the compose stack with Keycloak (E2E_BASE_URL) and the demo
// passwords of deploy/compose/.env.
const passwords = {
  viewer: process.env.KEYCLOAK_DEMO_VIEWER_PASSWORD,
  operator: process.env.KEYCLOAK_DEMO_OPERATOR_PASSWORD,
};

async function expectNoSeriousViolations(page: Page) {
  const results = await new AxeBuilder({ page }).analyze();
  const blocking = results.violations
    .filter((v) => v.impact === 'serious' || v.impact === 'critical')
    .map((v) => ({ id: v.id, nodes: v.nodes.slice(0, 3).map((n) => n.target.join(' ')) }));
  expect(blocking).toEqual([]);
}

function primaryNav(page: Page) {
  return page.getByRole('navigation', { name: 'Primary' });
}

async function signInAs(page: Page, user: 'viewer' | 'operator') {
  const password = passwords[user];
  if (!password) throw new Error(`KEYCLOAK_DEMO_${user.toUpperCase()}_PASSWORD is not set`);
  await page.getByRole('button', { name: 'Sign in' }).first().click();
  await page.locator('#username').fill(user);
  await page.locator('#password').fill(password);
  await page.locator('#kc-login').click();
}

test.describe('anonymous shell', () => {
  test.beforeEach(async ({ page }) => {
    await withoutBackend(page);
  });

  test('AC-1 shows the Network group and "Sign in" only', async ({ page }) => {
    const errors = collectErrors(page);
    await page.goto('/map');
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Live map');
    await expect(primaryNav(page).getByRole('link')).toHaveText(['Live map', 'Stops', 'Alerts']);
    if (!previewOnly) await expect(page.getByRole('button', { name: 'Sign in' })).toBeVisible();
    await expectNoSeriousViolations(page);
    expect(errors).toEqual([]);
  });

  test('AC-8 uses bottom tabs on a 375 px screen without horizontal scrolling', async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 740 });
    await page.goto('/map');
    const tabs = page.getByRole('navigation', { name: 'Primary' });
    await expect(tabs.getByRole('link', { name: 'Map' })).toHaveAttribute('aria-current', 'page');
    await expect(tabs.getByRole('button', { name: 'More' })).toBeVisible();
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
    expect(overflow).toBeLessThanOrEqual(0);
    await expectNoSeriousViolations(page);
  });

  test('⌘K opens the search with the pages the user may open', async ({ page }) => {
    await page.goto('/map');
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Live map');
    await page.keyboard.press('ControlOrMeta+k');
    const dialog = page.getByRole('dialog');
    await expect(dialog.getByPlaceholder('Search pages, routes and stops')).toBeFocused();
    await expect(dialog.getByRole('option', { name: 'Alerts' })).toBeVisible();
    await expect(dialog.getByRole('option', { name: 'Overview' })).toHaveCount(0);
    await page.keyboard.press('Escape');
    await expect(dialog).toBeHidden();
  });

  test('shows the API outage instead of a blank page', async ({ page }) => {
    test.skip(!previewOnly, 'The compose stack has a running API');
    await page.goto('/alerts');
    await expect(page.getByRole('status').filter({ hasText: "Can't check data freshness right now." })).toBeVisible();
  });
});

test.describe('signing in (Keycloak)', () => {
  test.skip(previewOnly || !passwords.viewer || !passwords.operator, 'Needs the compose stack and the demo passwords');

  test('E2E-SHELL-01 menus by role, with axe at every step', async ({ page }) => {
    await page.goto('/map');
    await expect(primaryNav(page).getByRole('link')).toHaveText(['Live map', 'Stops', 'Alerts']);
    await expectNoSeriousViolations(page);

    await signInAs(page, 'viewer');
    await page.goto('/');
    await expect(page).toHaveURL(/\/overview$/);
    await expect(primaryNav(page).getByRole('link', { name: 'Scorecard' })).toBeVisible();
    await expect(primaryNav(page).getByText('Read-only')).toBeVisible();
    await expect(primaryNav(page).getByRole('link', { name: 'Demo' })).toHaveCount(0);
    await expectNoSeriousViolations(page);

    await page.getByRole('button', { name: 'Account menu' }).click();
    await page.getByRole('menuitem', { name: 'Sign out' }).click();
    await expect(page.getByRole('button', { name: 'Sign in' })).toBeVisible();

    await signInAs(page, 'operator');
    await page.goto('/ops/jobs');
    await expect(primaryNav(page).getByRole('link', { name: /^Pipeline/ })).toHaveAttribute('aria-current', 'page');
    await expect(primaryNav(page).getByText('Read-only')).toHaveCount(0);
    await expectNoSeriousViolations(page);
  });

  test('E2E-SHELL-02 returns to the page after sign-in and survives a reload', async ({ page }) => {
    await page.goto('/ops/dlq?severity=2');
    await expect(page.getByRole('heading', { name: 'Sign in to view this page' })).toBeVisible();
    await signInAs(page, 'viewer');
    await expect(page).toHaveURL(/\/ops\/dlq\?severity=2$/);
    await expect(page.getByText('Viewer', { exact: true })).toBeVisible();

    await page.reload();
    await expect(page.getByText('Viewer', { exact: true })).toBeVisible();
    // Tokens stay in memory (DOC-27 §3.2, SEC-14).
    const stored = await page.evaluate(() => JSON.stringify(window.localStorage));
    expect(stored).not.toContain('access_token');
  });

  // Turning the pause flag on and off goes through the Controls screen, which P5-11 builds.
  test.fixme('E2E-SHELL-03 banner and "Paused" follow the pause flag', () => undefined);
});
