import { infiniteQueryOptions, queryOptions } from '@tanstack/react-query';

import { api, read } from '@/api/client';
import { keys } from '@/api/keys';
import type { AlertState, AlertWindow } from '@/features/alerts/search';

// Queries of the alert feed (DOC-36 screens/alert-feed §5).

export const PAGE_SIZE = 50;
const WINDOW_MS: Record<AlertWindow, number> = { '24h': 24 * 3_600_000, '7d': 7 * 24 * 3_600_000 };

/**
 * The list filter, keyed with the E-20 parameter names the real-time handlers match alerts against (DOC-26 §9):
 * `state`, `audience`, `type`, `severity`, `routeId`.
 */
export interface AlertFilters {
  state: AlertState;
  audience?: string[];
  type?: string[];
  severity?: number[];
  routeId?: string[];
  window: AlertWindow;
}

export function alertListQuery(filters: AlertFilters) {
  return infiniteQueryOptions({
    queryKey: keys.alerts.list({ ...filters }),
    queryFn: ({ pageParam }) =>
      read(
        api.GET('/api/v1/alerts', {
          params: {
            query: {
              state: filters.state,
              audience: filters.audience,
              type: filters.type,
              severity: filters.severity?.map(String),
              routeId: filters.routeId,
              // The audit axis: `created_at` against the machine clock (§2).
              from: new Date(Date.now() - WINDOW_MS[filters.window]).toISOString(),
              limit: PAGE_SIZE,
              cursor: pageParam,
            },
          },
        }),
      ),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.data.nextCursor,
  });
}

/** The unacknowledged alerts of the sidebar badge: the count on the "Unacknowledged" tab. */
export function alertBadgeQuery() {
  return queryOptions({
    queryKey: keys.alerts.badge(),
    queryFn: () => read(api.GET('/api/v1/alerts', { params: { query: { state: 'unacknowledged', limit: 100 } } })),
  });
}

export function disruptionDetailQuery(id: string) {
  return queryOptions({
    queryKey: keys.insights.disruptionDetail(id),
    queryFn: () => read(api.GET('/api/v1/insights/disruption/{id}', { params: { path: { id } } })),
  });
}

export function bunchingDetailQuery(id: string) {
  return queryOptions({
    queryKey: keys.insights.bunchingDetail(id),
    queryFn: () => read(api.GET('/api/v1/insights/bunching/{id}', { params: { path: { id } } })),
  });
}

export function ticketingDetailQuery(id: string) {
  return queryOptions({
    queryKey: keys.insights.ticketingDetail(id),
    queryFn: () => read(api.GET('/api/v1/insights/ticketing-anomalies/{id}', { params: { path: { id } } })),
  });
}

export function routeDetailQuery(routeId: string) {
  return queryOptions({
    queryKey: keys.routes.detail(routeId),
    queryFn: () => read(api.GET('/api/v1/routes/{routeId}', { params: { path: { routeId } } })),
    staleTime: Number.POSITIVE_INFINITY,
    refetchInterval: false,
  });
}

export function routesQuery() {
  return queryOptions({
    queryKey: keys.routes.list(),
    queryFn: () => read(api.GET('/api/v1/routes')),
    staleTime: 3_600_000,
    refetchInterval: false,
  });
}
