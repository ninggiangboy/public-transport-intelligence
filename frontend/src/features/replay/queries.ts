import { infiniteQueryOptions, queryOptions } from '@tanstack/react-query';

import { api, read } from '@/api/client';
import { keys } from '@/api/keys';
import { isActive } from '@/features/replay/model';
import type { ReplayKind, ReplayStatus } from '@/features/replay/search';

// Queries of Replay (DOC-36 screens/ops-console-replay §5). E-51 and E-52 are on the audit axis (machine clock).

/** The page of the history: 50 rows, the API's default 7 days. */
export const HISTORY_PAGE = 50;
const HISTORY_REFRESH_MS = 60_000;
/** While the first page has a replay waiting or running. */
const HISTORY_ACTIVE_REFRESH_MS = 5_000;
/** E-52 while the drawer shows a replay that runs (ADR-0013). */
const DETAIL_REFRESH_MS = 2_000;
/** A running row of the history follows its replay a little slower than the drawer. */
const ROW_REFRESH_MS = 5_000;

export interface HistoryFilters {
  kind?: ReplayKind;
  status?: ReplayStatus[];
}

/**
 * E-51 by pages of 50. The key carries `limit`, so it never meets the sidebar's count of running replays
 * (`limit: 10`, a plain query) in the cache.
 */
export function historyQuery(filters: HistoryFilters) {
  return infiniteQueryOptions({
    queryKey: keys.etl.replays({ ...filters, limit: HISTORY_PAGE }),
    queryFn: ({ pageParam }) =>
      read(
        api.GET('/api/v1/etl/replays', {
          params: { query: { ...filters, limit: HISTORY_PAGE, ...(pageParam ? { cursor: pageParam } : {}) } },
        }),
      ),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.data.nextCursor,
    refetchInterval: (query) =>
      query.state.data?.pages[0]?.data.items.some((replay) => isActive(replay.status))
        ? HISTORY_ACTIVE_REFRESH_MS
        : HISTORY_REFRESH_MS,
  });
}

/** The replays waiting or running: the "{n} running" badge, from the sidebar's own entry (use-nav-counts). */
export function activeQuery() {
  return queryOptions({
    queryKey: keys.etl.replays({ status: ['PENDING', 'RUNNING'], limit: 10 }),
    queryFn: () =>
      read(api.GET('/api/v1/etl/replays', { params: { query: { status: ['RUNNING', 'PENDING'], limit: 10 } } })),
  });
}

/** E-52, every 2 s while it waits or runs (the drawer), or every 5 s (a running row); stops once it ends. */
export function replayQuery(id: string, where: 'drawer' | 'row' = 'drawer') {
  return queryOptions({
    queryKey: keys.etl.replay(id),
    queryFn: () => read(api.GET('/api/v1/etl/replays/{id}', { params: { path: { id } } })),
    refetchInterval: (query) => {
      const status = query.state.data?.data.status;
      if (status !== undefined && !isActive(status)) return false;
      return where === 'drawer' ? DETAIL_REFRESH_MS : ROW_REFRESH_MS;
    },
  });
}

export interface EstimateParams {
  source: string;
  fromTs: string;
  toTs: string;
}

/** E-53 (operator only): kept 30 s, never refetched in the background. */
export function estimateQuery(params: EstimateParams) {
  return queryOptions({
    queryKey: keys.etl.replayEstimate({ ...params }),
    queryFn: () => read(api.GET('/api/v1/etl/replays/estimate', { params: { query: params } })),
    staleTime: 30_000,
    refetchInterval: false,
    refetchOnWindowFocus: false,
  });
}
