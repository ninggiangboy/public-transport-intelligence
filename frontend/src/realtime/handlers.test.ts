import { QueryClient } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import type { components } from '@/api/generated/schema';
import { keys } from '@/api/keys';
import { createHandlers, newestAlertCreatedAt, type Handlers } from '@/realtime/handlers';
import { parseFrame, type RealtimeEvent } from '@/realtime/schemas';
import { eventFrame, heartbeatFrame, resyncFrame } from '@/test/sse';

type Schemas = components['schemas'];

/** The event the stream would deliver for this frame. */
function event(type: string, data: unknown, extra: { routeId?: string } = {}): RealtimeEvent {
  const frame =
    type === 'heartbeat'
      ? heartbeatFrame(data as string)
      : type === 'resync'
        ? resyncFrame(data as string[])
        : eventFrame(type, data, extra);
  const parsed = parseFrame(frame.event, JSON.stringify(frame.data));
  if (parsed.kind !== 'event') throw new Error(`Bad fixture for ${type}`);
  return parsed.event;
}

const fix = {
  tripId: 't1',
  directionId: 0,
  lat: 44.9,
  lon: -93.2,
  currentStatus: 'IN_TRANSIT_TO',
  stopId: '51420',
  currentStopSequence: 14,
  eventTimestamp: '2026-09-29T21:19:30Z',
};

function vehicle(
  vehicleId: string,
  patch: Partial<Schemas['LiveVehicleResponse']> = {},
): Schemas['LiveVehicleResponse'] {
  return { ...fix, vehicleId, routeId: '18', delaySeconds: 95, headsign: 'Downtown', label: vehicleId, ...patch };
}

function live(items: Schemas['LiveVehicleResponse'][]): Schemas['LiveVehiclesResponse'] {
  return { businessNow: '2026-09-29T21:19:35Z', count: items.length, items };
}

function alert(id: string, patch: Partial<Schemas['AlertResponse']> = {}): Schemas['AlertResponse'] {
  return {
    id,
    type: 'DISRUPTION',
    severity: 2,
    audience: 'PUBLIC',
    routeId: '18',
    title: id,
    body: {},
    createdAt: '2026-09-29T20:59:31Z',
    link: '/map',
    ...patch,
  };
}

function stale(queryClient: QueryClient, queryKey: readonly unknown[]) {
  return queryClient.getQueryState(queryKey)?.isInvalidated ?? false;
}

let queryClient: QueryClient;
let handlers: Handlers;

beforeEach(() => {
  queryClient = new QueryClient();
  handlers = createHandlers(queryClient);
});

afterEach(() => {
  handlers.dispose();
  vi.useRealTimers();
});

describe('vehicles.batch', () => {
  const batch = (vehicles: object[]) => event('vehicles.batch', { routeId: '18', vehicles }, { routeId: '18' });

  it('overwrites positions by vehicleId, keeps the snapshot-only fields and appends new vehicles', () => {
    const queryKey = keys.vehicles.live();
    queryClient.setQueryData(queryKey, {
      data: live([vehicle('1203'), vehicle('1187')]),
      asOf: '2026-09-29T21:19:35Z',
    });

    handlers.apply(
      batch([
        { ...fix, vehicleId: '1203', lat: 45.0, eventTimestamp: '2026-09-29T21:19:40Z', speedMps: 5 },
        { ...fix, vehicleId: '1500', lat: 46 },
      ]),
    );

    const cached = queryClient.getQueryData<{ data: Schemas['LiveVehiclesResponse']; asOf: string }>(queryKey);
    expect(cached?.asOf).toBe('2026-09-29T21:19:35Z');
    expect(cached?.data.count).toBe(3);
    expect(cached?.data.items[0]).toMatchObject({
      vehicleId: '1203',
      lat: 45.0,
      speedMps: 5,
      delaySeconds: 95,
      headsign: 'Downtown',
      label: '1203',
    });
    expect(cached?.data.items[1]).toEqual(vehicle('1187'));
    expect(cached?.data.items[2]).toMatchObject({ vehicleId: '1500', routeId: '18', lat: 46 });
  });

  it('patches a plain body just as well, and only the queries that include the route', () => {
    queryClient.setQueryData(keys.vehicles.live(['18']), live([vehicle('1203')]));
    const other = live([vehicle('9', { routeId: '7' })]);
    queryClient.setQueryData(keys.vehicles.live(['7']), other);

    handlers.apply(batch([{ ...fix, vehicleId: '1203', lat: 45.5, eventTimestamp: '2026-09-29T21:19:50Z' }]));

    expect(queryClient.getQueryData<Schemas['LiveVehiclesResponse']>(keys.vehicles.live(['18']))?.items[0]?.lat).toBe(
      45.5,
    );
    expect(queryClient.getQueryData(keys.vehicles.live(['7']))).toBe(other);
  });

  it('ignores a fix older than the cached one and queries that have not loaded', () => {
    const queryKey = keys.vehicles.live();
    const snapshot = live([vehicle('1203', { eventTimestamp: '2026-09-29T21:19:59Z' })]);
    queryClient.setQueryData(queryKey, snapshot);
    queryClient.getQueryCache().build(queryClient, { queryKey: keys.vehicles.live(['18']) });

    handlers.apply(batch([{ ...fix, vehicleId: '1203', lat: 1 }]));

    expect(queryClient.getQueryData(queryKey)).toBe(snapshot);
    expect(queryClient.getQueryData(keys.vehicles.live(['18']))).toBeUndefined();
  });
});

describe('heartbeat', () => {
  it('hides vehicles that are 5 minutes older than businessNow', () => {
    queryClient.setQueryData(
      keys.vehicles.live(),
      live([
        vehicle('old', { eventTimestamp: '2026-09-29T21:14:00Z' }),
        vehicle('fresh', { eventTimestamp: '2026-09-29T21:19:00Z' }),
      ]),
    );

    handlers.apply(event('heartbeat', '2026-09-29T21:19:45Z'));

    const cached = queryClient.getQueryData<Schemas['LiveVehiclesResponse']>(keys.vehicles.live());
    expect(cached?.items.map((item) => item.vehicleId)).toEqual(['fresh']);
    expect(cached?.count).toBe(1);
  });
});

describe('bunching, dispatch and disruption', () => {
  const opened = {
    id: 'b1',
    routeId: '18',
    vehicleLeader: '1187',
    vehicleFollower: '1203',
    gapSeconds: 112,
    headwaySeconds: 600,
  };

  it('puts the overlay on the pair and takes it off again', () => {
    queryClient.setQueryData(keys.vehicles.live(), live([vehicle('1187'), vehicle('1203'), vehicle('1')]));
    queryClient.setQueryData(keys.insights.bunching(), { items: [] });

    handlers.apply(event('bunching.opened', opened, { routeId: '18' }));
    const items = queryClient.getQueryData<Schemas['LiveVehiclesResponse']>(keys.vehicles.live())?.items;
    expect(items?.[0]?.bunching).toEqual({
      episodeId: 'b1',
      role: 'LEADER',
      partnerVehicleId: '1203',
      gapSeconds: 112,
      headwaySeconds: 600,
    });
    expect(items?.[1]?.bunching).toMatchObject({ role: 'FOLLOWER', partnerVehicleId: '1187' });
    expect(items?.[2]?.bunching).toBeUndefined();
    expect(stale(queryClient, keys.insights.bunching())).toBe(true);

    handlers.apply(event('bunching.closed', { id: 'b1', routeId: '18' }, { routeId: '18' }));
    const after = queryClient.getQueryData<Schemas['LiveVehiclesResponse']>(keys.vehicles.live())?.items;
    expect(after?.some((item) => item.bunching)).toBe(false);
    expect(after?.[0]).toEqual(vehicle('1187'));
  });

  it('dispatch.suggested refreshes the bunching and dispatch lists only', () => {
    for (const key of [keys.insights.bunching(), keys.insights.dispatch(), keys.insights.disruption()])
      queryClient.setQueryData(key, {});

    handlers.apply(event('dispatch.suggested', { id: 'x', routeId: '18' }, { routeId: '18' }));

    expect(stale(queryClient, keys.insights.bunching())).toBe(true);
    expect(stale(queryClient, keys.insights.dispatch())).toBe(true);
    expect(stale(queryClient, keys.insights.disruption())).toBe(false);
  });

  it('disruptions refresh the list and the stops of the route', () => {
    queryClient.setQueryData(keys.insights.disruption(), {});
    const stop = (routeId: string) => ({ data: { routes: [{ routeId }], activeDisruptions: [] }, asOf: undefined });
    queryClient.setQueryData(keys.stops.detail('s18'), stop('18'));
    queryClient.setQueryData(keys.stops.detail('s7'), stop('7'));
    queryClient.setQueryData(keys.stops.detail('named'), stop('7'));

    handlers.apply(
      event('disruption.opened', { id: 'd1', routeId: '18', affectedStopIds: ['named'] }, { routeId: '18' }),
    );

    expect(stale(queryClient, keys.insights.disruption())).toBe(true);
    expect(stale(queryClient, keys.stops.detail('s18'))).toBe(true);
    expect(stale(queryClient, keys.stops.detail('named'))).toBe(true);
    expect(stale(queryClient, keys.stops.detail('s7'))).toBe(false);
  });
});

describe('alerts', () => {
  const record = (id: string, patch: object = {}) => ({ ...alert(id), ...patch });

  it('inserts at the head of the first page of every list the alert matches', () => {
    const all = keys.alerts.list();
    const route7 = keys.alerts.list({ routeId: ['7'] });
    const open = keys.alerts.list({ state: 'open', severity: [1, 2] });
    const history = keys.alerts.list({ from: '2026-09-01T00:00:00Z' });
    queryClient.setQueryData(all, { data: { items: [alert('old')] }, asOf: 'x' });
    queryClient.setQueryData(route7, { items: [] });
    queryClient.setQueryData(open, {
      pages: [{ items: [alert('old')] }, { items: [alert('older')] }],
      pageParams: [undefined, 'c'],
    });
    queryClient.setQueryData(history, { items: [] });

    handlers.apply(event('alert.created', record('new'), { routeId: '18' }));

    const ids = (key: readonly unknown[]) =>
      queryClient.getQueryData<{ data: { items: { id: string }[] } }>(key)?.data.items.map((a) => a.id);
    expect(ids(all)).toEqual(['new', 'old']);
    expect(queryClient.getQueryData<{ items: unknown[] }>(route7)?.items).toEqual([]);
    expect(
      queryClient
        .getQueryData<{ pages: { items: { id: string }[] }[] }>(open)
        ?.pages.map((page) => page.items.map((a) => a.id)),
    ).toEqual([['new', 'old'], ['older']]);
    expect(queryClient.getQueryData<{ items: unknown[] }>(history)?.items).toEqual([]);
  });

  it('replaces by id without merging, on any page, and drops an alert a list no longer matches', () => {
    const all = keys.alerts.list();
    const open = keys.alerts.list({ state: 'open' });
    queryClient.setQueryData(all, { items: [alert('a', { title: 'before', acknowledgedBy: 'x' }), alert('b')] });
    queryClient.setQueryData(open, { items: [alert('a'), alert('b')] });

    handlers.apply(
      event('alert.updated', record('a', { title: 'after', resolvedAt: '2026-09-29T21:40:00Z' }), { routeId: '18' }),
    );

    const items = queryClient.getQueryData<{ items: Schemas['AlertResponse'][] }>(all)?.items;
    expect(items?.map((a) => a.title)).toEqual(['after', 'b']);
    expect(items?.[0]).not.toHaveProperty('acknowledgedBy');
    expect(queryClient.getQueryData<{ items: { id: string }[] }>(open)?.items.map((a) => a.id)).toEqual(['b']);
  });

  it('does not insert an updated alert that belongs on a page that is not loaded', () => {
    const all = keys.alerts.list();
    queryClient.setQueryData(all, { items: [alert('head', { createdAt: '2026-09-29T22:00:00Z' })] });

    handlers.apply(event('alert.updated', record('older', { createdAt: '2026-09-29T20:00:00Z' }), { routeId: '18' }));
    expect(queryClient.getQueryData<{ items: unknown[] }>(all)?.items).toHaveLength(1);

    handlers.apply(event('alert.updated', record('newer', { createdAt: '2026-09-29T23:00:00Z' }), { routeId: '18' }));
    expect(queryClient.getQueryData<{ items: { id: string }[] }>(all)?.items.map((a) => a.id)).toEqual([
      'newer',
      'head',
    ]);
  });

  it('retracts: removes the alert and refreshes the stops of its route', () => {
    queryClient.setQueryData(keys.alerts.list(), { items: [alert('a'), alert('b')] });
    queryClient.setQueryData(keys.stops.detail('s18'), {
      data: { routes: [{ routeId: '18' }], activeDisruptions: [] },
    });

    handlers.apply(event('alert.retracted', { id: 'a', routeId: '18' }, { routeId: '18' }));

    expect(queryClient.getQueryData<{ items: { id: string }[] }>(keys.alerts.list())?.items.map((a) => a.id)).toEqual([
      'b',
    ]);
    expect(stale(queryClient, keys.stops.detail('s18'))).toBe(true);
  });

  it('finds the newest createdAt in the cache for the polling fallback', () => {
    expect(newestAlertCreatedAt(queryClient)).toBeUndefined();
    queryClient.setQueryData(keys.alerts.list(), { items: [alert('a', { createdAt: '2026-09-29T20:00:00Z' })] });
    queryClient.setQueryData(keys.alerts.list({ state: 'open' }), {
      pages: [{ data: { items: [alert('b', { createdAt: '2026-09-29T22:00:00Z' })] } }],
    });
    expect(newestAlertCreatedAt(queryClient)).toBe('2026-09-29T22:00:00Z');
  });
});

describe('job.run', () => {
  const run = (runId: string, patch: object = {}) => ({
    runId,
    kind: 'BATCH_JOB',
    name: 'RawZoneReplayJob',
    status: 'STARTED',
    readCount: 5,
    ...patch,
  });
  const stored = (runId: string): Schemas['JobRunResponse'] => ({
    runId,
    kind: 'BATCH_JOB',
    name: 'RawZoneReplayJob',
    status: 'STARTED',
    readCount: 1,
    writeCount: 0,
    skipCount: 0,
    durationMs: 900,
  });

  it('replaces a run by runId (keeping what the event lacks) and inserts a new one on the first page', () => {
    const all = keys.etl.jobs.list();
    const other = keys.etl.jobs.list({ name: ['OtherJob'] });
    queryClient.setQueryData(all, { data: { items: [stored('job:1')] }, asOf: 'x' });
    queryClient.setQueryData(other, { items: [] });

    handlers.apply(event('job.run', run('job:1', { status: 'COMPLETED', readCount: 9 }), { routeId: undefined }));
    handlers.apply(event('job.run', run('job:2')));

    const items = queryClient.getQueryData<{ data: { items: Schemas['JobRunResponse'][] } }>(all)?.data.items;
    expect(items?.map((r) => r.runId)).toEqual(['job:2', 'job:1']);
    expect(items?.[1]).toMatchObject({ status: 'COMPLETED', readCount: 9, durationMs: 900 });
    expect(queryClient.getQueryData<{ items: unknown[] }>(other)?.items).toEqual([]);
  });

  it('refreshes the detail at most every 2 s', () => {
    vi.useFakeTimers();
    queryClient.setQueryData(keys.etl.job('job:1'), {});

    handlers.apply(event('job.run', run('job:1')));
    handlers.apply(event('job.run', run('job:1', { readCount: 6 })));
    vi.advanceTimersByTime(1_999);
    expect(stale(queryClient, keys.etl.job('job:1'))).toBe(false);
    vi.advanceTimersByTime(1);
    expect(stale(queryClient, keys.etl.job('job:1'))).toBe(true);
  });
});

describe('dlq.changed', () => {
  const row = (id: string, status: string): Schemas['DeadLetterItemResponse'] => ({
    id,
    status,
    source: 'S',
    stage: 'PARSE',
    errorClass: 'E',
    errorMessage: 'm',
    createdAt: 'x',
    updatedAt: 'x',
    hasEditedPayload: false,
    replayCount: 0,
    autoReplayCount: 0,
  });

  it.each([
    ['CREATED', { kind: 'CREATED', source: 'S', count: 3 }],
    ['BULK_UPDATED', { kind: 'BULK_UPDATED', source: 'S', status: 'RESOLVED', count: 900 }],
  ])('%s refreshes the lists and the summary once, 2 s later', (_kind, data) => {
    vi.useFakeTimers();
    queryClient.setQueryData(keys.etl.dlq.list(), {});
    queryClient.setQueryData(keys.etl.dlq.summary(), {});
    queryClient.setQueryData(keys.etl.dlq.detail('d'), {});

    handlers.apply(event('dlq.changed', data));
    handlers.apply(event('dlq.changed', data));
    expect(stale(queryClient, keys.etl.dlq.list())).toBe(false);
    vi.advanceTimersByTime(2_000);

    expect(stale(queryClient, keys.etl.dlq.list())).toBe(true);
    expect(stale(queryClient, keys.etl.dlq.summary())).toBe(true);
    expect(stale(queryClient, keys.etl.dlq.detail('d'))).toBe(false);
  });

  it('UPDATED sets the status of the row at once and refreshes the detail and the summary', () => {
    vi.useFakeTimers();
    queryClient.setQueryData(keys.etl.dlq.list(), { items: [row('d1', 'NEW'), row('d2', 'NEW')] });
    queryClient.setQueryData(keys.etl.dlq.detail('d1'), {});
    queryClient.setQueryData(keys.etl.dlq.summary(), {});

    handlers.apply(event('dlq.changed', { kind: 'UPDATED', id: 'd1', status: 'REPLAY_REQUESTED' }));

    expect(
      queryClient
        .getQueryData<{ items: Schemas['DeadLetterItemResponse'][] }>(keys.etl.dlq.list())
        ?.items.map((r) => r.status),
    ).toEqual(['REPLAY_REQUESTED', 'NEW']);
    expect(stale(queryClient, keys.etl.dlq.detail('d1'))).toBe(false);
    vi.advanceTimersByTime(2_000);
    expect(stale(queryClient, keys.etl.dlq.detail('d1'))).toBe(true);
    expect(stale(queryClient, keys.etl.dlq.summary())).toBe(true);
  });

  it('dispose cancels what is pending', () => {
    vi.useFakeTimers();
    queryClient.setQueryData(keys.etl.dlq.summary(), {});
    handlers.apply(event('dlq.changed', { kind: 'CREATED', count: 1 }));
    handlers.dispose();
    vi.advanceTimersByTime(5_000);
    expect(stale(queryClient, keys.etl.dlq.summary())).toBe(false);
  });
});

describe('resync (RT-15)', () => {
  function seed() {
    const all = {
      alerts: [
        keys.alerts.list(),
        keys.alerts.list({ state: 'open' }),
        keys.insights.bunching(),
        keys.insights.dispatch(),
        keys.insights.disruption(),
        keys.stops.detail('s1'),
      ],
      jobs: [keys.etl.jobs.list(), keys.etl.job('job:1')],
      dlq: [keys.etl.dlq.list(), keys.etl.dlq.summary(), keys.etl.dlq.detail('d')],
      other: [['routes'] as const],
    };
    for (const key of Object.values(all).flat()) queryClient.setQueryData(key, {});
    return all;
  }

  it('invalidates the keys of the listed channels and nothing else', () => {
    const all = seed();
    handlers.apply(event('resync', ['alerts']));
    for (const key of all.alerts) expect(stale(queryClient, key)).toBe(true);
    for (const key of [...all.jobs, ...all.dlq, ...all.other]) expect(stale(queryClient, key)).toBe(false);
  });

  it('handles several channels and ignores unknown ones', () => {
    const all = seed();
    handlers.apply(event('resync', ['jobs', 'dlq', 'teleport']));
    for (const key of [...all.jobs, ...all.dlq]) expect(stale(queryClient, key)).toBe(true);
    for (const key of [...all.alerts, ...all.other]) expect(stale(queryClient, key)).toBe(false);
  });

  it('vehicles resync refreshes the live snapshots', () => {
    queryClient.setQueryData(keys.vehicles.live(['18']), live([]));
    handlers.apply(event('resync', ['vehicles']));
    expect(stale(queryClient, keys.vehicles.live(['18']))).toBe(true);
  });
});
