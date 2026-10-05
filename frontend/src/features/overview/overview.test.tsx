import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { beforeEach, describe, expect, it } from 'vitest';

import { getFreshness, getJobSummary, getOtpScorecard, listLiveVehicles } from '@/api/generated/examples';
import type { ResponseBody } from '@/api/types';
import { periodRanges, pulseRoutes, ratesBySource, routesToWatch } from '@/features/overview/model';
import { en } from '@/i18n/en';
import { overviewCopy } from '@/i18n/overview';
import { networkOtp, otpByDay } from '@/lib/otp';
import { mswPath, respond } from '@/test/handlers';
import { renderRoute } from '@/test/render';
import { server } from '@/test/server';

type Otp = ResponseBody<'/api/v1/insights/otp', 'get', 200>;
type OtpItem = Otp['items'][number];

function first<T>(items: readonly T[]): T {
  const [item] = items;
  if (item === undefined) throw new Error('the example has no item');
  return item;
}

const route18: OtpItem = first(getOtpScorecard.examples['seven days'].items);
const vehicle = first(
  listLiveVehicles.examples[Object.keys(listLiveVehicles.examples)[0] as keyof typeof listLiveVehicles.examples].items,
);

function otpItem(routeId: string, onTime: number, observations: number, daily: OtpItem['daily'] = []): OtpItem {
  return {
    ...route18,
    routeId,
    onTimeCount: onTime,
    observationCount: observations,
    otpPercentage: (onTime * 100) / observations,
    daily,
  };
}

let otpRequests: URL[] = [];
beforeEach(() => {
  otpRequests = [];
});

/** E-14 answering `current` for the period that ends yesterday and `previous` for the one before it. */
function serveOtp(current: OtpItem[], previous: OtpItem[]) {
  server.use(
    http.get(mswPath('/api/v1/insights/otp'), ({ request }) => {
      const url = new URL(request.url);
      otpRequests.push(url);
      const latest = otpRequests
        .map((r) => r.searchParams.get('toDate') ?? '')
        .sort()
        .at(-1);
      const items = url.searchParams.get('toDate') === latest ? current : previous;
      return HttpResponse.json({
        fromDate: url.searchParams.get('fromDate'),
        toDate: url.searchParams.get('toDate'),
        items,
      });
    }),
  );
}

describe('Overview page', () => {
  it('AC-1 shows the four KPIs to a viewer coming from /', async () => {
    serveOtp([route18], [route18]);
    const { router } = await renderRoute('/', { as: 'viewer' });
    await waitFor(() => {
      expect(router.state.location.pathname).toBe('/overview');
    });
    expect(await screen.findByRole('heading', { level: 1, name: overviewCopy.overview.title })).toBeInTheDocument();

    const vehicles = await screen.findByRole('link', { name: new RegExp(overviewCopy.overview.kpi.vehicles) });
    expect(vehicles).toHaveTextContent(overviewCopy.overview.kpi.onRoutes(1, '1'));
    expect(await screen.findByRole('link', { name: new RegExp(overviewCopy.overview.kpi.otp) })).toHaveTextContent(
      '78.4%',
    );
    expect(await screen.findByRole('link', { name: new RegExp(overviewCopy.overview.kpi.alerts) })).toHaveTextContent(
      overviewCopy.overview.kpi.bySeverity(0, 1, 0),
    );
    expect(
      await within(screen.getByRole('main')).findByRole('link', {
        name: new RegExp(overviewCopy.overview.kpi.deadLetters),
      }),
    ).toHaveTextContent('214');
  });

  it('AC-3 adds up the counters of the period and compares with the period before', async () => {
    serveOtp(
      [otpItem('18', 600, 1000), otpItem('21', 900, 1000)],
      [otpItem('18', 700, 1000), otpItem('21', 700, 1000)],
    );
    await renderRoute('/overview?period=7d', { as: 'viewer' });
    const kpi = await screen.findByRole('link', { name: new RegExp(overviewCopy.overview.kpi.otp) });
    // (600 + 900) / 2000 = 75 %, against 70 %: up 5 points.
    await waitFor(() => {
      expect(kpi).toHaveTextContent('75.0%');
    });
    expect(kpi).toHaveTextContent(overviewCopy.overview.kpi.points('5.0'));
    const ranges = otpRequests.map((url) => [url.searchParams.get('fromDate'), url.searchParams.get('toDate')]);
    // businessNow of the examples is Sep 29: the period ends on Sep 28.
    expect(ranges).toContainEqual(['2026-09-22', '2026-09-28']);
    expect(ranges).toContainEqual(['2026-09-15', '2026-09-21']);
  });

  it('lists the routes to watch, lowest on-time first, linking to the scorecard', async () => {
    serveOtp([otpItem('18', 900, 1000), otpItem('21', 600, 1000)], []);
    await renderRoute('/overview', { as: 'viewer' });
    const card = within(await screen.findByRole('region', { name: overviewCopy.overview.watch.title }));
    const rows = await card.findAllByRole('link', { name: /%/ });
    expect(rows[0]).toHaveAttribute('href', '/scorecard?route=21');
    expect(rows[0]).toHaveTextContent('60.0%');
  });

  it('AC-5 marks a stale source in the data pipeline and leaves the others alone', async () => {
    serveOtp([route18], [route18]);
    server.use(
      respond('get', '/api/v1/system/freshness', {
        ...getFreshness.examples.fresh,
        sources: [
          ...getFreshness.examples.fresh.sources,
          {
            source: 'TICKETING_SALES',
            lastEventAt: '2026-09-29T21:01:00Z',
            ageSeconds: 1080,
            staleAfterSeconds: 900,
            stale: true,
          },
        ],
      }),
      respond(
        'get',
        '/api/v1/etl/jobs/summary',
        getJobSummary.examples[Object.keys(getJobSummary.examples)[0] as keyof typeof getJobSummary.examples],
      ),
    );
    await renderRoute('/overview', { as: 'viewer' });
    const card = within(await screen.findByRole('region', { name: overviewCopy.overview.pipeline.title }));
    const sales = await card.findByText(overviewCopy.overview.pipeline.noData('18 min'));
    expect(sales).toHaveClass('text-tone-warning-fg');
    expect(card.getByRole('link', { name: new RegExp(overviewCopy.overview.pipeline.vehicles) })).not.toHaveTextContent(
      /No data/,
    );
    expect(await card.findByText(overviewCopy.overview.pipeline.failed(1))).toBeInTheDocument();
  });

  it('says nothing needs attention when no alert is open', async () => {
    serveOtp([route18], [route18]);
    server.use(respond('get', '/api/v1/alerts', { items: [] }));
    await renderRoute('/overview', { as: 'viewer' });
    expect(await screen.findByText(overviewCopy.overview.attention.emptyTitle)).toBeInTheDocument();
  });

  it('shows route 18 in the network pulse with a link to the live map', async () => {
    serveOtp([route18], [route18]);
    await renderRoute('/overview', { as: 'viewer' });
    const pulse = within(await screen.findByRole('region', { name: overviewCopy.overview.pulse.title }));
    expect(await pulse.findByRole('link', { name: overviewCopy.overview.pulse.route('18', 1) })).toHaveAttribute(
      'href',
      '/map?route=18',
    );
  });

  it('switches the period and offers the chart as a table', async () => {
    serveOtp(
      [otpItem('18', 80, 100, [{ serviceDate: '2026-09-27', otpPercentage: 75, observationCount: 100 }])],
      [route18],
    );
    const { router } = await renderRoute('/overview', { as: 'viewer' });
    await userEvent.click(await screen.findByRole('radio', { name: overviewCopy.overview.period['30d'] }));
    await waitFor(() => {
      expect(router.state.location.href).toBe('/overview?period=30d');
    });
    const chart = within(await screen.findByRole('region', { name: overviewCopy.overview.otpChart.title }));
    await userEvent.click(await chart.findByRole('button', { name: en.common.viewAsTable }));
    expect(chart.getByRole('table')).toHaveTextContent('75.0%');
  });

  it('asks anonymous users to sign in and loads nothing', async () => {
    await renderRoute('/overview');
    expect(await screen.findByRole('heading', { name: en.states.noAccess.signInTitle })).toBeInTheDocument();
    expect(otpRequests).toEqual([]);
  });
});

describe('Overview numbers', () => {
  it('takes periods that end yesterday, and a 7-day chart for "Yesterday"', () => {
    expect(periodRanges('1d', '2026-09-29')).toEqual({
      current: { from: '2026-09-28', to: '2026-09-28' },
      previous: { from: '2026-09-27', to: '2026-09-27' },
      chart: { from: '2026-09-22', to: '2026-09-28' },
      chartPrevious: { from: '2026-09-15', to: '2026-09-21' },
    });
    expect(periodRanges('30d', '2026-10-01').current).toEqual({ from: '2026-09-01', to: '2026-09-30' });
  });

  it('weights on-time performance by observations', () => {
    const items = [otpItem('a', 50, 100), otpItem('b', 900, 1000)];
    expect(networkOtp(items)).toBeCloseTo(86.36, 2);
    expect(networkOtp([])).toBeUndefined();
    expect(routesToWatch(items, 1).map((item) => item.routeId)).toEqual(['a']);
  });

  it('adds the routes up per day', () => {
    const items = [
      otpItem('a', 1, 1, [{ serviceDate: '2026-09-28', otpPercentage: 50, observationCount: 100 }]),
      otpItem('b', 1, 1, [{ serviceDate: '2026-09-28', otpPercentage: 100, observationCount: 300 }]),
    ];
    expect(otpByDay(items)).toEqual([{ date: '2026-09-28', otp: 87.5 }]);
  });

  it('puts bunched and disrupted routes first in the pulse, then the busiest', () => {
    const at = (routeId: string, extra = {}) => ({
      ...vehicle,
      routeId,
      vehicleId: `${routeId}-${Math.random()}`,
      bunching: undefined,
      ...extra,
    });
    const vehicles = [at('5'), at('5'), at('5'), at('6'), at('6'), at('7', { bunching: vehicle.bunching }), at('9')];
    expect(pulseRoutes(vehicles, ['9'], 3)).toEqual(['7', '9', '5']);
  });

  it('reads the throughput of the newest whole minute', () => {
    const summary =
      getJobSummary.examples[Object.keys(getJobSummary.examples)[0] as keyof typeof getJobSummary.examples];
    const rates = ratesBySource(summary);
    expect(rates.get('GTFS_RT_VEHICLE_POSITION')).toBeGreaterThan(0);
  });
});
