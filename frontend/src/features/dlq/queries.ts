import { infiniteQueryOptions, queryOptions } from '@tanstack/react-query';

import { api, read } from '@/api/client';
import { keys } from '@/api/keys';
import { actionFilters, LIST_PAGE, replayOpen, type ListFilters } from '@/features/dlq/model';
import type { DlqSearch } from '@/features/dlq/search';

// Queries of Dead letters (DOC-36 screens/ops-console-dlq §5). The summary shares its key with the sidebar and the
// Pipeline, so it is fetched once.

export { dlqSummaryQuery } from '@/features/ops-jobs/queries';

/**
 * E-40 in keyset pages of 200. No timer: a list refetches every loaded page, and 10,000 rows are 50 requests. New and
 * changed rows come from `dlq.changed` (realtime/dlq-heads.ts), which fetches the first page only.
 */
export function listQuery(filters: ListFilters) {
  return infiniteQueryOptions({
    queryKey: keys.etl.dlq.list({ ...filters }),
    queryFn: ({ pageParam }) =>
      read(api.GET('/api/v1/etl/dlq', { params: { query: { ...filters, limit: LIST_PAGE, cursor: pageParam } } })),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.data.nextCursor,
    refetchInterval: false,
  });
}

/** E-42; `dlq.changed` `UPDATED` of the same id invalidates it. A 404 is final: the record was swept (§6). */
export function detailQuery(id: string) {
  return queryOptions({
    queryKey: keys.etl.dlq.detail(id),
    queryFn: () => read(api.GET('/api/v1/etl/dlq/{id}', { params: { path: { id } } })),
  });
}

/** E-48, newest first. A sliding window, keyed by its name, so that the machine clock of each fetch ends it. */
export function actionsQuery(search: DlqSearch) {
  return infiniteQueryOptions({
    queryKey: keys.etl.dlq.actions({
      ...(search.action?.length ? { action: search.action } : {}),
      ...(search.actor ? { actorType: search.actor } : {}),
      window: search.window ?? '24h',
    }),
    queryFn: ({ pageParam }) =>
      read(
        api.GET('/api/v1/etl/dlq/actions', {
          params: { query: { ...actionFilters(search, Date.now()), limit: LIST_PAGE, cursor: pageParam } },
        }),
      ),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.data.nextCursor,
    refetchInterval: false,
  });
}

/** E-52, polled every 2 s while the replay has not ended and the detail is open (§5). */
export function replayQuery(id: string) {
  return queryOptions({
    queryKey: keys.etl.replay(id),
    queryFn: () => read(api.GET('/api/v1/etl/replays/{id}', { params: { path: { id } } })),
    refetchInterval: (query) => {
      const status = query.state.data?.data.status;
      return status === undefined || replayOpen(status) ? 2_000 : false;
    },
  });
}
