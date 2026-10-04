import type { QueryClient } from '@tanstack/react-query';

import { keys } from '@/api/keys';
import type { Alert } from '@/features/alerts/model';
import { mapPages } from '@/realtime/cache-shapes';

// Optimistic changes to every cached alert list (the feed pages and the sidebar badge), in the shapes the real-time
// handlers also patch (DOC-26 §9).

interface Page {
  items: Alert[];
  nextCursor?: string;
}

/** Applies `change` to the alert `id` wherever it is cached; returns the entries as they were, for a rollback. */
export function patchAlert(queryClient: QueryClient, id: string, change: (alert: Alert) => Alert) {
  const snapshot = queryClient.getQueriesData({ queryKey: keys.alerts.all() });
  queryClient.setQueriesData({ queryKey: keys.alerts.all() }, (cached: unknown) =>
    cached === undefined
      ? undefined
      : mapPages<Page>(cached, (page) =>
          Array.isArray(page.items) && page.items.some((alert) => alert.id === id)
            ? { ...page, items: page.items.map((alert) => (alert.id === id ? change(alert) : alert)) }
            : page,
        ),
  );
  return () => {
    for (const [queryKey, data] of snapshot) queryClient.setQueryData(queryKey, data);
  };
}

/** Removes the alert `id` from the cached lists whose key matches `where` (all of them by default). */
export function dropAlert(
  queryClient: QueryClient,
  id: string,
  where: (queryKey: readonly unknown[]) => boolean = () => true,
) {
  for (const query of queryClient.getQueryCache().findAll({ queryKey: keys.alerts.all() })) {
    if (!where(query.queryKey)) continue;
    queryClient.setQueryData(query.queryKey, (cached: unknown) =>
      cached === undefined
        ? undefined
        : mapPages<Page>(cached, (page) =>
            Array.isArray(page.items) ? { ...page, items: page.items.filter((alert) => alert.id !== id) } : page,
          ),
    );
  }
}
