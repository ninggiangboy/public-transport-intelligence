import { QueryClient } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { setAuth, type AuthAdapter } from '@/api/client';
import { keys } from '@/api/keys';
import { RealtimeController } from '@/realtime/controller';
import type { RealtimeOptions } from '@/realtime/types';
import { server } from '@/test/server';
import { mswPath } from '@/test/handlers';
import { eventFrame, FakeSseServer, heartbeatFrame, resyncFrame } from '@/test/sse';

let queryClient: QueryClient;
let fake: FakeSseServer;
let controller: RealtimeController;
let stop: () => void;

/** Lets timers up to `ms` fire and the promises they start settle. */
async function elapse(ms: number) {
  await vi.advanceTimersByTimeAsync(ms);
}

/** Lets time pass on an open stream the way the server does: a heartbeat every 15 s keeps the watchdog quiet. */
async function elapseWithHeartbeats(ms: number) {
  for (let left = ms; left > 0; left -= 15_000) {
    fake.stream.send(heartbeatFrame());
    await elapse(Math.min(left, 15_000));
  }
}

/** Registers like a screen and waits for the 500 ms debounce, so that the connection is being opened. */
async function subscribe(options: RealtimeOptions) {
  const unregister = controller.register(options);
  await elapse(500);
  return unregister;
}

function visibility(state: 'visible' | 'hidden') {
  Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => state });
  document.dispatchEvent(new Event('visibilitychange'));
}

beforeEach(() => {
  vi.useFakeTimers();
  vi.spyOn(Math, 'random').mockReturnValue(0.5);
  queryClient = new QueryClient();
  fake = new FakeSseServer();
  controller = new RealtimeController({ queryClient, fetch: fake.fetch });
  stop = controller.start();
});

afterEach(() => {
  stop();
  setAuth(undefined);
  Reflect.deleteProperty(document, 'visibilityState');
  vi.useRealTimers();
});

describe('opening', () => {
  it('opens no connection while nobody subscribes (the app has no subscribers yet)', async () => {
    await elapse(120_000);
    expect(fake.requests).toHaveLength(0);
    expect(controller.getState().status).toBe('connecting');
  });

  it('merges the channels and routes of all subscribers into one connection, after 500 ms of quiet', async () => {
    controller.register({ channels: ['alerts', 'vehicles'], routeIds: ['18'] });
    controller.register({ channels: ['vehicles'], routeIds: ['2', '18'] });
    await elapse(499);
    expect(fake.requests).toHaveLength(0);
    await elapse(1);

    expect(fake.requests).toHaveLength(1);
    expect(fake.last.url.pathname).toBe('/api/v1/stream');
    expect(fake.last.url.searchParams.get('channels')).toBe('vehicles,alerts');
    expect(fake.last.url.searchParams.getAll('routeId')).toEqual(['18', '2']);
  });

  it('asks for every route when any subscriber does, or when there are more than 20', async () => {
    controller.register({ channels: ['vehicles'], routeIds: ['18'] });
    controller.register({ channels: ['alerts'] });
    await elapse(500);
    expect(fake.last.url.searchParams.has('routeId')).toBe(false);

    const many = Array.from({ length: 21 }, (_, index) => String(index));
    const second = new FakeSseServer();
    const other = new RealtimeController({ queryClient, fetch: second.fetch });
    const stopOther = other.start();
    other.register({ channels: ['vehicles'], routeIds: many });
    await elapse(500);
    expect(second.last.url.searchParams.has('routeId')).toBe(false);
    stopOther();
  });

  it('reopens once when the merge changes, grows or shrinks (debounced)', async () => {
    const first = await subscribe({ channels: ['vehicles'] });
    expect(fake.requests).toHaveLength(1);

    // Two changes within the window make one reopen.
    controller.register({ channels: ['alerts'] });
    await elapse(300);
    controller.register({ channels: ['jobs'] });
    await elapse(499);
    expect(fake.requests).toHaveLength(1);
    await elapse(1);
    expect(fake.requests).toHaveLength(2);
    expect(fake.requests[0]?.aborted).toBe(true);
    expect(fake.last.url.searchParams.get('channels')).toBe('vehicles,alerts,jobs');

    // The same merge does not reconnect.
    const again = controller.register({ channels: ['alerts'] });
    await elapse(500);
    expect(fake.requests).toHaveLength(2);
    again();

    // The first subscriber leaves: the merge shrinks, which is a change too.
    first();
    await elapse(500);
    expect(fake.requests).toHaveLength(3);
    expect(fake.last.url.searchParams.get('channels')).toBe('alerts,jobs');
  });

  it('stops everything when the provider goes away', async () => {
    await subscribe({ channels: ['alerts'] });
    stop();
    await elapse(120_000);
    expect(fake.requests).toHaveLength(1);
    expect(fake.last.aborted).toBe(true);
  });
});

describe('frames', () => {
  it('reports open, lastEventAt and businessNow, and applies events to the cache', async () => {
    queryClient.setQueryData(keys.vehicles.live(), {
      businessNow: 'x',
      count: 1,
      items: [{ vehicleId: '1', routeId: '18', lat: 1, lon: 1, eventTimestamp: '2026-09-29T21:00:00Z' }],
    });
    await subscribe({ channels: ['vehicles'] });
    await elapse(0);
    expect(controller.getState().status).toBe('open');

    fake.stream.send(heartbeatFrame('2026-09-29T21:19:45Z'));
    fake.stream.send(
      eventFrame(
        'vehicles.batch',
        {
          routeId: '18',
          vehicles: [
            {
              vehicleId: '1',
              tripId: 't',
              directionId: 0,
              lat: 44,
              lon: -93,
              currentStatus: 'STOPPED_AT',
              stopId: 's',
              currentStopSequence: 2,
              eventTimestamp: '2026-09-29T21:19:40Z',
            },
          ],
        },
        { channel: 'vehicles', routeId: '18' },
      ),
    );
    await elapse(0);

    const state = controller.getState();
    expect(state.businessNow).toBe('2026-09-29T21:19:45Z');
    expect(state.lastEventAt).toEqual(new Date());
    expect(queryClient.getQueryData<{ items: { lat: number }[] }>(keys.vehicles.live())?.items[0]?.lat).toBe(44);
  });

  it('notifies subscribers of state changes', async () => {
    const listener = vi.fn();
    const unsubscribe = controller.subscribe(listener);
    await subscribe({ channels: ['alerts'] });
    await elapse(0);
    expect(listener).toHaveBeenCalled();
    unsubscribe();
  });

  it('ignores unknown types and bad frames, warns once, and keeps the stream alive', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries');
    await subscribe({ channels: ['alerts'] });
    await elapse(0);

    fake.stream.send(eventFrame('station.exploded', { boom: true }));
    fake.stream.send({ event: 'vehicles.batch', data: 'not json' });
    fake.stream.send(eventFrame('vehicles.batch', { routeId: '18' }));
    fake.stream.send(eventFrame('dispatch.suggested', { id: 'x', routeId: '18' }));
    await elapse(0);

    expect(warn).toHaveBeenCalledTimes(1);
    expect(invalidate).toHaveBeenCalled();
    expect(controller.getState().status).toBe('open');
    expect(fake.requests).toHaveLength(1);
  });

  it('drops a frame it has already applied (replay by Last-Event-ID may repeat a few)', async () => {
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries');
    await subscribe({ channels: ['alerts'] });
    await elapse(0);
    const frame = eventFrame('dispatch.suggested', { id: 'x' });

    fake.stream.send(frame);
    await elapse(0);
    const once = invalidate.mock.calls.length;
    fake.stream.send(frame);
    await elapse(0);

    expect(invalidate.mock.calls.length).toBe(once);
  });

  it('resync of alerts invalidates exactly the alerts keys (RT-15)', async () => {
    for (const key of [keys.alerts.list(), keys.insights.bunching(), keys.etl.jobs.list(), keys.etl.dlq.summary()]) {
      queryClient.setQueryData(key, {});
    }
    await subscribe({ channels: ['alerts', 'jobs'] });
    await elapse(0);

    fake.stream.send(resyncFrame(['alerts']));
    await elapse(0);

    const stale = (key: readonly unknown[]) => queryClient.getQueryState(key)?.isInvalidated;
    expect(stale(keys.alerts.list())).toBe(true);
    expect(stale(keys.insights.bunching())).toBe(true);
    expect(stale(keys.etl.jobs.list())).toBe(false);
    expect(stale(keys.etl.dlq.summary())).toBe(false);
  });
});

describe('reconnecting', () => {
  it('follows 1 s, 2 s, 4 s ... 30 s plus 0-1 s of jitter, and starts over after a success', async () => {
    const random = vi.spyOn(Math, 'random');
    await subscribe({ channels: ['alerts'] });
    for (let i = 0; i < 8; i += 1) fake.failNext();
    // The first request is open; drop it, then fail 7 more times.
    await elapse(0);
    fake.stream.fail();
    await elapse(0);

    random.mockReturnValue(0.5);
    await elapse(200_000);
    // Request 0 opened, requests 1-7 failed, the 9th (index 8) opened... check the gaps between the failures.
    const gaps = fake.requests
      .slice(0, 9)
      .map((request, index, all) => request.at - (all[index - 1]?.at ?? request.at));
    expect(gaps.slice(1, 8)).toEqual([1500, 2500, 4500, 8500, 16_500, 30_500, 30_500]);
  });

  it('keeps the jitter between 0 and 1 s', async () => {
    const random = vi.spyOn(Math, 'random').mockReturnValue(0.999);
    await subscribe({ channels: ['alerts'] });
    await elapse(0);
    const dropped = Date.now();
    fake.stream.fail();
    await elapse(0);
    await elapse(1_999);
    expect(fake.requests).toHaveLength(2);
    expect(fake.last.at - dropped).toBeLessThan(2_000);
    expect(fake.last.at - dropped).toBeGreaterThanOrEqual(1_000);

    random.mockReturnValue(0);
    fake.stream.fail();
    await elapse(0);
    const second = Date.now();
    await elapse(1_000);
    expect(fake.last.at - second).toBe(1_000);
  });

  it('resets the backoff once a connection opens', async () => {
    await subscribe({ channels: ['alerts'] });
    await elapse(0);
    fake.failNext();
    fake.failNext();
    fake.stream.close();
    await elapse(1_500 + 2_500);
    expect(fake.requests).toHaveLength(3);
    // The fourth attempt, after 4.5 s, opens ...
    await elapse(4_500);
    expect(fake.requests).toHaveLength(4);
    // ... so the next loss waits 1 s plus jitter again, not 8.5 s.
    const lost = Date.now();
    fake.stream.fail();
    await elapse(1_500);
    expect(fake.requests).toHaveLength(5);
    expect(fake.last.at - lost).toBe(1_500);
  });

  it('sends the id of the last event as Last-Event-ID, and heartbeats do not move it', async () => {
    await subscribe({ channels: ['alerts'] });
    await elapse(0);
    expect(fake.last.headers.has('Last-Event-ID')).toBe(false);

    fake.stream.send(eventFrame('dispatch.suggested', { id: 'x' }, { id: 'EVENT-1' }));
    fake.stream.send(eventFrame('dispatch.suggested', { id: 'y' }, { id: 'EVENT-2' }));
    fake.stream.send(heartbeatFrame());
    fake.stream.send(resyncFrame(['alerts']));
    await elapse(0);
    fake.stream.fail();
    await elapse(2_000);

    expect(fake.requests).toHaveLength(2);
    expect(fake.last.headers.get('Last-Event-ID')).toBe('EVENT-2');
  });

  it('treats a stream the server ends like a loss', async () => {
    await subscribe({ channels: ['alerts'] });
    await elapse(0);
    fake.stream.close();
    await elapse(1_500);
    expect(fake.requests).toHaveLength(2);
  });

  it('declares the connection dead after 45 s without a frame, heartbeats included in the count', async () => {
    await subscribe({ channels: ['alerts'] });
    await elapse(0);
    await elapse(30_000);
    fake.stream.send(heartbeatFrame());
    await elapse(44_999);
    expect(fake.requests).toHaveLength(1);
    expect(fake.last.aborted).toBe(false);

    await elapse(1);
    expect(fake.requests[0]?.aborted).toBe(true);
    await elapse(1_500);
    expect(fake.requests).toHaveLength(2);
  });

  it('reconnects at once, with fresh credentials, when asked to (token refreshed)', async () => {
    let token = 'old';
    setAuth({ accessToken: () => token, renew: () => Promise.resolve(true), expired: vi.fn() });
    await subscribe({ channels: ['alerts'] });
    await elapse(0);
    expect(fake.last.headers.get('Authorization')).toBe('Bearer old');

    token = 'new';
    controller.reconnect();
    await elapse(0);

    expect(fake.requests).toHaveLength(2);
    expect(fake.requests[0]?.aborted).toBe(true);
    expect(fake.last.headers.get('Authorization')).toBe('Bearer new');
  });
});

describe('refusals', () => {
  function auth(overrides: Partial<AuthAdapter> = {}) {
    const adapter = {
      accessToken: vi.fn(() => 'old'),
      renew: vi.fn(() => Promise.resolve(true)),
      expired: vi.fn(),
      ...overrides,
    };
    setAuth(adapter);
    return adapter;
  }

  it('401 renews the session and reconnects with the new token at once', async () => {
    let token = 'old';
    const adapter = auth({
      accessToken: vi.fn(() => token),
      renew: vi.fn(() => {
        token = 'renewed';
        return Promise.resolve(true);
      }),
    });
    fake.refuseNext(401);
    await subscribe({ channels: ['alerts', 'jobs'] });
    await elapse(0);

    expect(adapter.renew).toHaveBeenCalledTimes(1);
    expect(fake.requests).toHaveLength(2);
    expect(fake.requests[0]?.headers.get('Authorization')).toBe('Bearer old');
    expect(fake.last.headers.get('Authorization')).toBe('Bearer renewed');
    expect(fake.last.url.searchParams.get('channels')).toBe('alerts,jobs');
    expect(controller.getState().status).toBe('open');
  });

  it('401 after a failed renewal drops the channels that need a role and reconnects with the public ones', async () => {
    const adapter = auth({ renew: vi.fn(() => Promise.resolve(false)) });
    fake.refuseNext(401);
    await subscribe({ channels: ['vehicles', 'jobs', 'dlq'] });
    await elapse(0);

    expect(adapter.expired).toHaveBeenCalledTimes(1);
    expect(fake.requests).toHaveLength(2);
    expect(fake.last.url.searchParams.get('channels')).toBe('vehicles');
    expect(controller.getState().status).toBe('open');
  });

  it('401 for an anonymous user goes straight to the public channels', async () => {
    fake.refuseNext(401);
    await subscribe({ channels: ['alerts', 'dlq'] });
    await elapse(0);
    expect(fake.last.url.searchParams.get('channels')).toBe('alerts');
  });

  it('401 with nothing public left ends the attempts', async () => {
    fake.refuseNext(401);
    await subscribe({ channels: ['jobs'] });
    await elapse(120_000);
    expect(fake.requests).toHaveLength(1);
  });

  it('401 again after a successful renewal drops the restricted channels instead of looping', async () => {
    const adapter = auth();
    fake.refuseNext(401);
    fake.refuseNext(401);
    await subscribe({ channels: ['alerts', 'jobs'] });
    await elapse(0);

    expect(adapter.renew).toHaveBeenCalledTimes(1);
    expect(fake.requests).toHaveLength(3);
    expect(fake.last.url.searchParams.get('channels')).toBe('alerts');
  });

  it('403 drops the restricted channels too', async () => {
    fake.refuseNext(403);
    await subscribe({ channels: ['alerts', 'dlq'] });
    await elapse(0);
    expect(fake.last.url.searchParams.get('channels')).toBe('alerts');
  });

  it('429 waits for Retry-After, without jitter', async () => {
    fake.refuseNext(429, { 'Retry-After': '7' });
    await subscribe({ channels: ['alerts'] });
    await elapse(6_999);
    expect(fake.requests).toHaveLength(1);
    await elapse(1);
    expect(fake.requests).toHaveLength(2);
    expect(fake.last.at - (fake.requests[0]?.at ?? 0)).toBe(7_000);
  });

  it('503 without Retry-After backs off like a lost connection', async () => {
    fake.refuseNext(503);
    await subscribe({ channels: ['alerts'] });
    await elapse(1_499);
    expect(fake.requests).toHaveLength(1);
    await elapse(1);
    expect(fake.requests).toHaveLength(2);
  });

  it('another 4xx is not retried with the same parameters', async () => {
    fake.refuseNext(400);
    await subscribe({ channels: ['alerts'] });
    await elapse(300_000);
    expect(fake.requests).toHaveLength(1);

    // A different subscription is a new try.
    controller.register({ channels: ['vehicles'] });
    await elapse(500);
    expect(fake.requests).toHaveLength(2);
  });

  it('a response that is not an event stream counts as a failure', async () => {
    const odd = new RealtimeController({
      queryClient,
      fetch: () => Promise.resolve(new Response('<html>', { headers: { 'Content-Type': 'text/html' } })),
    });
    const stopOdd = odd.start();
    odd.register({ channels: ['alerts'] });
    await elapse(500);
    expect(odd.getState().status).not.toBe('open');
    stopOdd();
  });
});

describe('polling fallback (RT-14)', () => {
  let polls: number[];

  beforeEach(() => {
    polls = [];
    server.use(
      http.get(mswPath('/api/v1/vehicles/live'), () => {
        polls.push(Date.now());
        return HttpResponse.json({ businessNow: '2026-09-29T21:19:35Z', count: 0, items: [] });
      }),
    );
  });

  it('polls GET /vehicles/live every 5 s once the stream has been down for 5 s, and stops and refetches once on reconnect', async () => {
    await subscribe({ channels: ['vehicles'], routeIds: [] });
    await elapse(0);
    expect(controller.getState().status).toBe('open');

    // Down at t=0; the attempts fail at 1.5 s, 4 s and 8.5 s, and the fourth, at 17 s, opens.
    fake.failNext();
    fake.failNext();
    fake.failNext();
    fake.stream.fail();
    await elapse(0);
    const lost = Date.now();
    expect(controller.getState().status).toBe('reconnecting');

    await elapse(4_999);
    expect(controller.getState().status).toBe('reconnecting');
    expect(polls).toHaveLength(0);
    await elapse(1);
    expect(controller.getState().status).toBe('polling');
    await elapse(10_000);
    expect(polls.map((at) => at - lost)).toEqual([5_000, 10_000, 15_000]);
    expect(queryClient.getQueryData(keys.vehicles.live())).toEqual({
      data: { businessNow: '2026-09-29T21:19:35Z', count: 0, items: [] },
      asOf: undefined,
    });

    await elapse(2_000);
    expect(controller.getState().status).toBe('open');
    // One refetch for the gap (the vehicles channel has no replay), then silence.
    expect(polls).toHaveLength(4);
    await elapseWithHeartbeats(60_000);
    expect(polls).toHaveLength(4);
  });

  it('does not poll when the stream comes back within 5 s, but still refetches the snapshot', async () => {
    await subscribe({ channels: ['vehicles'] });
    await elapse(0);
    fake.stream.fail();
    await elapse(2_000);
    expect(controller.getState().status).toBe('open');
    expect(polls).toHaveLength(1);
  });

  it('polls only the channels that are subscribed, at their periods', async () => {
    const hits = { alerts: 0, jobs: 0, dlq: 0, since: [] as (string | null)[] };
    server.use(
      http.get(mswPath('/api/v1/alerts'), ({ request }) => {
        hits.alerts += 1;
        hits.since.push(new URL(request.url).searchParams.get('since'));
        return HttpResponse.json({
          items: [
            {
              id: 'a1',
              type: 'DISRUPTION',
              severity: 2,
              audience: 'PUBLIC',
              title: 't',
              body: {},
              createdAt: '2026-09-29T21:00:00Z',
              link: '/map',
            },
          ],
        });
      }),
      http.get(mswPath('/api/v1/etl/jobs'), () => {
        hits.jobs += 1;
        return HttpResponse.json({
          items: [
            {
              runId: 'job:1',
              kind: 'BATCH_JOB',
              name: 'n',
              status: 'STARTED',
              readCount: 0,
              writeCount: 0,
              skipCount: 0,
            },
          ],
        });
      }),
      http.get(mswPath('/api/v1/etl/dlq/summary'), () => {
        hits.dlq += 1;
        return HttpResponse.json({ byStatus: {}, createdLastHour: 0, open: 3, openBySeverity: {}, openBySource: {} });
      }),
    );
    queryClient.setQueryData(keys.alerts.list(), { items: [] });
    queryClient.setQueryData(keys.etl.jobs.list(), { data: { items: [] }, asOf: undefined });
    fake.refuseNext(400);
    await subscribe({ channels: ['alerts', 'jobs', 'dlq'] });
    await elapse(5_000);
    expect(hits).toMatchObject({ alerts: 1, jobs: 1, dlq: 1 });

    await elapse(30_000);
    expect(hits).toMatchObject({ alerts: 2, jobs: 3, dlq: 2 });
    expect(polls).toHaveLength(0);
    // Rows land in the lists the screens query, in the shape those lists already have.
    expect(queryClient.getQueryData<{ items: { id: string }[] }>(keys.alerts.list())?.items.map((a) => a.id)).toEqual([
      'a1',
    ]);
    expect(
      queryClient.getQueryData<{ data: { items: { runId: string }[] } }>(keys.etl.jobs.list())?.data.items[0]?.runId,
    ).toBe('job:1');
    expect(queryClient.getQueryData<{ data: { open: number } }>(keys.etl.dlq.summary())?.data.open).toBe(3);
    // Later alert polls ask for what is newer than the newest alert in the cache.
    expect(hits.since[0]).toBeNull();
    expect(hits.since[1]).toBe('2026-09-29T21:00:00Z');
  });

  it('survives a failing poll and tries again at the next period', async () => {
    let calls = 0;
    server.use(
      http.get(mswPath('/api/v1/vehicles/live'), () => {
        calls += 1;
        return calls === 1
          ? new HttpResponse(null, { status: 500 })
          : HttpResponse.json({ businessNow: 'x', count: 0, items: [] });
      }),
    );
    fake.refuseNext(400);
    await subscribe({ channels: ['vehicles'] });
    await elapse(10_000);
    expect(calls).toBe(2);
  });

  it('polls one snapshot per route filter among the subscribers', async () => {
    const routes: string[][] = [];
    server.use(
      http.get(mswPath('/api/v1/vehicles/live'), ({ request }) => {
        routes.push(new URL(request.url).searchParams.getAll('routeId'));
        return HttpResponse.json({ businessNow: 'x', count: 0, items: [] });
      }),
    );
    fake.refuseNext(400);
    controller.register({ channels: ['vehicles'], routeIds: ['18', '2'] });
    controller.register({ channels: ['vehicles'], routeIds: ['2', '18'] });
    await subscribe({ channels: ['vehicles'], routeIds: ['7'] });
    await elapse(5_000);

    expect(routes.map((ids) => ids.join()).sort()).toEqual(['18,2', '7']);
    expect(queryClient.getQueryData(keys.vehicles.live(['2', '18']))).toBeDefined();
    expect(queryClient.getQueryData(keys.vehicles.live(['7']))).toBeDefined();
  });

  it('does not poll a channel that was dropped for lack of a role', async () => {
    const hits = { jobs: 0, alerts: 0 };
    server.use(
      http.get(mswPath('/api/v1/etl/jobs'), () => {
        hits.jobs += 1;
        return HttpResponse.json({ items: [] });
      }),
      http.get(mswPath('/api/v1/alerts'), () => {
        hits.alerts += 1;
        return HttpResponse.json({ items: [] });
      }),
    );
    fake.refuseNext(401);
    fake.refuseNext(400);
    await subscribe({ channels: ['alerts', 'jobs'] });
    await elapse(5_000);
    expect(hits).toEqual({ jobs: 0, alerts: 1 });
  });
});

describe('hidden tab', () => {
  it('closes after 5 minutes hidden, and reopens and refetches when the tab shows again', async () => {
    await subscribe({ channels: ['alerts'] });
    await elapse(0);

    visibility('hidden');
    await elapseWithHeartbeats(5 * 60_000 - 1);
    expect(fake.last.aborted).toBe(false);
    await elapse(1);
    expect(fake.last.aborted).toBe(true);
    expect(controller.getState().status).toBe('reconnecting');
    await elapse(10 * 60_000);
    expect(fake.requests).toHaveLength(1);

    queryClient.setQueryData(keys.alerts.list(), {});
    visibility('visible');
    await elapse(0);
    expect(fake.requests).toHaveLength(2);
    expect(queryClient.getQueryState(keys.alerts.list())?.isInvalidated).toBe(true);
    expect(controller.getState().status).toBe('open');
  });

  it('stays connected when the tab is back before 5 minutes', async () => {
    await subscribe({ channels: ['alerts'] });
    await elapse(0);
    visibility('hidden');
    await elapseWithHeartbeats(4 * 60_000);
    visibility('visible');
    await elapseWithHeartbeats(10 * 60_000);
    expect(fake.requests).toHaveLength(1);
    expect(fake.last.aborted).toBe(false);
  });
});
