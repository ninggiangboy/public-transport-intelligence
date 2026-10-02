/**
 * Polling fallback (DOC-26 §8.3): while the stream is down, each subscribed channel is polled through the API client
 * and the answer goes into the same cache entries the events patch.
 *
 * Why write instead of invalidate: a screen may be subscribed to a channel without a mounted query on its endpoint
 * (a badge that only counts alerts), and the test of RT-14 expects the request itself. How the answer is written keeps
 * the shape of what screens read:
 *
 * - vehicles: one request per distinct route filter among the subscribers, stored under `keys.vehicles.live(filter)`
 *   in the shape of the existing entry (`{ data, asOf }` by default, a plain body if that is what is cached);
 * - alerts and jobs: every returned row goes through the same upsert as `alert.updated` / `job.run`, so lists of any
 *   filter and shape (plain, wrapped, infinite) stay consistent and nothing is invented for filters nobody queried;
 * - dlq: the summary is stored under `keys.etl.dlq.summary()` and the lists are invalidated.
 *
 * A failed poll is ignored; the next period tries again, and the 60 s refetch of every query is the safety net.
 */
import type { QueryClient } from '@tanstack/react-query';

import { api, read } from '@/api/client';
import { keys, normalizeList } from '@/api/keys';
import { newestAlertCreatedAt, upsertAlert, upsertJobRun } from '@/realtime/handlers';
import { shapeLike } from '@/realtime/cache-shapes';
import { POLL_PERIOD_MS } from '@/realtime/config';
import type { Channel } from '@/realtime/types';

export interface PollTarget {
  /** Channels to poll. */
  channels: readonly Channel[];
  /** The distinct route filters of the subscribers to `vehicles` (an empty list is "all routes"). */
  vehicleRoutes: readonly (readonly string[])[];
}

/** `GET /vehicles/live` once per route filter. Also the refetch after a reconnect, since `vehicles` has no replay. */
export async function pollVehicles(queryClient: QueryClient, routeFilters: readonly (readonly string[])[]) {
  await Promise.all(
    routeFilters.map(async (filter) => {
      const routeIds = normalizeList(filter);
      const { data, asOf } = await read(
        api.GET('/api/v1/vehicles/live', {
          params: { query: { routeId: routeIds.length > 0 ? routeIds : undefined } },
        }),
      );
      const queryKey = keys.vehicles.live(routeIds);
      queryClient.setQueryData(queryKey, (existing: unknown) => shapeLike(existing, data, asOf));
    }),
  );
}

async function pollAlerts(queryClient: QueryClient) {
  const since = newestAlertCreatedAt(queryClient);
  const { data } = await read(api.GET('/api/v1/alerts', { params: { query: { since } } }));
  // The API answers newest first; upserting oldest first leaves the newest at the head.
  for (const alert of [...data.items].reverse()) upsertAlert(queryClient, alert, 'created');
}

async function pollJobs(queryClient: QueryClient) {
  const { data } = await read(api.GET('/api/v1/etl/jobs', {}));
  for (const run of [...data.items].reverse()) upsertJobRun(queryClient, run);
}

async function pollDlq(queryClient: QueryClient) {
  const { data, asOf } = await read(api.GET('/api/v1/etl/dlq/summary', {}));
  queryClient.setQueryData(keys.etl.dlq.summary(), (existing: unknown) => shapeLike(existing, data, asOf));
  void queryClient.invalidateQueries({ queryKey: keys.etl.dlq.listAll() });
}

/** Runs one poll loop per channel; `start` polls at once and then every period of {@link POLL_PERIOD_MS}. */
export class Poller {
  private timers: ReturnType<typeof setInterval>[] = [];
  private readonly running = new Set<Channel>();

  constructor(private readonly queryClient: QueryClient) {}

  start(target: PollTarget) {
    this.stop();
    for (const channel of target.channels) {
      const poll = () => {
        this.poll(channel, target);
      };
      poll();
      this.timers.push(setInterval(poll, POLL_PERIOD_MS[channel]));
    }
  }

  stop() {
    for (const timer of this.timers) clearInterval(timer);
    this.timers = [];
  }

  private poll(channel: Channel, target: PollTarget) {
    // A slow answer does not pile requests up.
    if (this.running.has(channel)) return;
    this.running.add(channel);
    const done = () => this.running.delete(channel);
    const queryClient = this.queryClient;
    const request = {
      vehicles: () => pollVehicles(queryClient, target.vehicleRoutes),
      alerts: () => pollAlerts(queryClient),
      jobs: () => pollJobs(queryClient),
      dlq: () => pollDlq(queryClient),
    }[channel];
    request().then(done, done);
  }
}
