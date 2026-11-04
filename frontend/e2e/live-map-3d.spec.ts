import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Page } from '@playwright/test';

import { previewOnly, withoutBackend } from './support';

// DOC-36 screens/live-map §10, DR-111. The 3D view is a canvas, so positions are read through `window.__pti3d`, which
// MapCanvas sets only when the browser profile has `pti.e2e` = "1" in localStorage.

async function expectNoSeriousViolations(page: Page) {
  const results = await new AxeBuilder({ page }).exclude('.maplibregl-canvas').analyze();
  const blocking = results.violations
    .filter((v) => v.impact === 'serious' || v.impact === 'critical')
    .map((v) => ({ id: v.id, nodes: v.nodes.slice(0, 3).map((n) => n.target.join(' ')) }));
  expect(blocking).toEqual([]);
}

/** Opens the first vehicle of the list view; leaves the page on the map with `vehicle=` in the URL. */
async function openFirstVehicle(page: Page) {
  await page.goto('/map?view=list');
  const table = page.getByRole('table', { name: 'Vehicles by route' });
  await table.getByRole('rowheader').getByRole('button').first().click();
  await expect(page).toHaveURL(/vehicle=/);
  return new URL(page.url()).searchParams.get('vehicle') ?? '';
}

test.describe('E2E-MAP-07 without 3D', () => {
  test('a screen under 1024 px opens view=3d as the 2D map', async ({ page }) => {
    if (previewOnly) await withoutBackend(page);
    await page.setViewportSize({ width: 800, height: 800 });
    await page.goto('/map?view=3d');
    // AC-16: no "3D" button, the URL is not rewritten, the map is the 2D one.
    await expect(page.getByRole('region', { name: 'Map controls' })).toBeVisible();
    await expect(page.getByRole('radio', { name: '3D' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Tilt up' })).toHaveCount(0);
    expect(new URL(page.url()).searchParams.get('view')).toBe('3d');
  });
});

test.describe('against the stack', () => {
  test.skip(previewOnly, 'Needs the compose stack (E2E_BASE_URL) with the simulator emitting');

  test('E2E-MAP-05 switch to 3D, reload, Follow a vehicle, a drag returns to Free, with axe', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    const vehicle = await openFirstVehicle(page);
    expect(vehicle).not.toBe('');

    // AC-12: the URL carries the view and, after the camera settles, bearing and pitch.
    await page.getByRole('radio', { name: '3D' }).click();
    await expect(page).toHaveURL(/view=3d/);
    await expect(page).toHaveURL(/c=[-\d.]+,[-\d.]+,[\d.]+,-?\d+,\d+/, { timeout: 5_000 });
    await expect(page.getByRole('button', { name: 'Tilt up' })).toBeVisible();
    await page.reload();
    await expect(page).toHaveURL(/view=3d/);
    await expect(page.getByRole('radio', { name: '3D' })).toBeChecked();

    // AC-14: Follow keeps the vehicle; dragging the map switches the control back to Free.
    const panel = page.getByRole('complementary', { name: 'Vehicle' });
    await expect(panel.getByText('Camera')).toBeVisible();
    await panel.getByRole('radio', { name: 'Follow' }).click();
    await expect(panel.getByRole('radio', { name: 'Follow' })).toBeChecked();
    const box = await page.locator('.maplibregl-canvas').boundingBox();
    if (!box) throw new Error('no map canvas');
    await page.mouse.move(box.x + 200, box.y + 300);
    await page.mouse.down();
    await page.mouse.move(box.x + 280, box.y + 340, { steps: 6 });
    await page.mouse.up();
    await expect(panel.getByRole('radio', { name: 'Free' })).toBeChecked();
    await expectNoSeriousViolations(page);
  });

  for (const reduced of [false, true]) {
    test(`E2E-MAP-06 motion between fixes, ${reduced ? 'reduced motion' : 'default'}`, async ({ page }) => {
      test.setTimeout(90_000);
      await page.emulateMedia({ reducedMotion: reduced ? 'reduce' : 'no-preference' });
      await page.setViewportSize({ width: 1440, height: 900 });
      await page.addInitScript(() => {
        localStorage.setItem('pti.e2e', '1');
      });
      const vehicle = await openFirstVehicle(page);
      await page.getByRole('radio', { name: '3D' }).click();
      await page.waitForFunction((id) => Boolean(window.__pti3d?.screenPosition(id)), vehicle, { timeout: 20_000 });

      // 30 s of frames: with motion the marker moves a little every frame and never by more than 5 px (AC-13); with
      // reduced motion it moves only on a fix (AC-15), so most frames are still.
      const stats = await page.evaluate(
        (id) =>
          new Promise<{ frames: number; moving: number; maxJump: number }>((resolve) => {
            let last: { x: number; y: number } | undefined;
            let frames = 0;
            let moving = 0;
            let maxJump = 0;
            const start = performance.now();
            const tick = () => {
              const at = window.__pti3d?.screenPosition(id);
              if (at && last) {
                const jump = Math.hypot(at.x - last.x, at.y - last.y);
                frames += 1;
                if (jump > 0.01) moving += 1;
                maxJump = Math.max(maxJump, jump);
              }
              if (at) last = at;
              if (performance.now() - start < 30_000) requestAnimationFrame(tick);
              else resolve({ frames, moving, maxJump });
            };
            requestAnimationFrame(tick);
          }),
        vehicle,
      );
      expect(stats.frames).toBeGreaterThan(500);
      if (reduced) expect(stats.moving / stats.frames).toBeLessThan(0.05);
      else {
        expect(stats.maxJump).toBeLessThanOrEqual(5);
        expect(stats.moving / stats.frames).toBeGreaterThan(0.5);
      }
    });
  }
});

declare global {
  interface Window {
    __pti3d?: { screenPosition: (vehicleId: string) => { x: number; y: number } | undefined };
  }
}
