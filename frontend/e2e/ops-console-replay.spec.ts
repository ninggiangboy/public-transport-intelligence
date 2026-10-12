import { expect, test } from '@playwright/test';

import { collectErrors, previewOnly } from './support';
import { fillReplay, MINUTE, passwords } from './replay-support';

// DOC-36 screens/ops-console-replay §10. The case needs the compose stack with Keycloak (E2E_BASE_URL) and the demo
// operator password of deploy/compose/.env. E2E-REPLAY-01 and -02 wait for older raw zone data: e2e/late/.

test.skip(previewOnly || !passwords.operator, 'Needs the compose stack and the demo operator password');

test('E2E-REPLAY-03 an end less than 10 minutes ago is refused before any request', async ({ page }) => {
  const errors = collectErrors(page);
  const created: string[] = [];
  page.on('request', (request) => {
    if (request.method() === 'POST' && new URL(request.url()).pathname === '/api/v1/etl/replays') {
      created.push(request.url());
    }
  });
  const now = Date.now();
  const form = await fillReplay(page, 'Trip updates', now - 60 * MINUTE, now - 5 * MINUTE);

  // AC-2.
  await expect(form.getByText('End must be at least 10 minutes ago, so all raw files are written.')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Start replay' })).toBeDisabled();
  expect(created).toEqual([]);
  expect(errors).toEqual([]);
});
