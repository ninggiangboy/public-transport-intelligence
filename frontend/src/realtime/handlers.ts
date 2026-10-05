/**
 * Events to cache (DOC-26 §9). Events only patch or invalidate the TanStack Query cache; components never read the
 * stream (§8.4). Each handler is a plain function of `(queryClient, payload)`; {@link createHandlers} ties them to the
 * event types and owns the 2 s coalescing of invalidations.
 */
import type { Query, QueryClient, QueryKey } from '@tanstack/react-query';

import { keys } from '@/api/keys';
import type { components } from '@/api/generated/schema';
import { bodyOf, isRecord, mapBody, mapPages } from '@/realtime/cache-shapes';
import { INVALIDATE_THROTTLE_MS, JOB_SUMMARY_THROTTLE_MS, VEHICLE_STALE_MS } from '@/realtime/config';
import type { EventOf, RealtimeEvent, VehiclePosition } from '@/realtime/schemas';
import type { Channel } from '@/realtime/types';

type Schemas = components['schemas'];
type LiveVehicles = Schemas['LiveVehiclesResponse'];
type LiveVehicle = Schemas['LiveVehicleResponse'];
type AlertResponse = Schemas['AlertResponse'];
type JobRunResponse = Schemas['JobRunResponse'];
interface Page<T> {
  items: T[];
  nextCursor?: string;
}

// ---------------------------------------------------------------------------------------------------------------------
// Helpers

/** The filter object a list query was keyed with (`keys.*.list(filters)`), `{}` when it has none. */
function filtersOf(query: Query, position: number): Record<string, unknown> {
  const filters = query.queryKey[position];
  return isRecord(filters) ? filters : {};
}

/** A filter value may be one scalar or a list; `undefined` means "no constraint". */
function accepts(filter: unknown, value: string | number | undefined): boolean {
  if (filter === undefined || (Array.isArray(filter) && filter.length === 0)) return true;
  if (value === undefined) return false;
  const wanted = (Array.isArray(filter) ? filter : [filter]) as unknown[];
  return wanted.some((item) => String(item) === String(value));
}

function patch(queryClient: QueryClient, query: Query, update: (cached: unknown) => unknown) {
  // `undefined` from an updater leaves the cache alone: nothing is created for a query that has not loaded.
  queryClient.setQueryData(query.queryKey, (cached: unknown) => (cached === undefined ? undefined : update(cached)));
}

// ---------------------------------------------------------------------------------------------------------------------
// vehicles

function liveQueries(queryClient: QueryClient): Query[] {
  return queryClient.getQueryCache().findAll({ queryKey: keys.vehicles.liveAll() });
}

/** Applies `change` to the items of every cached live-vehicles snapshot; unchanged snapshots keep their identity. */
function patchLive(
  queryClient: QueryClient,
  change: (items: LiveVehicle[], query: Query) => LiveVehicle[],
  only?: (query: Query) => boolean,
) {
  for (const query of liveQueries(queryClient)) {
    if (only && !only(query)) continue;
    patch(queryClient, query, (cached) =>
      mapBody<LiveVehicles>(cached, (body) => {
        if (!Array.isArray(body.items)) return body;
        const items = change(body.items, query);
        return items === body.items ? body : { ...body, items, count: items.length };
      }),
    );
  }
}

/**
 * `vehicles.batch`: overwrites the position fields of vehicles by `vehicleId`, keeps `delaySeconds`, `headsign`,
 * `label`, `stopArrivalAt` and the bunching overlay of the snapshot, and appends vehicles it has not seen. Snapshots
 * filtered to other routes are left alone, and an older fix never replaces a newer one.
 */
export function applyVehiclesBatch(
  queryClient: QueryClient,
  batch: { routeId: string; vehicles: readonly VehiclePosition[] },
) {
  patchLive(
    queryClient,
    (items) => {
      const index = new Map(items.map((item, position) => [item.vehicleId, position]));
      const next = [...items];
      let changed = false;
      for (const fix of batch.vehicles) {
        const position = index.get(fix.vehicleId);
        const current = position === undefined ? undefined : next[position];
        if (position === undefined || !current) {
          index.set(fix.vehicleId, next.length);
          next.push({ ...fix, routeId: batch.routeId });
          changed = true;
        } else if (Date.parse(fix.eventTimestamp) >= Date.parse(current.eventTimestamp)) {
          next[position] = {
            ...current,
            tripId: fix.tripId,
            directionId: fix.directionId,
            lat: fix.lat,
            lon: fix.lon,
            bearing: fix.bearing ?? current.bearing,
            speedMps: fix.speedMps ?? current.speedMps,
            currentStatus: fix.currentStatus,
            stopId: fix.stopId,
            currentStopSequence: fix.currentStopSequence,
            occupancyStatus: fix.occupancyStatus ?? current.occupancyStatus,
            eventTimestamp: fix.eventTimestamp,
          };
          changed = true;
        }
      }
      return changed ? next : items;
    },
    (query) => {
      const routes = query.queryKey[2];
      return !Array.isArray(routes) || routes.length === 0 || routes.includes(batch.routeId);
    },
  );
}

/** `heartbeat`: hides vehicles whose last fix is more than 5 minutes older than `businessNow` (DOC-33 §5.1). */
export function hideStaleVehicles(queryClient: QueryClient, businessNow: string) {
  const cutoff = Date.parse(businessNow) - VEHICLE_STALE_MS;
  if (Number.isNaN(cutoff)) return;
  patchLive(queryClient, (items) => {
    const fresh = items.filter((item) => !(Date.parse(item.eventTimestamp) < cutoff));
    return fresh.length === items.length ? items : fresh;
  });
}

/** `bunching.opened`: puts the overlay on both vehicles of the pair. */
export function openBunchingOverlay(queryClient: QueryClient, data: EventOf<'bunching.opened'>['data']) {
  const overlay = (role: string, partner: string) => ({
    episodeId: data.id,
    role,
    partnerVehicleId: partner,
    gapSeconds: data.gapSeconds,
    headwaySeconds: data.headwaySeconds,
  });
  patchLive(queryClient, (items) => {
    const pair = [data.vehicleLeader, data.vehicleFollower];
    if (!items.some((item) => pair.includes(item.vehicleId))) return items;
    return items.map((item) => {
      if (item.vehicleId === data.vehicleLeader) return { ...item, bunching: overlay('LEADER', data.vehicleFollower) };
      if (item.vehicleId === data.vehicleFollower)
        return { ...item, bunching: overlay('FOLLOWER', data.vehicleLeader) };
      return item;
    });
  });
}

/** `bunching.closed`: removes the overlay of that episode wherever it is. */
export function closeBunchingOverlay(queryClient: QueryClient, episodeId: string) {
  patchLive(queryClient, (items) => {
    if (!items.some((item) => item.bunching?.episodeId === episodeId)) return items;
    return items.map((item) => {
      if (item.bunching?.episodeId !== episodeId) return item;
      const rest = { ...item };
      delete rest.bunching;
      return rest;
    });
  });
}

// ---------------------------------------------------------------------------------------------------------------------
// stops

/** Marks stop details stale when they belong to the route (or are one of the named stops). */
export function invalidateStopDetails(queryClient: QueryClient, routeId?: string, stopIds: readonly string[] = []) {
  void queryClient.invalidateQueries({
    predicate: (query) => {
      const [root, stopId, leaf] = query.queryKey;
      if (root !== 'stops' || leaf !== 'detail') return false;
      if (typeof stopId === 'string' && stopIds.includes(stopId)) return true;
      if (routeId === undefined) return false;
      const stop = bodyOf<Schemas['StopDetailResponse']>(query.state.data);
      return (
        (stop?.routes.some((route) => route.routeId === routeId) ?? false) ||
        (stop?.activeDisruptions.some((disruption) => disruption.routeId === routeId) ?? false)
      );
    },
  });
}

// ---------------------------------------------------------------------------------------------------------------------
// alerts

export function toAlertResponse(record: EventOf<'alert.created'>['data']): AlertResponse {
  return { ...record, link: record.link ?? '' };
}

/** Whether an alert belongs in a list queried with these filters. Range queries (`from`/`to`) are history, not live. */
export function alertMatches(filters: Record<string, unknown>, alert: AlertResponse): boolean {
  if (filters.from !== undefined || filters.to !== undefined) return false;
  if (filters.state === 'open' && alert.resolvedAt) return false;
  if (filters.state === 'unacknowledged' && (alert.resolvedAt || alert.acknowledgedAt)) return false;
  return (
    accepts(filters.routeId, alert.routeId) &&
    accepts(filters.type, alert.type) &&
    accepts(filters.severity, alert.severity) &&
    accepts(filters.audience, alert.audience)
  );
}

function alertLists(queryClient: QueryClient): Query[] {
  return queryClient.getQueryCache().findAll({ queryKey: keys.alerts.all() });
}

/**
 * `alert.created` / `alert.updated`: the event carries the whole record, so it replaces the cached one by `id` (no
 * field merge). A new alert goes to the head of the first page of every list whose filter it matches; a list it no
 * longer matches (say `state=open` after the alert resolved) loses it. `mode` is `updated` for an alert that is not on
 * the loaded pages: it is inserted only if it is newer than the page's head, because otherwise it lives on a page not
 * loaded yet.
 */
export function upsertAlert(queryClient: QueryClient, alert: AlertResponse, mode: 'created' | 'updated') {
  for (const query of alertLists(queryClient)) {
    const matches = alertMatches(filtersOf(query, 2), alert);
    patch(queryClient, query, (cached) =>
      mapPages<Page<AlertResponse>>(cached, (page, index) => {
        if (!Array.isArray(page.items)) return page;
        const at = page.items.findIndex((item) => item.id === alert.id);
        if (at >= 0) {
          return { ...page, items: matches ? page.items.with(at, alert) : page.items.filter((_, i) => i !== at) };
        }
        const head = page.items[0];
        if (index === 0 && matches && (mode === 'created' || !head || alert.createdAt >= head.createdAt)) {
          return { ...page, items: [alert, ...page.items] };
        }
        return page;
      }),
    );
  }
}

/** `alert.retracted`: removes the alert from every loaded page. */
export function removeAlert(queryClient: QueryClient, id: string) {
  for (const query of alertLists(queryClient)) {
    patch(queryClient, query, (cached) =>
      mapPages<Page<AlertResponse>>(cached, (page) => {
        if (!Array.isArray(page.items) || !page.items.some((item) => item.id === id)) return page;
        return { ...page, items: page.items.filter((item) => item.id !== id) };
      }),
    );
  }
}

/** The newest `createdAt` among the alerts in the cache, for the polling fallback's `since` (DOC-26 §8.3). */
export function newestAlertCreatedAt(queryClient: QueryClient): string | undefined {
  let newest: string | undefined;
  for (const query of alertLists(queryClient)) {
    const pages =
      isRecord(query.state.data) && Array.isArray(query.state.data.pages) ? query.state.data.pages : [query.state.data];
    for (const page of pages) {
      for (const alert of bodyOf<Page<AlertResponse>>(page)?.items ?? []) {
        if (newest === undefined || alert.createdAt > newest) newest = alert.createdAt;
      }
    }
  }
  return newest;
}

// ---------------------------------------------------------------------------------------------------------------------
// jobs

function jobLists(queryClient: QueryClient): Query[] {
  return queryClient
    .getQueryCache()
    .findAll({ queryKey: keys.etl.jobs.all() })
    .filter((query) => query.queryKey[2] === 'list');
}

/**
 * `job.run`: replaces the run with the same `runId` on the loaded pages (fields the event does not carry, such as
 * `durationMs`, stay) and inserts a new run at the head of the first page when the list's filter accepts it.
 */
export function upsertJobRun(queryClient: QueryClient, fresh: JobRunResponse) {
  for (const query of jobLists(queryClient)) {
    const filters = filtersOf(query, 3);
    const matches =
      filters.to === undefined &&
      accepts(filters.kind, fresh.kind) &&
      accepts(filters.name, fresh.name) &&
      accepts(filters.status, fresh.status);
    patch(queryClient, query, (cached) =>
      mapPages<Page<JobRunResponse>>(cached, (page, index) => {
        if (!Array.isArray(page.items)) return page;
        const at = page.items.findIndex((item) => item.runId === fresh.runId);
        const current = page.items[at];
        if (current) return { ...page, items: page.items.with(at, { ...current, ...fresh }) };
        return index === 0 && matches ? { ...page, items: [fresh, ...page.items] } : page;
      }),
    );
  }
}

// ---------------------------------------------------------------------------------------------------------------------
// dead letters

/** `dlq.changed` `UPDATED`: sets the new `status` on the row with that id wherever it is listed. */
export function setDeadLetterStatus(queryClient: QueryClient, id: string, status: string) {
  for (const query of queryClient.getQueryCache().findAll({ queryKey: keys.etl.dlq.listAll() })) {
    patch(queryClient, query, (cached) =>
      mapPages<Page<Schemas['DeadLetterItemResponse']>>(cached, (page) => {
        if (!Array.isArray(page.items) || !page.items.some((item) => item.id === id && item.status !== status))
          return page;
        return { ...page, items: page.items.map((item) => (item.id === id ? { ...item, status } : item)) };
      }),
    );
  }
}

// ---------------------------------------------------------------------------------------------------------------------
// resync

/** The cache keys each channel feeds, by the table of DOC-26 §9. */
const CHANNEL_KEYS: Record<Channel, readonly QueryKey[]> = {
  vehicles: [keys.vehicles.all()],
  // Alerts-channel events also move the bunching overlay on live vehicles and the stop details.
  alerts: [
    keys.alerts.all(),
    ['insights', 'bunching'],
    ['insights', 'dispatch'],
    ['insights', 'disruption'],
    keys.vehicles.liveAll(),
  ],
  jobs: [keys.etl.jobs.all(), keys.etl.jobAll()],
  dlq: [keys.etl.dlq.all()],
};

/** `resync`: every key of the listed channels is stale, and the active ones refetch. */
export function invalidateChannels(queryClient: QueryClient, channels: readonly string[]) {
  const known = (Object.keys(CHANNEL_KEYS) as Channel[]).filter((channel) => channels.includes(channel));
  for (const channel of known) {
    for (const queryKey of CHANNEL_KEYS[channel]) void queryClient.invalidateQueries({ queryKey });
  }
  if (known.includes('alerts')) {
    void queryClient.invalidateQueries({
      predicate: (query) => query.queryKey[0] === 'stops' && query.queryKey[2] === 'detail',
    });
  }
}

// ---------------------------------------------------------------------------------------------------------------------
// dispatch

export interface Handlers {
  apply(event: RealtimeEvent): void;
  /** Cancels the pending coalesced invalidations. */
  dispose(): void;
}

/** Applies events to `queryClient`; invalidations that events would repeat are coalesced to one per 2 s (§9). */
export function createHandlers(queryClient: QueryClient): Handlers {
  const pending = new Map<string, ReturnType<typeof setTimeout>>();
  /** Runs `action` once, `delay` (2 s) after the first call; calls in between join it. */
  const coalesce = (key: string, action: () => void, delay = INVALIDATE_THROTTLE_MS) => {
    if (pending.has(key)) return;
    pending.set(
      key,
      setTimeout(() => {
        pending.delete(key);
        action();
      }, delay),
    );
  };
  const invalidate = (queryKey: QueryKey) => {
    void queryClient.invalidateQueries({ queryKey });
  };

  return {
    apply(event) {
      switch (event.type) {
        case 'vehicles.batch':
          applyVehiclesBatch(queryClient, event.data);
          break;
        case 'heartbeat':
          hideStaleVehicles(queryClient, event.data.businessNow);
          break;
        case 'bunching.opened':
          openBunchingOverlay(queryClient, event.data);
          invalidate(['insights', 'bunching']);
          break;
        case 'bunching.closed':
          closeBunchingOverlay(queryClient, event.data.id);
          invalidate(['insights', 'bunching']);
          break;
        case 'dispatch.suggested':
          invalidate(['insights', 'bunching']);
          invalidate(['insights', 'dispatch']);
          break;
        case 'disruption.opened':
        case 'disruption.closed':
          invalidate(['insights', 'disruption']);
          invalidateStopDetails(queryClient, event.routeId ?? event.data.routeId, event.data.affectedStopIds);
          break;
        case 'alert.created':
          upsertAlert(queryClient, toAlertResponse(event.data), 'created');
          // The sidebar counts ticketing anomalies from their own endpoint (screens/shell-and-navigation §5).
          if (event.data.type === 'TICKETING_ANOMALY') invalidate(keys.insights.ticketingBadge());
          break;
        case 'alert.updated':
          upsertAlert(queryClient, toAlertResponse(event.data), 'updated');
          break;
        case 'alert.retracted':
          removeAlert(queryClient, event.data.id);
          invalidateStopDetails(queryClient, event.routeId ?? event.data.routeId);
          break;
        case 'job.run':
          upsertJobRun(queryClient, event.data);
          coalesce(`job:${event.data.runId}`, () => {
            invalidate(keys.etl.job(event.data.runId));
          });
          // The batch-job counts of E-31 (Pipeline, the sidebar dot): at most every 10 s (screens/ops-console-jobs §5).
          coalesce(
            'jobs:summary',
            () => {
              invalidate(keys.etl.jobs.summaryAll());
            },
            JOB_SUMMARY_THROTTLE_MS,
          );
          break;
        case 'dlq.changed': {
          const change = event.data;
          if (change.kind === 'UPDATED') {
            setDeadLetterStatus(queryClient, change.id, change.status);
            coalesce(`dlq:detail:${change.id}`, () => {
              invalidate(keys.etl.dlq.detail(change.id));
            });
            coalesce('dlq:summary', () => {
              invalidate(keys.etl.dlq.summary());
            });
          } else {
            coalesce('dlq:lists', () => {
              invalidate(keys.etl.dlq.listAll());
              invalidate(keys.etl.dlq.summary());
            });
          }
          break;
        }
        case 'resync':
          invalidateChannels(queryClient, event.data.channels);
          break;
      }
    },
    dispose() {
      for (const timer of pending.values()) clearTimeout(timer);
      pending.clear();
    },
  };
}
