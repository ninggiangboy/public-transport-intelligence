import AxeBuilder from '@axe-core/playwright';
import { expect, type Page } from '@playwright/test';

// Shared by the Demo control cases (DOC-36 screens/demo-control §10). They need the compose stack started with the
// demo profile (`make up-demo`: env.js has demoControl and the API has the proxy E-90) and the demo passwords of
// deploy/compose/.env.

export const passwords = {
  viewer: process.env.KEYCLOAK_DEMO_VIEWER_PASSWORD,
  operator: process.env.KEYCLOAK_DEMO_OPERATOR_PASSWORD,
};

export async function signInAs(page: Page, user: 'viewer' | 'operator') {
  await page.getByRole('button', { name: 'Sign in' }).first().click();
  await page.locator('#username').fill(user);
  await page.locator('#password').fill(passwords[user] ?? '');
  await page.locator('#kc-login').click();
}

/** True when the stack runs the demo profile: env.js turns Demo control on. */
export async function demoProfileOn(page: Page): Promise<boolean> {
  const response = await page.request.get('/env.js');
  return /demoControl:\s*true/.test(await response.text());
}

export async function expectNoSeriousViolations(page: Page) {
  const results = await new AxeBuilder({ page }).analyze();
  const blocking = results.violations
    .filter((v) => v.impact === 'serious' || v.impact === 'critical')
    .map((v) => ({ id: v.id, nodes: v.nodes.slice(0, 3).map((n) => n.target.join(' ')) }));
  expect(blocking).toEqual([]);
}

/** Opens the start dialog of a scenario card and returns it. */
export async function openStart(page: Page, title: string) {
  const library = page.getByRole('region', { name: 'Scenarios' });
  await library.getByRole('article', { name: title }).getByRole('button', { name: 'Start scenario' }).click();
  const dialog = page.getByRole('dialog', { name: `Start ${title}?` });
  await expect(dialog).toBeVisible();
  return dialog;
}

/** Picks route `routeId` in the dialog's route field (RouteSelect, one route). */
export async function pickRoute(page: Page, dialog: ReturnType<Page['getByRole']>, shortName: string) {
  await dialog.getByRole('button', { name: 'Route' }).click();
  const list = page.getByRole('dialog', { name: 'Route' });
  await list.getByRole('searchbox').fill(shortName);
  await list.getByRole('checkbox').first().click();
}
