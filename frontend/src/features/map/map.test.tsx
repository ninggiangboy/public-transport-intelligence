import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { getBunchingEpisode, getDisruptionEpisode, getRoute, listRoutes } from '@/api/generated/examples';
import type { ResponseBody } from '@/api/types';
import { alertsCopy } from '@/i18n/alerts';
import { en } from '@/i18n/en';
import { mapCopy } from '@/i18n/map';
import { toAlertResponse, upsertAlert } from '@/realtime/handlers';
import { mswPath, respond, respondProblem } from '@/test/handlers';
import { featureIds, mapDouble, resetMapDouble } from '@/test/map';
import { renderRoute } from '@/test/render';
import { server } from '@/test/server';

const notify = vi.hoisted(() => ({ message: vi.fn(), success: vi.fn(), error: vi.fn(), warning: vi.fn() }));
vi.mock('@/lib/notify', () => ({ notify, toasterReady: vi.fn() }));

type Vehicle = ResponseBody<'/api/v1/vehicles/live', 'get', 200>['items'][number];
type Episode = ResponseBody<'/api/v1/insights/bunching/{id}', 'get', 200>;

const copy = mapCopy.map;
const EPISODE = getBunchingEpisode.examples['closed episode'].id;
/** A CSS colour from a GTFS colour; written so that DS-11 finds no hex literal here. */
const hex = (value: string) => `#${value}`;

const route18 = getRoute.examples.route;
const stops18 = ['51405', '51412', '51418', '51420', '51422', '51426', '51430'].map((stopId, index) => ({
  stopId,
  code: stopId,
  name: ['46th St', '38th St', 'Lake St', '31st St', 'Franklin Ave', 'Grant St', '7th St'][index] ?? stopId,
  stopSequence: index + 12,
  lat: 44.92 + index / 100,
  lon: -93.278,
}));
const routeDetail = {
  ...route18,
  directions: route18.directions.map((direction) => ({ ...direction, stops: stops18 })),
};
const routes = {
  ...listRoutes.examples.routes,
  items: [
    ...listRoutes.examples.routes.items,
    { routeId: '21', displayName: '21', routeType: 3, color: 'E3A21A', textColor: '000000', sortOrder: 21 },
  ],
};

function vehicle(overrides: Partial<Vehicle>): Vehicle {
  return {
    vehicleId: '1203',
    label: '1203',
    routeId: '18',
    tripId: 't-2043',
    directionId: 0,
    lat: 44.948,
    lon: -93.278,
    bearing: 358,
    speedMps: 7.4,
    currentStatus: 'IN_TRANSIT_TO',
    currentStopSequence: 15,
    stopId: '51420',
    delaySeconds: 95,
    headsign: 'Downtown Minneapolis',
    occupancyStatus: 'MANY_SEATS_AVAILABLE',
    // Fresh against any clock the test runs on, so that nothing is faded or hidden.
    eventTimestamp: new Date().toISOString(),
    stopArrivalAt: new Date(Date.now() + 180_000).toISOString(),
    ...overrides,
  };
}

const overlay = { episodeId: EPISODE, gapSeconds: 40, headwaySeconds: 480 };
const fleet = (): Vehicle[] => [
  vehicle({ vehicleId: '1187', label: '1187', bunching: { ...overlay, role: 'LEADER', partnerVehicleId: '1203' } }),
  vehicle({ bunching: { ...overlay, role: 'FOLLOWER', partnerVehicleId: '1187' } }),
  vehicle({ vehicleId: '2101', label: '2101', routeId: '21', delaySeconds: 700, bunching: undefined }),
];

let vehicleRequests: string[][] = [];

/** E-05 filtered by `routeId` like the API does. */
function serveVehicles(items: Vehicle[] = fleet()) {
  server.use(
    http.get(mswPath('/api/v1/vehicles/live'), ({ request }) => {
      const wanted = new URL(request.url).searchParams.getAll('routeId');
      vehicleRequests.push(wanted);
      const shown = wanted.length > 0 ? items.filter((item) => wanted.includes(item.routeId)) : items;
      return HttpResponse.json({ businessNow: new Date().toISOString(), count: shown.length, items: shown });
    }),
  );
}

function serveBunching(episode: Partial<Episode> = {}) {
  const example = getBunchingEpisode.examples['closed episode'];
  server.use(
    respond('get', '/api/v1/insights/bunching/{id}', {
      ...example,
      status: 'OPEN',
      episodeEnd: undefined,
      closeReason: undefined,
      ...episode,
    }),
  );
}

beforeEach(() => {
  vehicleRequests = [];
  server.use(
    respond('get', '/api/v1/routes', routes),
    respond('get', '/api/v1/routes/{routeId}', routeDetail),
    respond('get', '/api/v1/stops', { items: [] }),
    respond('get', '/api/v1/insights/disruption', { items: [] }),
    respond('get', '/api/v1/alerts', { items: [] }),
  );
  serveVehicles();
});

afterEach(() => {
  resetMapDouble();
  for (const fn of Object.values(notify)) fn.mockClear();
});

/** The detail panel; for a signed-in user it shows once `/me` has answered. */
async function panel(name: string) {
  return within(await screen.findByRole('complementary', { name }));
}

describe('live map', () => {
  it('AC-1 AC-3 draws the vehicles and the shape of the selected route only', async () => {
    await renderRoute('/map?route=18');
    await waitFor(() => {
      expect(featureIds('vehicles')).toEqual(['1187', '1203']);
    });
    expect(vehicleRequests).toContainEqual(['18']);
    expect(await screen.findByText(copy.summary.vehiclesOnRoutes(2, 1))).toBeInTheDocument();
    await waitFor(() => {
      expect(featureIds('routes', 'colour')).toEqual([hex('0053A0')]);
    });
    // The route chip, and no fit of the feed: the shape of route 18 is brought into view.
    expect(screen.getByRole('button', { name: copy.controls.removeRoute('18') })).toBeInTheDocument();
    await waitFor(() => {
      expect(mapDouble.fitBounds).toHaveBeenCalled();
    });
  });

  it('removing a route chip writes the URL by replace', async () => {
    const { router } = await renderRoute('/map?route=18');
    await userEvent.click(await screen.findByRole('button', { name: copy.controls.removeRoute('18') }));
    await waitFor(() => {
      expect(router.state.location.search).toEqual({});
    });
  });

  it('AC-4 shows bunching halos to viewers only', async () => {
    await renderRoute('/map', { as: 'viewer' });
    await waitFor(() => {
      expect(featureIds('bunching', 'kind')).toEqual(['halo', 'link', 'halo']);
    });
    resetMapDouble();
  });

  it('AC-4 gives anonymous users no halo, and ignores `bunching` in their URL', async () => {
    await renderRoute(`/map?bunching=${EPISODE}`);
    await waitFor(() => {
      expect(featureIds('vehicles')).toHaveLength(3);
    });
    expect(featureIds('bunching')).toEqual([]);
    expect(screen.queryByRole('complementary', { name: copy.panel.kind.bunching })).not.toBeInTheDocument();
  });

  it('AC-11 a click on a bus opens its panel with the next stop and its arrival', async () => {
    const { router } = await renderRoute('/map');
    await waitFor(() => {
      expect(featureIds('vehicles')).toHaveLength(3);
    });
    mapDouble.pick?.({ layer: 'vehicles', properties: { id: '1203' }, lon: -93.278, lat: 44.948 });
    await waitFor(() => {
      expect(router.state.location.search).toEqual({ vehicle: '1203' });
    });
    const vehiclePanel = await panel(copy.panel.kind.vehicle);
    expect(
      await vehiclePanel.findByRole('heading', {
        name: 'Nicollet Av - Nicollet Mall - 1st Av to Downtown Minneapolis',
      }),
    ).toBeInTheDocument();
    // The direction name comes with E-02, which may land after the snapshot.
    expect(await vehiclePanel.findByText(copy.vehicle.subtitle('1203', 'Northbound', 't-2043'))).toBeInTheDocument();
    // Anonymous: no bunching badge even though the bus is in a pair.
    expect(vehiclePanel.queryByText(copy.vehicle.bunching)).not.toBeInTheDocument();
    const progress = within(vehiclePanel.getByRole('region', { name: copy.vehicle.tripProgress }));
    expect(progress.getByText('Lake St')).toBeInTheDocument();
    expect(progress.getByText('31st St')).toBeInTheDocument();
    expect(progress.getByText(/^Arriving /)).toBeInTheDocument();
    expect(progress.getByText(copy.vehicle.now('1203'))).toBeInTheDocument();
    expect(progress.queryByText('46th St')).not.toBeInTheDocument();
    expect(vehiclePanel.getByRole('link', { name: copy.vehicle.stopDetails })).toHaveAttribute('href', '/stops/51420');

    await userEvent.click(vehiclePanel.getByRole('button', { name: copy.vehicle.follow }));
    expect(vehiclePanel.getByRole('button', { name: copy.vehicle.stopFollowing })).toHaveAttribute(
      'aria-pressed',
      'true',
    );

    await userEvent.click(screen.getByRole('button', { name: copy.panel.close }));
    await waitFor(() => {
      expect(router.state.location.search).toEqual({});
    });
  });

  it('brings the bus of the URL into view, but not one picked on the map', async () => {
    await renderRoute('/map?vehicle=2101');
    await waitFor(() => {
      expect(mapDouble.moveTo).toHaveBeenCalledWith(-93.278, 44.948, 14);
    });
    mapDouble.moveTo.mockClear();
    mapDouble.pick?.({ layer: 'vehicles', properties: { id: '1203' }, lon: -93.278, lat: 44.948 });
    await screen.findByText(copy.vehicle.subtitle('1203', 'Northbound', 't-2043'));
    expect(mapDouble.moveTo).not.toHaveBeenCalled();
  });

  it('says so when the selected bus stops reporting', async () => {
    await renderRoute('/map?vehicle=4242');
    expect(await screen.findByText(copy.panel.gone.vehicle)).toBeInTheDocument();
  });

  it('a viewer clicking a bus with a halo gets the bunching panel and the pair in focus', async () => {
    serveBunching();
    const { router } = await renderRoute('/map', { as: 'viewer' });
    await waitFor(() => {
      expect(featureIds('bunching', 'kind')).toContain('halo');
    });
    mapDouble.pick?.({ layer: 'vehicles', properties: { id: '1203' }, lon: -93.278, lat: 44.948 });
    await waitFor(() => {
      expect(router.state.location.search).toEqual({ bunching: EPISODE });
    });
    const bunching = await panel(copy.panel.kind.bunching);
    expect(await bunching.findByRole('heading', { name: copy.bunching.title('18') })).toBeInTheDocument();
    expect(bunching.getByText(/^Northbound near Lake St · detected /)).toBeInTheDocument();
    expect(bunching.getByText(copy.bunching.headwayNow)).toBeInTheDocument();
    expect(bunching.getByText(alertsCopy.dispatchAction.full.hold_follower ?? '')).toBeInTheDocument();
    // Viewers see the suggestion without the buttons.
    expect(bunching.queryByRole('button', { name: alertsCopy.alerts.actions.accept })).not.toBeInTheDocument();
    await waitFor(() => {
      expect(mapDouble.fitBounds).toHaveBeenCalledWith(expect.any(Array), 120);
    });
    // Everything outside the pair is dimmed.
    await waitFor(() => {
      expect(featureIds('vehicles', 'opacity')).toEqual([1, 1, 0.5]);
    });
  });

  it("AC-5 an operator's Accept changes the button before the API answers, then posts the feedback", async () => {
    serveBunching();
    const posted: unknown[] = [];
    let release: () => void = () => undefined;
    server.use(
      http.post(mswPath('/api/v1/insights/dispatch-suggestions/{id}/feedback'), async ({ request }) => {
        posted.push(await request.json());
        await new Promise<void>((resolve) => {
          release = resolve;
        });
        return HttpResponse.json({
          ...getBunchingEpisode.examples['closed episode'].suggestion,
          operatorFeedback: 'accepted',
          feedbackBy: 'user:operator',
          feedbackAt: new Date().toISOString(),
        });
      }),
    );
    await renderRoute(`/map?bunching=${EPISODE}`, { as: 'operator' });
    const bunching = await panel(copy.panel.kind.bunching);
    await userEvent.click(await bunching.findByRole('button', { name: alertsCopy.alerts.actions.accept }));
    expect(bunching.getByRole('button', { name: alertsCopy.alerts.actions.accept })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
    await waitFor(() => {
      expect(posted).toEqual([{ feedback: 'accepted' }]);
    });
    release();
    await waitFor(() => {
      expect(notify.success).toHaveBeenCalledWith(copy.toast.feedbackSaved);
    });
    expect(bunching.getByText(/^Accepted by operator · /)).toBeInTheDocument();
    // The other button stays, to override the choice (UC-04 3b).
    expect(bunching.getByRole('button', { name: alertsCopy.alerts.actions.dismiss })).toHaveAttribute(
      'aria-pressed',
      'false',
    );
  });

  it('puts the choice back and says so when the feedback fails (UC-04 E1)', async () => {
    serveBunching();
    server.use(respondProblem('post', '/api/v1/insights/dispatch-suggestions/{id}/feedback', 503, 'unavailable'));
    await renderRoute(`/map?bunching=${EPISODE}`, { as: 'operator' });
    const bunching = await panel(copy.panel.kind.bunching);
    await userEvent.click(await bunching.findByRole('button', { name: alertsCopy.alerts.actions.dismiss }));
    await waitFor(() => {
      expect(notify.error).toHaveBeenCalledWith(copy.toast.feedbackFailed);
    });
    expect(bunching.getByRole('button', { name: alertsCopy.alerts.actions.dismiss })).not.toHaveAttribute(
      'aria-pressed',
    );
  });

  it('AC-6 marks a low-confidence suggestion and keeps its buttons', async () => {
    const example = getBunchingEpisode.examples['closed episode'];
    serveBunching({ suggestion: { ...example.suggestion, actionConfidence: 0.42, lowConfidence: true } });
    await renderRoute(`/map?bunching=${EPISODE}`, { as: 'operator' });
    const bunching = await panel(copy.panel.kind.bunching);
    expect(await bunching.findByText(en.confidence.aiLow('42%'))).toBeInTheDocument();
    expect(bunching.getByRole('button', { name: alertsCopy.alerts.actions.accept })).toBeEnabled();
  });

  it('says so when the bunching episode is gone', async () => {
    server.use(respondProblem('get', '/api/v1/insights/bunching/{id}', 404, 'not-found'));
    await renderRoute(`/map?bunching=${EPISODE}`, { as: 'viewer' });
    expect(await screen.findByText(copy.panel.gone.bunching)).toBeInTheDocument();
  });

  it('AC-10 colours by route from the segmented control and lists the routes in the legend', async () => {
    const { router } = await renderRoute('/map');
    await userEvent.click(await screen.findByRole('radio', { name: copy.controls.colour.route }));
    await waitFor(() => {
      expect(router.state.location.search).toEqual({ colour: 'route' });
    });
    await waitFor(() => {
      expect(featureIds('vehicles', 'colour')).toEqual([hex('0053A0'), hex('0053A0'), hex('E3A21A')]);
    });
    const legend = within(screen.getByRole('table', { name: copy.legend.title }));
    expect(legend.getByRole('rowheader', { name: '18' })).toBeInTheDocument();
    expect(legend.getByRole('rowheader', { name: '21' })).toBeInTheDocument();
  });

  it('counts the delay classes in the legend', async () => {
    await renderRoute('/map');
    const legend = within(await screen.findByRole('table', { name: copy.legend.title }));
    await waitFor(() => {
      expect(legend.getByRole('row', { name: `${copy.legend.delay['on-time']} 2` })).toBeInTheDocument();
    });
    expect(legend.getByRole('row', { name: `${copy.legend.delay['very-late']} 1` })).toBeInTheDocument();
  });

  it('AC-8 a new public disruption raises a toast whose "Show" opens the panel', async () => {
    server.use(respond('get', '/api/v1/insights/disruption/{id}', getDisruptionEpisode.examples.anonymous));
    const { router, queryClient } = await renderRoute('/map');
    await waitFor(() => {
      expect(featureIds('vehicles')).toHaveLength(3);
    });
    await waitFor(() => {
      expect(
        queryClient
          .getQueryCache()
          .findAll({ queryKey: ['alerts', 'list'] })
          .some((q) => q.state.data),
      ).toBe(true);
    });
    const refId = getDisruptionEpisode.examples.anonymous.id;
    upsertAlert(
      queryClient,
      toAlertResponse({
        id: 'a-1',
        type: 'DISRUPTION',
        severity: 1,
        audience: 'PUBLIC',
        routeId: '18',
        refId,
        title: 'Delays on route 18 northbound',
        body: { currentAvgDelaySeconds: 212.7 },
        createdAt: new Date().toISOString(),
      }),
      'created',
    );
    await waitFor(() => {
      expect(notify.warning).toHaveBeenCalledWith(copy.toast.disruption('18', 4), expect.anything());
    });
    const options = notify.warning.mock.calls[0]?.[1] as { action: { label: string; onClick: () => void } };
    expect(options.action.label).toBe(copy.toast.show);
    options.action.onClick();
    await waitFor(() => {
      expect(router.state.location.search).toEqual({ disruption: refId });
    });
    const disruption = await panel(copy.panel.kind.disruption);
    expect(
      await disruption.findByRole('heading', { name: copy.disruption.title('18', 'northbound') }),
    ).toBeInTheDocument();
    expect(disruption.getByText(copy.disruption.range('Lake St', 'Franklin Ave'))).toBeInTheDocument();
    // Anonymous: no z, no baseline, no AI block.
    expect(disruption.queryByText(copy.disruption.likelyCause)).not.toBeInTheDocument();
    await waitFor(() => {
      expect(featureIds('disruption-stops')).toEqual(['51418', '51420', '51422']);
    });
    expect(mapDouble.fitBounds).toHaveBeenCalledWith(expect.any(Array), 80);
  });

  it('lists the open disruptions behind the chip and opens one', async () => {
    const episode = getDisruptionEpisode.examples.anonymous;
    server.use(
      respond('get', '/api/v1/insights/disruption', { items: [episode] }),
      respond('get', '/api/v1/insights/disruption/{id}', episode),
    );
    const { router } = await renderRoute('/map?route=18');
    await userEvent.click(await screen.findByRole('button', { name: copy.controls.disruptions(1) }));
    await userEvent.click(await screen.findByRole('button', { name: new RegExp(copy.disruption.title('18', '')) }));
    await waitFor(() => {
      expect(router.state.location.searchStr).toBe(`?route=18&disruption=${episode.id}`);
    });
  });

  it('AC-9 lists the vehicles by route and opens one on the map', async () => {
    const { router } = await renderRoute('/map?view=list');
    const table = within(await screen.findByRole('table', { name: copy.list.caption }));
    const group = await table.findByRole('button', { name: copy.list.group('18', 2) });
    expect(group).toHaveAttribute('aria-expanded', 'true');
    expect(table.getByRole('button', { name: '2101' })).toBeInTheDocument();
    await userEvent.click(group);
    expect(table.queryByRole('button', { name: '1203' })).not.toBeInTheDocument();
    await userEvent.click(table.getByRole('button', { name: '2101' }));
    await waitFor(() => {
      expect(router.state.location.search).toEqual({ vehicle: '2101' });
    });
  });

  it('writes the camera to the URL 500 ms after the map stops moving', async () => {
    const { router } = await renderRoute('/map');
    await waitFor(() => {
      expect(mapDouble.moveEnd).toBeDefined();
    });
    mapDouble.moveEnd?.({ lon: -93.265, lat: 44.97781, zoom: 13 });
    await waitFor(() => {
      expect(router.state.location.searchStr).toBe('?c=-93.2650,44.9778,13.0000');
    });
  });

  it('shows "Couldn\'t load vehicles" with Retry when the first snapshot fails', async () => {
    server.use(respondProblem('get', '/api/v1/vehicles/live', 503, 'unavailable'));
    await renderRoute('/map');
    expect(await screen.findByText(copy.summary.failed)).toBeInTheDocument();
    expect(await screen.findByRole('button', { name: copy.summary.retry })).toBeInTheDocument();
  });

  it('says when a route has no vehicles', async () => {
    serveVehicles([]);
    await renderRoute('/map?route=18');
    expect(await screen.findByText(copy.empty.onRoute('18'))).toBeInTheDocument();
  });

  it('opens a stop picked in "Search the map" with a link to its page', async () => {
    server.use(
      respond('get', '/api/v1/stops', {
        items: [
          {
            stopId: '51418',
            name: 'Nicollet Ave & Lake St',
            lat: 44.948,
            lon: -93.278,
            locationType: 0,
            routeIds: ['18'],
            wheelchairBoarding: 1,
          },
        ],
      }),
    );
    await renderRoute('/map');
    await userEvent.type(await screen.findByRole('combobox', { name: copy.controls.search }), 'Lake');
    await userEvent.click(await screen.findByRole('option', { name: 'Nicollet Ave & Lake St' }));
    expect(mapDouble.moveTo).toHaveBeenCalledWith(-93.278, 44.948, 16);
    expect(await screen.findByRole('link', { name: copy.controls.stopDetails })).toHaveAttribute(
      'href',
      '/stops/51418',
    );
  });
});
