import { screen, waitFor, within } from '@testing-library/react';
import axe from 'axe-core';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';

import type { AppEnv } from '@/env';
import { meHandler, renderRoute, type TestUser } from '@/test/render';
import { server } from '@/test/server';

// P5-14 (DOC-37 §2): no screen is left blank when the API is down. The page keeps its title and shows an error where
// its data would be, whether the server answers 503 or cannot be reached at all.
const SCREENS: { path: string; as: TestUser; env?: Partial<AppEnv> }[] = [
  { path: '/overview', as: 'viewer' },
  { path: '/map', as: 'anonymous' },
  { path: '/map?view=list', as: 'anonymous' },
  // /stops without a query asks the API nothing, so it has no failure to show; with one it searches.
  { path: '/stops?q=ma', as: 'anonymous' },
  { path: '/stops/S1', as: 'anonymous' },
  { path: '/alerts', as: 'anonymous' },
  { path: '/scorecard', as: 'viewer' },
  { path: '/scorecard/R1', as: 'viewer' },
  { path: '/ops/jobs', as: 'viewer' },
  { path: '/ops/batches/B1', as: 'viewer' },
  { path: '/ops/dlq', as: 'viewer' },
  { path: '/ops/replay', as: 'operator' },
  { path: '/ops/ticketing', as: 'viewer' },
  { path: '/ops/controls', as: 'viewer' },
  { path: '/ops/demo', as: 'operator', env: { demoControl: true } },
];

const DEAD = {
  'answers 503': () =>
    http.all('*/api/v1/*', () =>
      HttpResponse.json(
        { type: 'urn:pti:problem:service-unavailable', title: 'Service Unavailable', status: 503 },
        { status: 503, headers: { 'Content-Type': 'application/problem+json' } },
      ),
    ),
  'is unreachable': () => http.all('*/api/v1/*', () => HttpResponse.error()),
};

describe.each(Object.entries(DEAD))('when the API %s', (_name, dead) => {
  it.each(SCREENS)('$path ($as) is not blank', async ({ path, as, env }) => {
    server.use(dead());
    // The session is known from the token; /me and everything else is down.
    await renderRoute(path, { as, env });
    const main = await screen.findByRole('main');
    await waitFor(() => {
      expect(within(main).getAllByRole('heading').length).toBeGreaterThan(0);
      expect(main.textContent.trim().length).toBeGreaterThan(20);
    });
    // Settled: nothing is still loading, and the failure is said out loud somewhere on the page.
    await waitFor(() => {
      expect(main.querySelector('[aria-busy="true"]')).toBeNull();
      expect(within(main).queryAllByRole('alert').length).toBeGreaterThan(0);
    });
    // The error pages keep the accessibility bar of the working ones (NFR-11): no serious or critical violation.
    const results = await axe.run(document.body, {
      rules: { 'color-contrast': { enabled: false }, region: { enabled: false } },
    });
    expect(results.violations.filter((v) => v.impact === 'serious' || v.impact === 'critical')).toEqual([]);
  });
});

describe('when only the data is down and the session is fine', () => {
  it.each(SCREENS.filter((s) => s.as !== 'anonymous'))('$path ($as) is not blank', async ({ path, as, env }) => {
    server.use(http.all('*/api/v1/*', () => HttpResponse.error()));
    server.use(meHandler(as as 'viewer' | 'operator'));
    await renderRoute(path, { as, env });
    const main = await screen.findByRole('main');
    await waitFor(() => {
      expect(main.querySelector('[aria-busy="true"]')).toBeNull();
      expect(within(main).queryAllByRole('alert').length).toBeGreaterThan(0);
    });
    expect(main.textContent.trim().length).toBeGreaterThan(20);
  });
});
