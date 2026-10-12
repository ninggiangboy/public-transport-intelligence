import { expect, type Page } from '@playwright/test';

// Shared by the Replay cases (DOC-36 screens/ops-console-replay §10): operator sign-in and the times of the form, which
// are typed in the agency's zone.

export const passwords = {
  viewer: process.env.KEYCLOAK_DEMO_VIEWER_PASSWORD,
  operator: process.env.KEYCLOAK_DEMO_OPERATOR_PASSWORD,
};

/** The zone of the active feed (E-60 `activeFeed.timezone`) of the demo data. */
export const AGENCY_ZONE = 'America/Chicago';
export const MINUTE = 60_000;

export async function signInAs(page: Page, user: 'viewer' | 'operator') {
  await page.getByRole('button', { name: 'Sign in' }).first().click();
  await page.locator('#username').fill(user);
  await page.locator('#password').fill(passwords[user] ?? '');
  await page.locator('#kc-login').click();
}

/** "2026-09-29T16:05": the value of a datetime-local input showing `ms` in the agency's zone. */
export function localInput(ms: number): string {
  const parts = Object.fromEntries(
    new Intl.DateTimeFormat('en-CA', {
      timeZone: AGENCY_ZONE,
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      hourCycle: 'h23',
    })
      .formatToParts(ms)
      .map((part) => [part.type, part.value]),
  );
  return `${parts.year}-${parts.month}-${parts.day}T${parts.hour}:${parts.minute}`;
}

/** Opens the form as an operator and fills the source and the record time range. */
export async function fillReplay(page: Page, source: string, from: number, to: number) {
  await page.goto('/ops/replay?tab=new');
  await signInAs(page, 'operator');
  const form = page.getByRole('form', { name: 'New replay' });
  await expect(form).toBeVisible();
  await form.getByRole('radio', { name: source }).click();
  await form.getByLabel('From').fill(localInput(from));
  await form.getByLabel('To').fill(localInput(to));
  return form;
}
