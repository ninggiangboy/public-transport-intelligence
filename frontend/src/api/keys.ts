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
  vehicles: {
    all: () => ['vehicles'] as const,
    /** Prefix of every live-vehicles query, whatever its route filter. */
    liveAll: () => ['vehicles', 'live'] as const,
    live: (routeIds?: readonly string[]) => ['vehicles', 'live', normalizeList(routeIds)] as const,
  },
  alerts: {
    all: () => ['alerts'] as const,
    list: (filters?: Filters) => ['alerts', 'list', normalizeFilters(filters)] as const,
  },
  insights: {
    bunching: (filters?: Filters) => ['insights', 'bunching', normalizeFilters(filters)] as const,
    dispatch: (filters?: Filters) => ['insights', 'dispatch', normalizeFilters(filters)] as const,
    disruption: (filters?: Filters) => ['insights', 'disruption', normalizeFilters(filters)] as const,
  },
  stops: {
    detail: (stopId: string) => ['stops', stopId, 'detail'] as const,
  },
  etl: {
    jobs: {
      all: () => ['etl', 'jobs'] as const,
      list: (filters?: Filters) => ['etl', 'jobs', 'list', normalizeFilters(filters)] as const,
      summary: (filters?: Filters) => ['etl', 'jobs', 'summary', normalizeFilters(filters)] as const,
    },
    jobAll: () => ['etl', 'job'] as const,
    job: (runId: string) => ['etl', 'job', runId] as const,
    dlq: {
      all: () => ['etl', 'dlq'] as const,
      list: (filters?: Filters) => ['etl', 'dlq', 'list', normalizeFilters(filters)] as const,
      listAll: () => ['etl', 'dlq', 'list'] as const,
      summary: () => ['etl', 'dlq', 'summary'] as const,
      detail: (id: string) => ['etl', 'dlq', 'detail', id] as const,
    },
  },
} as const;
