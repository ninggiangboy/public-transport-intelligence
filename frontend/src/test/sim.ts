import { http, HttpResponse } from 'msw';

import type { Scenario, ScenarioRun, SimStatus } from '@/api/sim';
import { mswPath } from '@/test/handlers';

// The simulator behind the proxy E-90 for the tests of Demo control and for `pnpm dev:mock`: the proxy is not in
// openapi.json, so its examples are written here, after DOC-25 §8.

// The catalog entries of DOC-25 §8, as the simulator writes them.
export const bunching: Scenario = {
  name: 'bunching',
  title: 'Bus bunching',
  description: 'Make consecutive buses on a route run close together.',
  concurrency: 'PER_TARGET',
  params: [
    { name: 'routeId', label: 'Route', type: 'ROUTE', required: true, nullable: false },
    {
      name: 'directionId',
      label: 'Direction',
      type: 'ENUM',
      required: false,
      nullable: false,
      default: 0,
      options: ['0', '1'],
    },
    {
      name: 'pairs',
      label: 'Vehicle pairs',
      type: 'INT',
      required: false,
      nullable: false,
      default: 1,
      min: 1,
      max: 3,
    },
    {
      name: 'targetGapRatio',
      label: 'Target gap (share of headway)',
      type: 'DOUBLE',
      required: false,
      nullable: false,
      default: 0.2,
      min: 0.05,
      max: 0.45,
    },
    {
      name: 'duration',
      label: 'Duration',
      type: 'DURATION',
      required: false,
      nullable: false,
      default: 'PT20M',
      min: 'PT1M',
      max: 'PT2H',
    },
  ],
};

export const disruption: Scenario = {
  name: 'disruption',
  title: 'Service disruption',
  description: 'Add growing delays to every trip on a route.',
  concurrency: 'PER_TARGET',
  params: [
    { name: 'routeId', label: 'Route', type: 'ROUTE', required: true, nullable: false },
    { name: 'directionId', label: 'Direction', type: 'ENUM', required: false, nullable: true, options: ['0', '1'] },
    { name: 'skipStops', label: 'Skip stops', type: 'BOOLEAN', required: false, nullable: false, default: false },
    {
      name: 'duration',
      label: 'Duration',
      type: 'DURATION',
      required: false,
      nullable: false,
      default: 'PT20M',
      min: 'PT1M',
      max: 'PT2H',
    },
  ],
};

export const badData: Scenario = {
  name: 'bad-data',
  title: 'Bad data',
  description: 'Corrupt a share of the GTFS-realtime messages.',
  concurrency: 'SINGLE',
  params: [
    {
      name: 'ratio',
      label: 'Share of messages',
      type: 'DOUBLE',
      required: false,
      nullable: false,
      default: 0.05,
      min: 0.001,
      max: 0.5,
    },
    {
      name: 'kinds',
      label: 'Kinds of corruption',
      type: 'ENUM_LIST',
      required: false,
      nullable: false,
      default: ['malformed_json', 'unknown_route'],
      options: ['malformed_json', 'unknown_route'],
    },
    {
      name: 'duration',
      label: 'Duration',
      type: 'DURATION',
      required: false,
      nullable: false,
      default: 'PT10M',
      min: 'PT1M',
      max: 'PT2H',
    },
  ],
};

export const loadRamp: Scenario = {
  name: 'load-ramp',
  title: 'Load ramp',
  description: 'Raise the GTFS-realtime emission rate step by step.',
  concurrency: 'SINGLE',
  params: [
    {
      name: 'steps',
      label: 'Rate multipliers',
      type: 'DOUBLE_LIST',
      required: false,
      nullable: false,
      default: [1, 2, 5, 10],
      min: 0.1,
      max: 20,
    },
    {
      name: 'stepDuration',
      label: 'Step duration',
      type: 'DURATION',
      required: false,
      nullable: false,
      default: 'PT5M',
      min: 'PT1M',
    },
    { name: 'rampDown', label: 'Ramp down', type: 'BOOLEAN', required: false, nullable: false, default: true },
  ],
};

export const ticketSpike: Scenario = {
  name: 'ticket-spike',
  title: 'Ticket sales spike',
  description: 'Add extra sales at one sale point.',
  concurrency: 'PER_TARGET',
  params: [
    {
      name: 'salePointId',
      label: 'Sale point',
      type: 'STRING',
      required: false,
      nullable: false,
      default: 'KIOSK-001',
      suggestions: ['KIOSK-001', 'KIOSK-002'],
    },
    {
      name: 'duration',
      label: 'Duration',
      type: 'DURATION',
      required: false,
      nullable: false,
      default: 'PT30M',
      min: 'PT15M',
      max: 'PT2H',
    },
  ],
};

export const catalog: Scenario[] = [bunching, disruption, badData, ticketSpike, loadRamp];

/** `GET /sim/status` at 4:40 PM CDT business time, 14 h 30 min ahead of the real clock. */
export const simStatus: SimStatus = {
  clock: {
    businessNow: '2026-10-02T21:40:00.000Z',
    offset: 'PT14H30M',
    agencyTimeZone: 'America/Chicago',
    serviceDates: [
      { realDate: '2026-10-01', feedDate: '2026-09-30' },
      { realDate: '2026-10-02', feedDate: '2026-10-01' },
    ],
  },
  rate: { gtfsRt: 1, ticketing: 1 },
  activeVehicles: 598,
  activeTrips: 466,
  messagesPerSecond: { 'gtfs.vehicle_positions': 119.6, 'gtfs.trip_updates': 21.8 },
  ticketingPerSecond: 1.9,
  runningScenarios: [],
};

/** A run of `scenario` as the simulator returns it; real time. */
export function simRun(overrides: Partial<ScenarioRun> = {}): ScenarioRun {
  const startedAt = new Date(Date.now() - 5 * 60_000).toISOString();
  return {
    runId: '0192f7b1-2c3d-7e4f-8a9b-0c1d2e3f4a5b',
    scenario: 'bunching',
    status: 'RUNNING',
    params: { routeId: '18', directionId: 0, pairs: 1, targetGapRatio: 0.2, duration: 'PT20M' },
    requestedBy: 'user:operator',
    startedAt,
    plannedEndAt: new Date(Date.parse(startedAt) + 20 * 60_000).toISOString(),
    ...overrides,
  };
}

/** Handlers of `/api/v1/sim/**` answering with these examples: an idle simulator and no run yet. */
export function simHandlers(state: { status?: SimStatus; runs?: ScenarioRun[] } = {}) {
  return [
    http.get(mswPath('/api/v1/sim/status'), () => HttpResponse.json(state.status ?? simStatus)),
    http.get(mswPath('/api/v1/sim/scenarios'), () => HttpResponse.json({ items: catalog })),
    http.get(mswPath('/api/v1/sim/scenario-runs'), () => HttpResponse.json({ items: state.runs ?? [] })),
    http.get(mswPath('/api/v1/sim/scenario-runs/{runId}'), ({ params }) => {
      const found = (state.runs ?? []).find((run) => run.runId === params.runId);
      return found
        ? HttpResponse.json(found)
        : HttpResponse.json(
            {
              type: 'urn:pti:problem:scenario-run-not-found',
              title: 'Scenario run not found',
              status: 404,
            },
            { status: 404, headers: { 'Content-Type': 'application/problem+json' } },
          );
    }),
    http.put(mswPath('/api/v1/sim/rate'), async ({ request }) => {
      const body = (await request.json()) as Partial<SimStatus['rate']>;
      const status = state.status ?? simStatus;
      return HttpResponse.json({ ...status, rate: { ...status.rate, ...body } });
    }),
    http.post(mswPath('/api/v1/sim/scenarios/{name}'), async ({ params, request }) => {
      const body = (await request.json()) as Record<string, unknown>;
      return HttpResponse.json(simRun({ scenario: String(params.name), params: body }), { status: 201 });
    }),
    http.delete(mswPath('/api/v1/sim/scenarios/{name}'), () => new HttpResponse(null, { status: 204 })),
    http.delete(mswPath('/api/v1/sim/scenario-runs/{runId}'), () => new HttpResponse(null, { status: 204 })),
  ];
}
