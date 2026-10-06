import type { InfiniteData, QueryClient } from '@tanstack/react-query';

import { api, read, type WithAsOf } from '@/api/client';
import type { components, operations } from '@/api/generated/schema';

// The dead-letter lists load their first page again on `dlq.changed` `CREATED` and `BULK_UPDATED` (DOC-26 §9) and merge
// it over the pages already loaded. A full refetch would be one request per 200 rows, 50 of them for 10,000.

type Item = components['schemas']['DeadLetterItemResponse'];
interface Page {
  items: Item[];
  nextCursor?: string;
}
type Cached = InfiniteData<WithAsOf<Page>>;
type ListQuery = NonNullable<operations['listDeadLetters']['parameters']['query']>;

/** E-40 page size; the same as the screen's (features/dlq/model.ts `LIST_PAGE`). */
const PAGE = 200;

/** Newest first, as E-40 orders its pages: `createdAt` descending, then `id` descending. */
const newerFirst = (a: Item, b: Item) => Date.parse(b.createdAt) - Date.parse(a.createdAt) || b.id.localeCompare(a.id);

/**
 * `fresh` is the first page of the same list. A row it has replaces the cached one where it stands; a row it lacks that
 * is as new as its last row has left the list (it no longer matches the filters), so it goes; the rows that are new go
 * to the head. What lies beyond the first page is not touched.
 */
export function mergeDeadLetterHead(cached: Cached | undefined, fresh: Page): Cached | undefined {
  if (!cached || cached.pages.length === 0) return cached;
  const byId = new Map(fresh.items.map((item) => [item.id, item]));
  const oldest = fresh.nextCursor === undefined ? undefined : fresh.items.at(-1);
  const seen = new Set<string>();
  const pages = cached.pages.map((page) => {
    const items = page.data.items.flatMap((item) => {
      const next = byId.get(item.id);
      if (next) {
        seen.add(item.id);
        return [{ ...item, ...next }];
      }
      // Within the span the first page covers, a row that is not on it is gone.
      return oldest === undefined || newerFirst(item, oldest) <= 0 ? [] : [item];
    });
    return { ...page, data: { ...page.data, items } };
  });
  const added = fresh.items.filter((item) => !seen.has(item.id));
  const [head, ...rest] = pages;
  if (!head) return { ...cached, pages };
  const items = added.length === 0 ? head.data.items : [...added, ...head.data.items].sort(newerFirst);
  return { ...cached, pages: [{ ...head, data: { ...head.data, items } }, ...rest] };
}

/**
 * `dlq.changed` `CREATED` and `BULK_UPDATED` mark the lists stale without loading them (realtime/handlers.ts). This
 * watches for that and loads the first page of the lists that are on screen, then merges it. A list nobody watches is
 * refetched when it is opened.
 */
export function watchDeadLetterHeads(queryClient: QueryClient) {
  return queryClient.getQueryCache().subscribe((event) => {
    if (event.type !== 'updated' || event.action.type !== 'invalidate') return;
    const { query } = event;
    // `['etl', 'dlq', 'list', filters]`, the shape of `keys.etl.dlq.list`.
    const key = query.queryKey as readonly unknown[];
    const filters = key[3];
    if (key[0] !== 'etl' || key[1] !== 'dlq' || key[2] !== 'list' || typeof filters !== 'object' || filters === null)
      return;
    if (query.getObserversCount() === 0 || query.state.fetchStatus === 'fetching') return;
    read(api.GET('/api/v1/etl/dlq', { params: { query: { ...(filters as ListQuery), limit: PAGE } } })).then(
      ({ data }) => {
        queryClient.setQueryData(query.queryKey, (cached: Cached | undefined) => mergeDeadLetterHead(cached, data));
      },
      // The next event, the focus or the 60 s safety net try again.
      () => undefined,
    );
  });
}
