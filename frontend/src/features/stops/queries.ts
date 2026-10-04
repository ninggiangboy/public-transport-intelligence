import { queryOptions } from '@tanstack/react-query';

import { api, read } from '@/api/client';
import { keys } from '@/api/keys';

// Queries of the stop screens (DOC-36 screens/stop-detail §5).

const FIVE_MINUTES = 5 * 60_000;
const HOUR = 60 * 60_000;

/** E-08 is refetched this often while the stop is open (UC-02 step 4); it has no event stream. */
export const ARRIVALS_POLL_MS = 30_000;
export const ARRIVALS_LIMIT = 10;
export const ARRIVALS_LIMIT_MORE = 30;
export const SEARCH_LIMIT = 20;

export function stopSearchQuery(q: string) {
  return queryOptions({
    queryKey: keys.stops.search(q),
    queryFn: () => read(api.GET('/api/v1/stops', { params: { query: { q, limit: SEARCH_LIMIT } } })),
    staleTime: FIVE_MINUTES,
    refetchInterval: false,
  });
}

export function stopDetailQuery(stopId: string) {
  return queryOptions({
    queryKey: keys.stops.detail(stopId),
    queryFn: () => read(api.GET('/api/v1/stops/{stopId}', { params: { path: { stopId } } })),
  });
}

export function arrivalsQuery(stopId: string, limit: number) {
  return queryOptions({
    queryKey: keys.stops.arrivals(stopId, limit),
    queryFn: () => read(api.GET('/api/v1/stops/{stopId}/arrivals', { params: { path: { stopId }, query: { limit } } })),
    refetchInterval: ARRIVALS_POLL_MS,
  });
}

export interface ProfileSlot {
  directionId: number;
  dayOfWeek: number;
  hourOfDay: number;
}

export function delayProfileQuery(routeId: string, slot: ProfileSlot) {
  return queryOptions({
    queryKey: keys.routes.delayProfile(routeId, { ...slot }),
    queryFn: () =>
      read(api.GET('/api/v1/routes/{routeId}/delay-profile', { params: { path: { routeId }, query: slot } })),
    staleTime: HOUR,
    refetchInterval: false,
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
    staleTime: HOUR,
    refetchInterval: false,
  });
}

/** E-13: the delay of a disruption for its callout, and whether it ended. */
export function disruptionQuery(id: string) {
  return queryOptions({
    queryKey: keys.insights.disruptionDetail(id),
    queryFn: () => read(api.GET('/api/v1/insights/disruption/{id}', { params: { path: { id } } })),
  });
}
