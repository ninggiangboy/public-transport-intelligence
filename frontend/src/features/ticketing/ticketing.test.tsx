import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';

import { getFreshness } from '@/api/generated/examples';
import { keys } from '@/api/keys';
import type { Anomaly } from '@/features/ticketing/model';
import { alertsCopy } from '@/i18n/alerts';
import { ticketingCopy } from '@/i18n/ticketing';
import { mswPath, respond, respondProblem } from '@/test/handlers';
import { renderRoute } from '@/test/render';
import { server } from '@/test/server';

const copy = ticketingCopy;
const ID = (n: number) => `c1d2e3f4-a5b6-5c7d-8e9f-0a1b2c3d4e5${String(n)}`;

function anomaly(n: number, over: Partial<Anomaly> = {}): Anomaly {
  return {
    id: ID(n),
    salePointId: `SP-014${String(n)}`,
    salePointName: `Nicollet Mall kiosk ${String(n)}`,
    windowStart: '2026-09-29T21:00:00Z',
    windowEnd: '2026-09-29T21:15:00Z',
    detectedAt: '2026-09-29T21:15:00Z',
    trigger: 'REFUND_RATIO',
    txnCount: 25,
    refundCount: 12,
    refundRatio: 0.48,
    amountSum: 50,
    baselineMean: 14.2,
    baselineStddev: 3.1,
    zScore: 3.48,
    enrichmentStatus: 'PENDING',
    ...over,
  };
}

/** E-15 answering `rows()` at each call, recording the query strings. */
function serveList(rows: () => Anomaly[]) {
  const calls: URL[] = [];
  server.use(
    http.get(mswPath('/api/v1/insights/ticketing-anomalies'), ({ request }) => {
      calls.push(new URL(request.url));
      return HttpResponse.json({ items: rows() }, { headers: { 'X-Data-As-Of': '2026-09-29T21:18:51Z' } });
    }),
  );
  return calls;
}

const table = () => screen.findByRole('table', { name: copy.table.caption });

/** The row of a sale point, once the list has loaded. */
async function rowOf(salePoint: string) {
  const row = (await screen.findByRole('button', { name: salePoint })).closest('tr');
  if (!row) throw new Error(`no row for ${salePoint}`);
  return row;
}

describe('Ticketing anomalies (DOC-36 screens/ops-console-ticketing)', () => {
  it('lists anomalies with their trigger, signal and AI verdict, and opens the detail (§4, §6.1)', async () => {
    serveList(() => [
      anomaly(1, { category: 'fraud_suspect', categoryConfidence: 0.77, severity: 2, severityConfidence: 0.69 }),
    ]);
    server.use(
      respond('get', '/api/v1/insights/ticketing-anomalies/{id}', {
        ...anomaly(1, { category: 'fraud_suspect', categoryConfidence: 0.77, modelVersion: 'jev@0.2.0', severity: 2 }),
        summary: {
          salePointKind: 'KIOSK',
          currency: 'USD',
          voidCount: 2,
          ticketTypes: { SINGLE: 20, DAY: 5, MONTH: 0 },
          saleAmount: { min: 2.5, max: 5 },
          baseline: { kind: 'SEASONAL', windows: 80, mean: 14.2, stddev: 3.1, zScore: 3.48 },
          previousWindows: [
            { windowStart: '2026-09-29T20:00:00Z', txnCount: 13, refundCount: 0 },
            { windowStart: '2026-09-29T20:15:00Z', txnCount: 11, refundCount: 0 },
            { windowStart: '2026-09-29T20:30:00Z', txnCount: 14, refundCount: 1 },
            { windowStart: '2026-09-29T20:45:00Z', txnCount: 12, refundCount: 0 },
          ],
          thresholds: { volumeZ: 3, volumeMinTxn: 20, refundRatio: 0.3, refundMinCount: 5 },
        },
      }),
    );
    const { router } = await renderRoute('/ops/ticketing', { as: 'viewer' });

    expect(await screen.findByRole('heading', { level: 1, name: copy.title })).toBeInTheDocument();
    const row = await rowOf('Nicollet Mall kiosk 1');
    expect(within(row).getByText('Nicollet Mall kiosk 1')).toBeInTheDocument();
    expect(within(row).getByText(alertsCopy.ticketingTrigger.REFUND_RATIO ?? '')).toBeInTheDocument();
    expect(within(row).getByText('refund 48%')).toBeInTheDocument();
    expect(within(row).getByText('z 3.48')).toBeInTheDocument();
    expect(within(row).getByText('Possible fraud')).toBeInTheDocument();
    expect(screen.getByText(copy.detail.select)).toBeInTheDocument();

    await userEvent.click(row);
    await waitFor(() => {
      expect(router.state.location.search).toMatchObject({ anomaly: ID(1) });
    });
    const detail = await screen.findByRole('article', { name: copy.detail.label });
    expect(
      within(detail).getByRole('heading', { name: 'High refund rate at Nicollet Mall kiosk 1' }),
    ).toBeInTheDocument();
    expect(within(detail).getByText(copy.detail.previous(4))).toBeInTheDocument();
    expect(
      within(detail).getByRole('img', { name: copy.detail.previousLabel('13, 11, 14, 12, 25') }),
    ).toBeInTheDocument();
    expect(
      within(detail).getByText(copy.detail.normal('14.2', '3.1', 'same hour on similar days', 80)),
    ).toBeInTheDocument();
    expect(within(detail).getByText('Model jev@0.2.0')).toBeInTheDocument();
    expect(within(detail).getByText('Single ride 20')).toBeInTheDocument();
    expect(within(detail).getByText('Amount $50.00 · $2.50–$5.00 per sale')).toBeInTheDocument();
    expect(within(detail).getByText('Voided sales: 2')).toBeInTheDocument();
    expect(within(detail).getByRole('button', { name: copy.detail.copyLink })).toBeInTheDocument();
  });

  it('changes "Unclassified" into the category in place when triage classifies it (AC-2)', async () => {
    let classified = false;
    serveList(() => [
      classified
        ? anomaly(1, { category: 'promo_spike', categoryConfidence: 0.91, enrichmentStatus: 'DONE' })
        : anomaly(1),
    ]);
    const { queryClient } = await renderRoute('/ops/ticketing', { as: 'viewer' });
    const row = await rowOf('Nicollet Mall kiosk 1');
    expect(within(row).getByText(alertsCopy.ticketingCategory.unclassified ?? '')).toBeInTheDocument();

    classified = true;
    // What the alert.updated handler does (realtime/handlers.ts).
    await queryClient.invalidateQueries({ queryKey: keys.insights.ticketingAll() });
    await waitFor(() => {
      expect(within(row).getByText('Promotion spike')).toBeInTheDocument();
    });
    expect(within(row).queryByText(alertsCopy.ticketingCategory.unclassified ?? '')).not.toBeInTheDocument();
  });

  it('marks a confidence under 0.6 as low (AC-3)', async () => {
    serveList(() => [anomaly(1, { category: 'system_error', categoryConfidence: 0.42 })]);
    await renderRoute('/ops/ticketing', { as: 'viewer' });
    const row = await rowOf('Nicollet Mall kiosk 1');
    expect(within(row).getByText('Low confidence (42%)')).toHaveClass('text-tone-warning-fg');
  });

  it('says when ticket sales stopped, here and nowhere else (AC-4)', async () => {
    const fresh = getFreshness.examples.fresh;
    server.use(
      respond('get', '/api/v1/system/freshness', {
        ...fresh,
        sources: [
          ...fresh.sources,
          {
            source: 'TICKETING_SALES',
            lastEventAt: '2026-09-29T21:01:35Z',
            ageSeconds: 1080,
            staleAfterSeconds: 900,
            stale: true,
          },
        ],
      }),
    );
    serveList(() => []);
    const { router } = await renderRoute('/ops/ticketing', { as: 'viewer' });
    expect(await screen.findByText(copy.stale.title('18 min'))).toBeInTheDocument();
    expect(screen.getByText(copy.stale.body)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: copy.stale.check })).toHaveAttribute('href', '/ops/jobs?kind=STREAM');
    expect(screen.getByText(copy.chart.noData('18 min'))).toBeInTheDocument();

    await router.navigate({ to: '/alerts' });
    await waitFor(() => {
      expect(screen.queryByText(copy.stale.body)).not.toBeInTheDocument();
    });
  });

  it('keeps category=unclassified in the URL and sends it to the API (AC-5)', async () => {
    const calls = serveList(() => [anomaly(1)]);
    const { router } = await renderRoute('/ops/ticketing?category=unclassified', { as: 'viewer' });
    await table();
    await waitFor(() => {
      expect(calls.some((url) => url.searchParams.getAll('category').includes('unclassified'))).toBe(true);
    });
    expect(router.state.location.search).toMatchObject({ category: 'unclassified' });
    expect(screen.getByRole('button', { name: /Category: Unclassified/ })).toBeInTheDocument();
  });

  it('filters by the sale point of a row and offers "Clear filters" when nothing matches (§6, §7)', async () => {
    let rows = [anomaly(1), anomaly(2)];
    const calls = serveList(() => rows);
    const { router } = await renderRoute('/ops/ticketing', { as: 'viewer' });
    await rowOf('Nicollet Mall kiosk 2');

    rows = [];
    await userEvent.click(screen.getByRole('button', { name: 'Nicollet Mall kiosk 2' }));
    await waitFor(() => {
      expect(router.state.location.search).toMatchObject({ salePoint: 'SP-0142' });
    });
    expect(router.state.location.search).not.toHaveProperty('anomaly');
    await waitFor(() => {
      expect(calls.at(-1)?.searchParams.get('salePointId')).toBe('SP-0142');
    });
    expect(await screen.findByText(copy.empty.filtered)).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: copy.filters.clearFilters }));
    await waitFor(() => {
      expect(router.state.location.search).not.toHaveProperty('salePoint');
    });
  });

  it('says so when nothing was detected, and when an anomaly is gone (§7, §6)', async () => {
    serveList(() => []);
    server.use(respondProblem('get', '/api/v1/insights/ticketing-anomalies/{id}', 404, 'not-found'));
    await renderRoute(`/ops/ticketing?anomaly=${ID(9)}`, { as: 'viewer' });
    expect(await screen.findByText(copy.empty.title)).toBeInTheDocument();
    expect(screen.getByText(copy.empty.description)).toBeInTheDocument();
    expect(await screen.findByText(copy.detail.gone)).toBeInTheDocument();
  });

  it('asks E-15 for the window ending at businessNow, not at the machine clock (§2)', async () => {
    const calls = serveList(() => []);
    await renderRoute('/ops/ticketing?window=6h', { as: 'viewer' });
    await waitFor(() => {
      expect(
        calls.some(
          (url) =>
            url.searchParams.get('to')?.startsWith('2026-09-29T21:19') &&
            url.searchParams.get('from')?.startsWith('2026-09-29T15:19'),
        ),
      ).toBe(true);
    });
  });
});
