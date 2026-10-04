import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { afterEach, describe, expect, it } from 'vitest';

import { getDisruptionEpisode, getStop, listStopArrivals } from '@/api/generated/examples';
import type { ResponseBody } from '@/api/types';
import { RECENT_KEY, SAVED_KEY } from '@/features/stops/stop-lists';
import { en } from '@/i18n/en';
import { mswPath, respond, respondProblem } from '@/test/handlers';
import { renderRoute } from '@/test/render';
import { server } from '@/test/server';

type Arrivals = ResponseBody<'/api/v1/stops/{stopId}/arrivals', 'get', 200>;
type Stop = ResponseBody<'/api/v1/stops/{stopId}', 'get', 200>;

const stop = getStop.examples.stop;
const arrivalsExample = listStopArrivals.examples.arrivals;
const [highTrip] = arrivalsExample.items;
if (!highTrip) throw new Error('example has no arrival');

const route46 = {
  routeId: '46',
  displayName: '46',
  color: 'E07B2E',
  textColor: 'FFFFFF',
  headsigns: ['46th St Station'],
};

function arrivals(items: Arrivals['items'], overrides: Partial<Arrivals> = {}): Arrivals {
  return { ...arrivalsExample, items, ...overrides };
}

// 21:19:35Z is 4:19 PM in Chicago; the example trip arrives at 4:25 PM, 5 min later.
const scheduleOnly = {
  ...highTrip,
  tripId: 'trip-schedule',
  confidence: 'NONE',
  sampleCount: 0,
  predictedDelaySeconds: 0,
  predictedArrival: '2026-09-29T22:30:00Z',
  scheduledArrival: '2026-09-29T22:30:00Z',
};
const route46Trip = {
  ...highTrip,
  tripId: 'trip-46',
  routeId: '46',
  directionId: 1,
  headsign: '46th St Station',
  confidence: 'MEDIUM',
  sampleCount: 14,
  predictedDelaySeconds: -130,
  predictedArrival: '2026-09-29T21:31:00Z',
  scheduledArrival: '2026-09-29T21:33:10Z',
};

afterEach(() => {
  window.localStorage.clear();
});

async function departures() {
  return within(await screen.findByRole('region', { name: en.stops.departures.title }));
}

describe('stop detail', () => {
  it('AC-1 lists departures with ETA, time and confidence; schedule-only trips say so', async () => {
    server.use(
      respond('get', '/api/v1/stops/{stopId}/arrivals', arrivals([highTrip, scheduleOnly]), {
        headers: { 'X-Data-As-Of': '2026-09-29T21:05:12Z' },
      }),
    );
    await renderRoute('/stops/51405');

    expect(await screen.findByRole('heading', { level: 1, name: stop.name })).toBeInTheDocument();
    const rows = await (await departures()).findAllByRole('listitem');
    expect(rows).toHaveLength(2);
    const [first, second] = rows as [HTMLElement, HTMLElement];
    expect(first).toHaveAccessibleName(
      'Route 18 to Downtown Minneapolis, in 5 minutes, 4:25 PM, 1 minute late, high confidence based on 36 trips',
    );
    expect(within(first).getByText(en.format.unit.min).parentElement).toHaveTextContent('5min');
    expect(within(first).getByText(en.confidence.eta.HIGH)).toBeInTheDocument();
    // In the status line and as the confidence of the trip.
    expect(within(second).getAllByText(en.stops.departures.scheduleOnly)).toHaveLength(2);
    expect(within(second).getByText('5:30')).toBeInTheDocument();
    expect(await (await departures()).findByText(/^Predictions as of Sep 29, 4:05 PM CDT$/)).toBeInTheDocument();
  });

  it('shows the stop plate, code, step-free and a link to the map', async () => {
    await renderRoute('/stops/51405');
    await screen.findByRole('heading', { level: 1, name: stop.name });
    expect(screen.getByText(en.stops.header.code('51405'))).toBeInTheDocument();
    expect(screen.getByText(en.stops.header.stepFree)).toBeInTheDocument();
    expect(screen.getByText(en.stops.header.routes(1))).toBeInTheDocument();
    expect(screen.getByRole('link', { name: en.stops.header.viewOnMap })).toHaveAttribute(
      'href',
      '/map?c=-93.2780,44.9204,16',
    );
    expect(document.title).toBe(en.page.title(stop.name));
  });

  it('AC-3 shows a disruption with its delay from the episode and a link to it', async () => {
    await renderRoute('/stops/51405');
    const [disruption] = stop.activeDisruptions;
    if (!disruption) throw new Error('example has no disruption');
    expect(await screen.findByText(disruption.title)).toBeInTheDocument();
    // 212.7 s is about 4 min; the episode started at 3:58 PM CDT.
    expect(await screen.findByText(en.stops.disruption.body('4 min', '3:58 PM'))).toBeInTheDocument();
    expect(screen.getByRole('link', { name: en.stops.disruption.seeAlert })).toHaveAttribute(
      'href',
      `/map?route=18&disruption=${disruption.disruptionId}`,
    );
  });

  it('says the route is back to normal when the disruption ends', async () => {
    const { queryClient } = await renderRoute('/stops/51405');
    await screen.findByText(en.stops.disruption.body('4 min', '3:58 PM'));

    server.use(
      respond('get', '/api/v1/stops/{stopId}', { ...stop, activeDisruptions: [] }),
      respond('get', '/api/v1/insights/disruption/{id}', {
        ...getDisruptionEpisode.examples.anonymous,
        status: 'CLOSED',
        episodeEnd: '2026-09-29T21:30:00Z',
      }),
    );
    await queryClient.invalidateQueries({ queryKey: ['stops'] });
    expect(await screen.findByText(en.stops.disruption.backToNormal('18'))).toBeInTheDocument();
  });

  it('drops a retracted disruption without a message', async () => {
    const { queryClient } = await renderRoute('/stops/51405');
    const [disruption] = stop.activeDisruptions;
    await screen.findByText(disruption?.title ?? '');

    server.use(
      respond('get', '/api/v1/stops/{stopId}', { ...stop, activeDisruptions: [] }),
      respondProblem('get', '/api/v1/insights/disruption/{id}', 404, 'not-found'),
    );
    await queryClient.invalidateQueries({ queryKey: ['stops'] });
    await waitFor(() => {
      expect(screen.queryByText(disruption?.title ?? '')).not.toBeInTheDocument();
    });
    expect(screen.queryByText(en.stops.disruption.backToNormal('18'))).not.toBeInTheDocument();
  });

  it('AC-5 says "Stop not found" with a search box for an unknown stop', async () => {
    server.use(respondProblem('get', '/api/v1/stops/{stopId}', 404, 'not-found'));
    await renderRoute('/stops/does-not-exist');
    expect(await screen.findByRole('heading', { level: 1, name: en.stops.notFound.title })).toBeInTheDocument();
    expect(screen.getByRole('searchbox', { name: en.stops.find.label })).toBeInTheDocument();
  });

  it('keeps the header and the disruption when the departures fail', async () => {
    server.use(respondProblem('get', '/api/v1/stops/{stopId}/arrivals', 503, 'service-unavailable'));
    await renderRoute('/stops/51405');
    expect(await (await departures()).findByText(en.error.couldntLoad(en.stops.departures.panel))).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 1, name: stop.name })).toBeInTheDocument();
    expect(screen.getByText(stop.activeDisruptions[0]?.title ?? '')).toBeInTheDocument();
  });

  it('says when no trips are coming', async () => {
    server.use(respond('get', '/api/v1/stops/{stopId}/arrivals', arrivals([])));
    await renderRoute('/stops/51405');
    expect(await screen.findByText(en.stops.departures.emptyTitle)).toBeInTheDocument();
  });

  it('AC-7 filters by route and direction, and writes them to the URL', async () => {
    const twoRoutes: Stop = { ...stop, routes: [...stop.routes, route46] };
    server.use(
      respond('get', '/api/v1/stops/{stopId}', twoRoutes),
      respond('get', '/api/v1/stops/{stopId}/arrivals', arrivals([highTrip, route46Trip])),
    );
    const { router } = await renderRoute('/stops/51405');
    await (await departures()).findByRole('listitem', { name: /^Route 46/ });

    await userEvent.click(screen.getByRole('button', { name: en.route.label('18') }));
    await waitFor(() => {
      expect(router.state.location.href).toBe('/stops/51405?route=18');
    });
    expect((await departures()).getAllByRole('listitem')).toHaveLength(1);
    expect((await departures()).queryByRole('listitem', { name: /^Route 46/ })).not.toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: en.stops.departures.all }));
    // Direction labels come from E-02 (route 18 direction 0 is "NB").
    await userEvent.click(await screen.findByRole('radio', { name: en.stops.direction.labels.NB }));
    await waitFor(() => {
      expect(router.state.location.href).toBe('/stops/51405?dir=0');
    });
    expect((await departures()).getAllByRole('listitem')).toHaveLength(1);
  });

  it('asks for 30 departures on "Show later departures"', async () => {
    const limits: (string | null)[] = [];
    const ten = Array.from({ length: 10 }, (_, index) => ({ ...highTrip, tripId: `trip-${index}` }));
    server.use(
      http.get(mswPath('/api/v1/stops/{stopId}/arrivals'), ({ request }) => {
        limits.push(new URL(request.url).searchParams.get('limit'));
        return HttpResponse.json(arrivals(ten));
      }),
    );
    await renderRoute('/stops/51405');
    await userEvent.click(await screen.findByRole('button', { name: en.stops.departures.showLater }));
    await waitFor(() => {
      expect(limits).toContain('30');
    });
  });

  it('saves the stop and remembers it as recent', async () => {
    await renderRoute('/stops/51405');
    const save = await screen.findByRole('button', { name: en.stops.header.save });
    expect(save).toHaveAttribute('aria-pressed', 'false');
    await userEvent.click(save);
    expect(screen.getByRole('button', { name: en.stops.header.saved })).toHaveAttribute('aria-pressed', 'true');
    expect(JSON.parse(window.localStorage.getItem(SAVED_KEY) ?? '[]')).toEqual([
      { stopId: '51405', name: stop.name, code: '51405' },
    ]);
    expect(JSON.parse(window.localStorage.getItem(RECENT_KEY) ?? '[]')).toHaveLength(1);
  });

  it('shows how reliable each route is here at this hour', async () => {
    await renderRoute('/stops/51405');
    expect(await screen.findByRole('region', { name: en.stops.reliability.title })).toBeInTheDocument();
    expect(screen.getByText(en.stops.reliability.when('Tuesday', '4 PM'))).toBeInTheDocument();
    expect(screen.getByText(en.stops.reliability.stats('+1 min 4 s', '+2 min 50 s'))).toBeInTheDocument();
  });

  it('hides "Reliability here" when the profiles fail', async () => {
    server.use(respondProblem('get', '/api/v1/routes/{routeId}/delay-profile', 503, 'service-unavailable'));
    await renderRoute('/stops/51405');
    await (await departures()).findAllByRole('listitem');
    expect(screen.queryByRole('region', { name: en.stops.reliability.title })).not.toBeInTheDocument();
  });
});

describe('find a stop', () => {
  it('AC-6 searches from two characters, writes q to the URL and opens a result', async () => {
    const { router } = await renderRoute('/stops');
    expect(await screen.findByRole('heading', { level: 1, name: en.stops.find.title })).toBeInTheDocument();
    await userEvent.type(screen.getByRole('searchbox', { name: en.stops.find.label }), 'nicollet');
    await waitFor(() => {
      expect(router.state.location.search).toEqual({ q: 'nicollet' });
    });
    const result = await screen.findByRole('link', { name: /Nicollet Ave & 46th St/ });
    await userEvent.click(result);
    await waitFor(() => {
      expect(router.state.location.pathname).toBe('/stops/51405');
    });
    const recent = JSON.parse(window.localStorage.getItem(RECENT_KEY) ?? '[]') as unknown[];
    expect(recent[0]).toMatchObject({ stopId: '51405' });
  });

  it('says when no stop matches', async () => {
    server.use(respond('get', '/api/v1/stops', { items: [] }));
    await renderRoute('/stops?q=zzzz');
    expect(await screen.findByText(en.stops.find.noMatchTitle('zzzz'))).toBeInTheDocument();
  });

  it('lists saved and recent stops while the box is empty, and clears the recent ones', async () => {
    window.localStorage.setItem(SAVED_KEY, JSON.stringify([{ stopId: '51405', name: 'Nicollet Ave & 46th St' }]));
    window.localStorage.setItem(RECENT_KEY, JSON.stringify([{ stopId: '17864', name: 'Lake St & 1st Ave' }]));
    await renderRoute('/stops');
    expect(await screen.findByRole('heading', { name: en.stops.find.saved })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Lake St & 1st Ave/ })).toHaveAttribute('href', '/stops/17864');
    await userEvent.click(screen.getByRole('button', { name: en.stops.find.clear }));
    expect(screen.queryByRole('heading', { name: en.stops.find.recent })).not.toBeInTheDocument();
  });
});
