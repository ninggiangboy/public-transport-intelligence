/**
 * TanStack Query keys (DOC-26 §9). The real-time handlers invalidate and patch the cache by these shapes, so screens
 * must build their keys here and nowhere else. Arguments are normalised (lists sorted and de-duplicated, empty values
 * dropped), so equal filters always share one cache entry.
 */

/** A filter object: scalars or lists of scalars. */
export type Filters = Record<string, string | number | boolean | readonly (string | number)[] | undefined>;

/** Sorted, de-duplicated copy of a list; `undefined` becomes `[]`. */
export function normalizeList<T extends string | number>(values: readonly T[] | undefined): T[] {
  return [...new Set(values)].sort();
}

/** Drops `undefined` and empty lists, sorts list items and object keys. */
export function normalizeFilters(filters: Filters | undefined): Filters {
  const out: Filters = {};
  for (const name of Object.keys(filters ?? {}).sort()) {
    const value = filters?.[name];
    if (value === undefined) continue;
    if (Array.isArray(value)) {
      if (value.length > 0) out[name] = normalizeList(value as (string | number)[]);
    } else {
      out[name] = value;
    }
  }
  return out;
}

export const keys = {
  /** E-61: who is signed in and with which roles (DOC-34 §3). */
  me: () => ['me'] as const,
  system: {
    /** E-60, polled every 15 s by the FreshnessProvider. */
    freshness: () => ['system', 'freshness'] as const,
  },
  routes: {
    /** E-01: every route of the active feed, cached for the session. */
    list: () => ['routes'] as const,
    /** E-02: directions, stops and geometry of one route. */
    detail: (routeId: string) => ['routes', routeId, 'detail'] as const,
    /** E-04: historical delay per stop of one direction, for a day of week and hour. */
    delayProfile: (routeId: string, params: Filters) =>
      ['routes', routeId, 'delay-profile', normalizeFilters(params)] as const,
    /** E-03: observed delay of one route by hour, day or hour of the week. */
    delays: (routeId: string, params: Filters) => ['routes', routeId, 'delays', normalizeFilters(params)] as const,
  },
  vehicles: {
    all: () => ['vehicles'] as const,
    /** Prefix of every live-vehicles query, whatever its route filter. */
    liveAll: () => ['vehicles', 'live'] as const,
    live: (routeIds?: readonly string[]) => ['vehicles', 'live', normalizeList(routeIds)] as const,
  },
  alerts: {
    all: () => ['alerts'] as const,
    list: (filters?: Filters) => ['alerts', 'list', normalizeFilters(filters)] as const,
    /** The unacknowledged alerts behind the sidebar count; under `alerts`, so alert events patch it. */
    badge: () => ['alerts', 'badge'] as const,
  },
  insights: {
    /** E-14: OTP by route over service dates `from`…`to`, optionally for some `routeIds`. */
    otp: (filters: Filters) => ['insights', 'otp', normalizeFilters(filters)] as const,
    bunching: (filters?: Filters) => ['insights', 'bunching', normalizeFilters(filters)] as const,
    dispatch: (filters?: Filters) => ['insights', 'dispatch', normalizeFilters(filters)] as const,
    disruption: (filters?: Filters) => ['insights', 'disruption', normalizeFilters(filters)] as const,
    /** E-13: one disruption episode; under `['insights', 'disruption']`, so disruption events invalidate it. */
    disruptionDetail: (id: string) => ['insights', 'disruption', 'detail', id] as const,
    /** E-11: a bunching episode with its dispatch suggestion. */
    bunchingDetail: (id: string) => ['insights', 'bunching', 'detail', id] as const,
    /** E-16: a ticketing anomaly with its summary. */
    ticketingDetail: (id: string) => ['insights', 'ticketing', 'detail', id] as const,
    /** Ticketing anomalies of the last 24 hours behind the sidebar count. */
    ticketingBadge: () => ['insights', 'ticketing', 'badge'] as const,
  },
  stops: {
    detail: (stopId: string) => ['stops', stopId, 'detail'] as const,
    search: (q: string) => ['stops', 'search', q] as const,
    /** E-08, refetched every 30 s while the stop is open. */
    arrivals: (stopId: string, limit: number) => ['stops', stopId, 'arrivals', limit] as const,
  },
  etl: {
    jobs: {
      all: () => ['etl', 'jobs'] as const,
      list: (filters?: Filters) => ['etl', 'jobs', 'list', normalizeFilters(filters)] as const,
      summary: (filters?: Filters) => ['etl', 'jobs', 'summary', normalizeFilters(filters)] as const,
      /** Prefix of every E-31 entry, whatever its period: `job.run` refreshes them at most every 10 s. */
      summaryAll: () => ['etl', 'jobs', 'summary'] as const,
    },
    jobAll: () => ['etl', 'job'] as const,
    job: (runId: string) => ['etl', 'job', runId] as const,
    /** E-34: a run, restart or stop request, polled until the batch service picks it up. */
    jobRequest: (id: string) => ['etl', 'job-request', id] as const,
    /** E-37: the lineage of one batch id. */
    batch: (batchId: string) => ['etl', 'batch', batchId] as const,
    dlq: {
      all: () => ['etl', 'dlq'] as const,
      list: (filters?: Filters) => ['etl', 'dlq', 'list', normalizeFilters(filters)] as const,
      listAll: () => ['etl', 'dlq', 'list'] as const,
      summary: () => ['etl', 'dlq', 'summary'] as const,
      detail: (id: string) => ['etl', 'dlq', 'detail', id] as const,
    },
    flags: () => ['etl', 'flags'] as const,
    replays: (filters?: Filters) => ['etl', 'replays', normalizeFilters(filters)] as const,
  },
} as const;
