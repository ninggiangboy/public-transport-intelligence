import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Page } from '@playwright/test';

import { collectErrors, previewOnly, withoutBackend } from './support';

// DOC-36 screens/live-map §10. MapLibre draws on a canvas, so the tests reach vehicles through the list view and the
// URL rather than by clicking map features.

async function expectNoSeriousViolations(page: Page) {
  const results = await new AxeBuilder({ page }).exclude('.maplibregl-canvas').analyze();
  const blocking = results.violations
    .filter((v) => v.impact === 'serious' || v.impact === 'critical')
    .map((v) => ({ id: v.id, nodes: v.nodes.slice(0, 3).map((n) => n.target.join(' ')) }));
  expect(blocking).toEqual([]);
}

test.describe('without the API', () => {
  test.skip(!previewOnly, 'The compose stack has a running API');

  test('keeps the map on screen and says the vehicles could not load', async ({ page }) => {
    const errors = collectErrors(page);
    await withoutBackend(page);
    await page.goto('/map');
    await expect(page.getByRole('region', { name: 'Map controls' })).toBeVisible();
    await expect(page.getByText("Couldn't load vehicles")).toBeVisible();
    await expect(page.getByRole('button', { name: 'Retry' })).toBeVisible();
    // `vite preview` serves no /tiles/: the plain background and the note of ADR-0021.
    await expect(page.getByText('Base map unavailable. Run `make tiles` to download it.')).toBeVisible();
    await expectNoSeriousViolations(page);
    expect(errors.filter((error) => !/WebGL|tiles/i.test(error))).toEqual([]);
  });
});

test.describe('against the stack', () => {
  test.skip(previewOnly, 'Needs the compose stack (E2E_BASE_URL) with the simulator emitting');

  test('E2E-MAP-01 anonymous: vehicles within 3 s, a route filter, a bus, the list view, with axe', async ({
    page,
  }) => {
    const errors = collectErrors(page);
    await page.goto('/map');
    // AC-1: the summary counts vehicles once the snapshot is in.
    await expect(page.getByText(/^\d+ vehicles?$/)).toBeVisible({ timeout: 3_000 });
    await expectNoSeriousViolations(page);

    // AC-3: a route from the picker lands on the URL and filters the vehicles.
    await page.getByRole('button', { name: 'Routes' }).click();
    await page.getByRole('searchbox', { name: 'Search routes' }).fill('18');
    await page.getByRole('checkbox').first().check();
    await page.keyboard.press('Escape');
    await expect(page).toHaveURL(/route=/);
    await expect(page.getByText(/vehicles? on 1 route$/)).toBeVisible();

    // AC-9: the list holds what the vehicle panel shows, and opens a bus on the map.
    await page.getByRole('button', { name: 'List view' }).click();
    await expect(page).toHaveURL(/view=list/);
    const table = page.getByRole('table', { name: 'Vehicles by route' });
    await expect(table).toBeVisible();
    await expectNoSeriousViolations(page);
    await table.getByRole('rowheader').getByRole('button').first().click();
    await expect(page).toHaveURL(/vehicle=/);
    const panel = page.getByRole('complementary', { name: 'Vehicle' });
    await expect(panel.getByRole('region', { name: 'Trip progress' })).toBeVisible();
    await expect(panel.getByText(/^Bus .+ · now$/)).toBeVisible();
    expect(errors).toEqual([]);
  });

  test('E2E-MAP-03 falls back to polling when the event stream is blocked', async ({ page }) => {
    await page.route('**/api/v1/stream**', (route) => route.abort());
    await page.goto('/map?view=list');
    // AC-7: "Polling" after 5 s, and the snapshot still refreshes every 5 s.
    await expect(page.getByText('Polling').first()).toBeVisible({ timeout: 8_000 });
    let polls = 0;
    page.on('request', (request) => {
      if (request.url().includes('/api/v1/vehicles/live')) polls += 1;
    });
    await page.waitForTimeout(12_000);
    expect(polls).toBeGreaterThanOrEqual(2);
  });

  // Need a scenario started through the simulator (/sim/**), which the demo control screen (P5-13) wraps, and for
  // E2E-MAP-02 a signed-in operator; the suggestion itself arrives in P6.
  test.fixme('E2E-MAP-02 bunching: toast, "Show on map", the panel, then Accept', () => undefined);
  test.fixme('E2E-MAP-04 disruption: the public toast and "Show" open the panel', () => undefined);
});
