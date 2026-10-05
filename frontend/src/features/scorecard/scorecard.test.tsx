import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { getOtpScorecard, listDisruptionEpisodes } from '@/api/generated/examples';
import type { ResponseBody } from '@/api/types';
import {
  heatCells,
  modeOf,
  presetOf,
  previousRange,
  rangeInstants,
  resolveRange,
  scorecardCsv,
  sortItems,
  tolerances,
  totals,
} from '@/features/scorecard/model';
import { en } from '@/i18n/en';
import { scorecardCopy } from '@/i18n/scorecard';
import { mswPath, respond, respondProblem } from '@/test/handlers';
import { renderRoute } from '@/test/render';
import { server } from '@/test/server';

type Otp = ResponseBody<'/api/v1/insights/otp', 'get', 200>;
type OtpItem = Otp['items'][number];
type RouteItem = ResponseBody<'/api/v1/routes', 'get', 200>['items'][number];

const copy = scorecardCopy.scorecard;

function first<T>(items: readonly T[]): T {
  const [item] = items;
  if (item === undefined) throw new Error('the example has no item');
  return item;
}

const route18: OtpItem = first(getOtpScorecard.examples['seven days'].items);

function otpItem(routeId: string, onTime: number, observations: number, extra: Partial<OtpItem> = {}): OtpItem {
  return {
    ...route18,
    routeId,
    onTimeCount: onTime,
    earlyCount: Math.round(observations * 0.02),
    lateCount: observations - onTime - Math.round(observations * 0.02),
    observationCount: observations,
    otpPercentage: (onTime * 100) / observations,
    tripCount: 100,
    daily: [
      { serviceDate: '2026-09-27', otpPercentage: 70, observationCount: observations / 2 },
      { serviceDate: '2026-09-28', otpPercentage: 80, observationCount: observations / 2 },
    ],
    ...extra,
  };
}

const ROUTES: RouteItem[] = [
  { routeId: '18', displayName: '18', longName: 'Nicollet Ave', routeType: 3, sortOrder: 18, color: '0053A0' },
  { routeId: '21', displayName: '21', longName: 'Lake St – Selby Ave', routeType: 3, sortOrder: 21 },
  { routeId: '901', displayName: 'Blue', longName: 'METRO Blue Line', routeType: 0, sortOrder: 1 },
];

let otpRequests: URL[] = [];
beforeEach(() => {
  otpRequests = [];
  server.use(respond('get', '/api/v1/routes', { feedVersionId: 3, items: ROUTES }));
});

/** E-14 answering `current` for the newest range asked for and `previous` for older ones. */
function serveOtp(current: OtpItem[], previous: OtpItem[] = current, headers?: Record<string, string>) {
  server.use(
    http.get(mswPath('/api/v1/insights/otp'), ({ request }) => {
      const url = new URL(request.url);
      otpRequests.push(url);
      const routeIds = url.searchParams.getAll('routeId');
      const latest = otpRequests
        .map((r) => r.searchParams.get('toDate') ?? '')
        .sort()
        .at(-1);
      const items = (url.searchParams.get('toDate') === latest ? current : previous).filter(
        (item) => routeIds.length === 0 || routeIds.includes(item.routeId),
      );
      return HttpResponse.json(
        { fromDate: url.searchParams.get('fromDate'), toDate: url.searchParams.get('toDate'), items },
        { headers: { 'X-Data-As-Of': '2026-09-29T08:00:00Z', ...headers } },
      );
    }),
  );
}

function rows() {
  const table = screen.getByRole('table', { name: /On-time performance by route/ });
  return within(table).getAllByRole('row').slice(1);
}

function rowAt(list: HTMLElement[], index: number): HTMLElement {
  const row = list[index];
  if (row === undefined) throw new Error(`no row ${index}`);
  return row;
}

describe('Route scorecard', () => {
  it('AC-1 ranks worst first with a trend per route, and pools the counters for System on-time', async () => {
    serveOtp([otpItem('18', 900, 1000), otpItem('21', 600, 1000)]);
    await renderRoute('/scorecard', { as: 'viewer' });
    expect(await screen.findByRole('heading', { level: 1, name: copy.title })).toBeInTheDocument();
    await waitFor(() => {
      expect(rows()).toHaveLength(2);
    });
    const worst = rowAt(rows(), 0);
    expect(worst).toHaveTextContent('Lake St – Selby Ave');
    expect(worst).toHaveTextContent('60.0%');
    expect(rowAt(rows(), 1)).toHaveTextContent('90.0%');
    expect(within(worst).getByRole('img', { name: copy.trend('21') })).toBeInTheDocument();
    // (900 + 600) / 2000.
    expect(screen.getByText(copy.kpi.systemOtp).parentElement).toHaveTextContent('75.0%');
    // Sep 29 is businessNow of the examples: the default range is the 7 days ending Sep 28.
    expect(otpRequests.map((url) => url.searchParams.get('fromDate'))).toContain('2026-09-22');
    expect(otpRequests.map((url) => url.searchParams.get('toDate'))).toContain('2026-09-28');
    expect(screen.getByText(/On time means no more than 5 min early or 5 min late/)).toBeInTheDocument();
  });

  it('AC-2 writes a new range to the URL, which opens the same table', async () => {
    serveOtp([otpItem('18', 900, 1000)]);
    const { router } = await renderRoute('/scorecard', { as: 'viewer' });
    const user = userEvent.setup();
    await user.click(await screen.findByRole('radio', { name: copy.preset.month }));
    await waitFor(() => {
      expect(router.state.location.search).toMatchObject({ from: '2026-08-29', to: '2026-09-28' });
    });
    await user.click(screen.getByRole('button', { name: /Aug 29, 2026/ }));
    await user.clear(screen.getByLabelText(en.timeRange.from));
    await user.type(screen.getByLabelText(en.timeRange.from), '2026-09-15');
    await user.click(screen.getByRole('button', { name: en.timeRange.apply }));
    await waitFor(() => {
      expect(router.state.location.href).toBe('/scorecard?from=2026-09-15&to=2026-09-28');
    });
    await waitFor(() => {
      expect(otpRequests.at(-1)?.searchParams.get('toDate')).toBeDefined();
    });
    expect(otpRequests.some((url) => url.searchParams.get('fromDate') === '2026-09-15')).toBe(true);
  });

  it('AC-5 cuts a 60-day range to the 31 days that end on `to`', async () => {
    serveOtp([otpItem('18', 900, 1000)]);
    await renderRoute('/scorecard?from=2026-07-01&to=2026-08-30', { as: 'viewer' });
    expect(await screen.findByText(copy.clamped('Jul 31, 2026', 'Aug 30, 2026'))).toBeInTheDocument();
    await waitFor(() => {
      expect(otpRequests.some((url) => url.searchParams.get('fromDate') === '2026-07-31')).toBe(true);
    });
  });

  it('AC-6 warns when the on-time window changed during the period', async () => {
    serveOtp([
      otpItem('18', 900, 1000, {
        earlyToleranceSeconds: undefined,
        lateToleranceSeconds: undefined,
        mixedTolerances: true,
      }),
    ]);
    await renderRoute('/scorecard', { as: 'viewer' });
    expect(await screen.findByText(copy.mixed)).toBeInTheDocument();
    expect(screen.queryByText(/On time means no more than/)).not.toBeInTheDocument();
  });

  it('filters by mode, counting the routes of each', async () => {
    serveOtp([otpItem('18', 900, 1000), otpItem('21', 600, 1000), otpItem('901', 950, 1000)]);
    const { router } = await renderRoute('/scorecard', { as: 'viewer' });
    const rail = await screen.findByRole('radio', { name: copy.modeOption(copy.mode.rail, '1') });
    expect(screen.getByRole('radio', { name: copy.modeOption(copy.mode.bus, '2') })).toBeInTheDocument();
    await userEvent.click(rail);
    await waitFor(() => {
      expect(router.state.location.href).toBe('/scorecard?routeType=0,1,2');
    });
    expect(rows()).toHaveLength(1);
    expect(rows()[0]).toHaveTextContent('METRO Blue Line');
  });

  it('AC-7 opens the route drawer from a row, and "Open route details" keeps the range', async () => {
    serveOtp([otpItem('18', 900, 1000), otpItem('21', 600, 1000)]);
    server.use(respond('get', '/api/v1/insights/disruption', listDisruptionEpisodes.examples.viewer));
    const { router } = await renderRoute('/scorecard?from=2026-09-15&to=2026-09-28', { as: 'viewer' });
    await waitFor(() => {
      expect(rows()).toHaveLength(2);
    });
    await userEvent.click(rowAt(rows(), 1));
    await waitFor(() => {
      expect(router.state.location.search).toMatchObject({ route: '18' });
    });
    const drawer = within(await screen.findByRole('dialog'));
    expect(drawer.getByText(copy.drawer.typicalDelay)).toBeInTheDocument();
    expect(await drawer.findByRole('img', { name: /Nicollet Ave & 46th St: average \+1 min 4 s/ })).toBeInTheDocument();
    expect(drawer.getByText(copy.drawer.disruptions)).toBeInTheDocument();
    expect(await drawer.findByText(/peak \+3 min 50 s/)).toBeInTheDocument();
    expect(drawer.getByRole('link', { name: copy.drawer.openDetails })).toHaveAttribute(
      'href',
      '/scorecard/18?from=2026-09-15&to=2026-09-28',
    );
    expect(drawer.getByRole('link', { name: copy.drawer.seeLive })).toHaveAttribute('href', '/map?route=18');
  });

  describe('AC-8 Export', () => {
    let saved: Blob | undefined;
    let fileName: string | undefined;
    beforeEach(() => {
      saved = undefined;
      URL.createObjectURL = vi.fn((blob: Blob) => {
        saved = blob;
        return 'blob:scorecard';
      });
      URL.revokeObjectURL = vi.fn();
      vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) {
        fileName = this.download;
      });
    });
    afterEach(() => {
      vi.restoreAllMocks();
    });

    it('saves the rows and columns of the filtered table as CSV', async () => {
      serveOtp([otpItem('18', 900, 1000), otpItem('21', 600, 1000), otpItem('901', 950, 1000)]);
      await renderRoute('/scorecard?routeType=3,11', { as: 'viewer' });
      await waitFor(() => {
        expect(rows()).toHaveLength(2);
      });
      await userEvent.click(screen.getByRole('button', { name: copy.export }));
      expect(fileName).toBe('scorecard-2026-09-22-2026-09-28.csv');
      if (!saved) throw new Error('nothing was saved');
      const lines = (await saved.text()).trimEnd().split('\r\n');
      expect(lines).toHaveLength(3);
      expect(lines[0]).toBe('#,Route,On time,Trend,Early,Late,Observations,Trips');
      expect(lines[1]).toBe('1,21 Lake St – Selby Ave,60,70 80,2,38,1000,100');
    });
  });

  it('asks anonymous users to sign in and loads nothing', async () => {
    serveOtp([]);
    await renderRoute('/scorecard');
    expect(await screen.findByRole('heading', { name: en.states.noAccess.signInTitle })).toBeInTheDocument();
    expect(otpRequests).toEqual([]);
  });

  it('says when there are no scores for the period', async () => {
    serveOtp([]);
    await renderRoute('/scorecard', { as: 'viewer' });
    expect(await screen.findByText(copy.empty.title)).toBeInTheDocument();
    expect(screen.getByText(copy.empty.body)).toBeInTheDocument();
  });
});

describe('Route details', () => {
  it('AC-3 draws the 7 × 24 heatmap and gives the same numbers as a table', async () => {
    serveOtp([otpItem('18', 900, 1000)]);
    const delays: URL[] = [];
    server.use(
      http.get(mswPath('/api/v1/routes/{routeId}/delays'), ({ request }) => {
        delays.push(new URL(request.url));
        return HttpResponse.json({
          routeId: '18',
          bucket: 'hour-of-week',
          from: '2026-09-22T05:00:00Z',
          to: '2026-09-29T05:00:00Z',
          earlyToleranceSeconds: 300,
          lateToleranceSeconds: 300,
          items: [
            {
              dayOfWeek: 2,
              hourOfDay: 16,
              avgDelaySeconds: 222,
              medianDelaySeconds: 180,
              p90DelaySeconds: 400,
              observationCount: 50,
              onTimePercentage: 70,
            },
          ],
        });
      }),
    );
    await renderRoute('/scorecard/18', { as: 'viewer' });
    const card = within(await screen.findByRole('region', { name: copy.delays.heatTitle }));
    await userEvent.click(await card.findByRole('button', { name: en.common.viewAsTable }));
    const table = card.getByRole('table');
    const tuesday = within(table).getByRole('row', { name: /^Tue/ });
    expect(tuesday).toHaveTextContent('+3 min 42 s');
    expect(within(table).getAllByRole('row')).toHaveLength(8);
    // The days of the range, from 00:00 agency time (CDT) to 00:00 after the last day.
    expect(delays[0]?.searchParams.get('from')).toBe('2026-09-22T05:00:00.000Z');
    expect(delays[0]?.searchParams.get('to')).toBe('2026-09-29T05:00:00.000Z');
    expect(delays[0]?.searchParams.get('bucket')).toBe('hour-of-week');
  });

  it('switches the view and direction through the URL', async () => {
    serveOtp([otpItem('18', 900, 1000)]);
    const { router } = await renderRoute('/scorecard/18', { as: 'viewer' });
    await userEvent.click(await screen.findByRole('radio', { name: copy.delays.views.day }));
    await waitFor(() => {
      expect(router.state.location.search).toMatchObject({ bucket: 'day' });
    });
    await userEvent.click(await screen.findByRole('radio', { name: 'NB' }));
    await waitFor(() => {
      expect(router.state.location.href).toBe('/scorecard/18?bucket=day&dir=0');
    });
  });

  it('AC-4 gives each stop its confidence, and stops without history read "Schedule only" with no numbers', async () => {
    serveOtp([otpItem('18', 900, 1000)]);
    await renderRoute('/scorecard/18?tab=profile&dow=2&hour=16', { as: 'viewer' });
    const table = await screen.findByRole('table', { name: /Typical delay at each stop/ });
    await within(table).findByText('Nicollet Ave & 44th St');
    const [, known, unknown] = within(table).getAllByRole('row');
    expect(known).toHaveTextContent('+1 min 4 s');
    expect(known).toHaveTextContent(en.confidence.eta.HIGH);
    expect(unknown).toHaveTextContent(en.confidence.eta.NONE);
    expect(unknown).not.toHaveTextContent(/\d+ (s|min)/);
    expect(screen.getByText(copy.profile.window('Sep 1, 2026', 'Sep 28, 2026'))).toBeInTheDocument();
  });

  it('lists the disruptions of the range and opens one, or says it is gone', async () => {
    serveOtp([otpItem('18', 900, 1000)]);
    server.use(
      respond('get', '/api/v1/insights/disruption', listDisruptionEpisodes.examples.viewer),
      // E-13 has an example for anonymous users only; a viewer sees the full episode, as in E-12.
      respond('get', '/api/v1/insights/disruption/{id}', first(listDisruptionEpisodes.examples.viewer.items)),
    );
    const { router } = await renderRoute('/scorecard/18?tab=disruptions', { as: 'viewer' });
    const table = await screen.findByRole('table', { name: copy.disruptions.caption('18') });
    await within(table).findByText('Traffic');
    const row = rowAt(within(table).getAllByRole('row'), 1);
    expect(row).toHaveTextContent(copy.disruptions.ongoing);
    expect(row).toHaveTextContent('Traffic');
    await userEvent.click(row);
    await waitFor(() => {
      expect(router.state.location.search).toMatchObject({ disruption: '9d8e7f6a-5b4c-5d3e-8f2a-1b0c9d8e7f6a' });
    });
    const drawer = within(await screen.findByRole('dialog'));
    expect(await drawer.findByText(copy.disruption.likelyCause)).toBeInTheDocument();
    expect(drawer.getByRole('link', { name: copy.disruption.showOnMap })).toHaveAttribute(
      'href',
      '/map?route=18&disruption=9d8e7f6a-5b4c-5d3e-8f2a-1b0c9d8e7f6a',
    );

    server.use(respondProblem('get', '/api/v1/insights/disruption/{id}', 404, 'not-found'));
    await renderRoute('/scorecard/18?tab=disruptions&disruption=gone', { as: 'viewer' });
    expect(await screen.findByText(copy.disruption.gone)).toBeInTheDocument();
  });

  it('says the route is not found', async () => {
    serveOtp([]);
    server.use(respondProblem('get', '/api/v1/routes/{routeId}', 404, 'not-found'));
    await renderRoute('/scorecard/nope', { as: 'viewer' });
    expect(await screen.findByRole('heading', { name: /Route not found/ })).toBeInTheDocument();
  });
});

describe('Scorecard numbers', () => {
  const routes = new Map(ROUTES.map((route) => [route.routeId, route]));

  it('defaults to 7 days ending yesterday and cuts ranges to 31 days', () => {
    expect(resolveRange({}, '2026-09-28')).toEqual({ from: '2026-09-22', to: '2026-09-28', clamped: false });
    expect(resolveRange({ from: '2026-07-01', to: '2026-08-30' }, '2026-09-28')).toEqual({
      from: '2026-07-31',
      to: '2026-08-30',
      clamped: true,
    });
    expect(resolveRange({ from: '2026-09-29', to: '2026-09-01' }, '2026-09-28').from).toBe('2026-08-26');
    expect(previousRange({ from: '2026-09-22', to: '2026-09-28' })).toEqual({ from: '2026-09-15', to: '2026-09-21' });
    expect(presetOf({ from: '2026-08-29', to: '2026-09-28' }, '2026-09-28')).toBe('month');
    expect(presetOf({ from: '2026-09-15', to: '2026-09-28' }, '2026-09-28')).toBeUndefined();
  });

  it('turns days into agency-time instants across a DST change', () => {
    expect(rangeInstants({ from: '2026-11-01', to: '2026-11-01' }, 'America/Chicago')).toEqual({
      from: '2026-11-01T05:00:00.000Z',
      to: '2026-11-02T06:00:00.000Z',
    });
  });

  it('adds counters up before dividing, and reads the on-time window', () => {
    const items = [otpItem('18', 50, 100), otpItem('21', 900, 1000)];
    expect(totals(items).otp).toBeCloseTo(86.36, 2);
    expect(totals([]).otp).toBeUndefined();
    expect(tolerances(items)).toEqual({ mixed: false, early: 300, late: 300 });
    expect(tolerances([otpItem('18', 1, 1, { lateToleranceSeconds: 120 }), ...items])).toEqual({ mixed: true });
  });

  it('sorts worst first or by route number, and reads the mode of a type list', () => {
    const items = [otpItem('21', 600, 1000), otpItem('18', 900, 1000), otpItem('901', 950, 1000)];
    expect(sortItems(items, 'otp', routes).map((item) => item.routeId)).toEqual(['21', '18', '901']);
    expect(sortItems(items, 'route', routes).map((item) => item.routeId)).toEqual(['901', '18', '21']);
    expect(modeOf([3, 11])).toBe('bus');
    expect(modeOf([0])).toBe('rail');
    expect(modeOf([3, 0])).toBe('all');
    expect(modeOf(undefined)).toBe('all');
  });

  it('writes CSV with plain numbers and maps hours of the week to cells', () => {
    const csv = scorecardCsv(['a'], [otpItem('18', 900, 1000)], routes);
    expect(csv.split('\r\n')[1]).toBe('1,18 Nicollet Ave,90,70 80,2,8,1000,100');
    expect(
      heatCells([
        {
          dayOfWeek: 7,
          hourOfDay: 23,
          avgDelaySeconds: 10,
          medianDelaySeconds: 1,
          p90DelaySeconds: 2,
          observationCount: 3,
          onTimePercentage: 4,
        },
      ]),
    ).toEqual([{ row: 6, col: 23, value: 10, count: 3 }]);
  });
});
