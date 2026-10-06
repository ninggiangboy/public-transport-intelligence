import type { QueryClient } from '@tanstack/react-query';

import type { WithAsOf } from '@/api/client';
import { keys } from '@/api/keys';
import type { DeadLetter, DeadLetterDetail } from '@/features/dlq/model';
import { mapPages } from '@/realtime/cache-shapes';

// Optimistic changes to the cached dead letters: the rows of every list and the open detail, in the shapes the
// real-time handlers also patch (DOC-26 §9, realtime/handlers.ts `setDeadLetterStatus`).

interface Page {
  items: DeadLetter[];
  nextCursor?: string;
}

/**
 * Applies `row` to the item `id` of every cached list and `detail` to its open detail. Returns the entries as they
 * were, for a rollback.
 */
export function patchDeadLetter(
  queryClient: QueryClient,
  id: string,
  row: (item: DeadLetter) => DeadLetter,
  detail?: (record: DeadLetterDetail) => DeadLetterDetail,
) {
  const detailKey = keys.etl.dlq.detail(id);
  const snapshot = [
    ...queryClient.getQueriesData({ queryKey: keys.etl.dlq.listAll() }),
    ...queryClient.getQueriesData({ queryKey: detailKey }),
  ];
  queryClient.setQueriesData({ queryKey: keys.etl.dlq.listAll() }, (cached: unknown) =>
    cached === undefined
      ? undefined
      : mapPages<Page>(cached, (page) =>
          Array.isArray(page.items) && page.items.some((item) => item.id === id)
            ? { ...page, items: page.items.map((item) => (item.id === id ? row(item) : item)) }
            : page,
        ),
  );
  if (detail) {
    queryClient.setQueryData(detailKey, (cached: WithAsOf<DeadLetterDetail> | undefined) =>
      cached ? { ...cached, data: detail(cached.data) } : undefined,
    );
  }
  return () => {
    for (const [queryKey, data] of snapshot) queryClient.setQueryData(queryKey, data);
  };
}

/** The status the record has in the cache (a list row, else the detail), for a rollback or a bulk filter. */
export function cachedStatus(queryClient: QueryClient, id: string): string | undefined {
  for (const [, cached] of queryClient.getQueriesData({ queryKey: keys.etl.dlq.listAll() })) {
    let found: string | undefined;
    mapPages<Page>(cached, (page) => {
      found ??= Array.isArray(page.items) ? page.items.find((item) => item.id === id)?.status : undefined;
      return page;
    });
    if (found) return found;
  }
  return queryClient.getQueryData<WithAsOf<DeadLetterDetail>>(keys.etl.dlq.detail(id))?.data.status;
}

/** Sets the status of the record in every cache, and takes its actions away until the server answers. */
export function setStatus(queryClient: QueryClient, id: string, status: string) {
  return patchDeadLetter(
    queryClient,
    id,
    (item) => ({ ...item, status }),
    (record) => ({ ...record, status, allowedActions: [] }),
  );
}
