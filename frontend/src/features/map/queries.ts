import { queryOptions } from '@tanstack/react-query';

import { api, read } from '@/api/client';
import { keys, normalizeList } from '@/api/keys';

// Queries of the live map (DOC-36 screens/live-map §5). Keys come from keys.ts, which the event handlers patch.

const HOUR = 60 * 60_000;

/** E-01: every route and its colour, for the session. */
export function routesQuery() {
  return queryOptions({
    queryKey: keys.routes.list(),
    queryFn: () => read(api.GET('/api/v1/routes')),
    staleTime: HOUR,
    refetchInterval: false,
  });
}

/** E-02: directions, stops and shapes of one route; a feed version never changes them. */
export function routeDetailQuery(routeId: string) {
  return queryOptions({
    queryKey: keys.routes.detail(routeId),
    queryFn: () => read(api.GET('/api/v1/routes/{routeId}', { params: { path: { routeId } } })),
    staleTime: Number.POSITIVE_INFINITY,
    refetchInterval: false,
  });
}

/**
 * E-05: the snapshot that `vehicles.batch` patches (DOC-26 §9) and the poller refreshes every 5 s while the stream is
 * down; the 60 s refetch of the query client is the safety net.
 */
export function liveVehiclesQuery(routeIds: readonly string[]) {
  const routes = normalizeList(routeIds);
  return queryOptions({
    queryKey: keys.vehicles.live(routes),
    queryFn: () =>
      read(
        api.GET('/api/v1/vehicles/live', { params: { query: { routeId: routes.length > 0 ? routes : undefined } } }),
      ),
  });
}

export const DISRUPTION_LIMIT = 50;

/** E-12: the open disruptions of the chip, on the selected routes; disruption events invalidate it. */
export function openDisruptionsQuery(routeIds: readonly string[]) {
  const routes = normalizeList(routeIds);
  return queryOptions({
    queryKey: keys.insights.disruption({ status: 'OPEN', routeId: routes }),
    queryFn: () =>
      read(
        api.GET('/api/v1/insights/disruption', {
          params: {
            query: {
              status: 'OPEN',
              routeId: routes.length > 0 ? routes : undefined,
              limit: DISRUPTION_LIMIT,
            },
          },
        }),
      ),
  });
}

/** E-13. */
export function disruptionDetailQuery(id: string) {
  return queryOptions({
    queryKey: keys.insights.disruptionDetail(id),
    queryFn: () => read(api.GET('/api/v1/insights/disruption/{id}', { params: { path: { id } } })),
  });
}

/** E-11: the episode and its dispatch suggestion. */
export function bunchingDetailQuery(id: string) {
  return queryOptions({
    queryKey: keys.insights.bunchingDetail(id),
    queryFn: () => read(api.GET('/api/v1/insights/bunching/{id}', { params: { path: { id } } })),
  });
}

export const ROUTE_STOPS_LIMIT = 500;

/** E-06 by route: the stops layer from zoom 14 and the transfers of the trip progress. */
export function routeStopsQuery(routeId: string) {
  return queryOptions({
    queryKey: ['stops', 'route', routeId] as const,
    queryFn: () => read(api.GET('/api/v1/stops', { params: { query: { routeId, limit: ROUTE_STOPS_LIMIT } } })),
    staleTime: HOUR,
    refetchInterval: false,
  });
}

export const SEARCH_LIMIT = 8;

/** E-06 by name for "Search the map". */
export function stopSearchQuery(q: string) {
  return queryOptions({
    queryKey: keys.stops.search(q),
    queryFn: () => read(api.GET('/api/v1/stops', { params: { query: { q, limit: SEARCH_LIMIT } } })),
    staleTime: 5 * 60_000,
    refetchInterval: false,
  });
}

export const NEARBY_LIMIT = 5;

/** E-06 by bounding box ("minLon,minLat,maxLon,maxLat") around the user (screens/live-map §4.2). */
export function nearbyStopsQuery(bbox: string) {
  return queryOptions({
    queryKey: ['stops', 'bbox', bbox] as const,
    queryFn: () => read(api.GET('/api/v1/stops', { params: { query: { bbox, limit: 50 } } })),
    staleTime: HOUR,
    refetchInterval: false,
  });
}

export const NEARBY_ARRIVALS = 3;

/** E-08 with `limit=3` for a nearby stop; arrivals have no event stream, so it polls every 30 s. */
export function nearbyArrivalsQuery(stopId: string) {
  return queryOptions({
    queryKey: keys.stops.arrivals(stopId, NEARBY_ARRIVALS),
    queryFn: () =>
      read(
        api.GET('/api/v1/stops/{stopId}/arrivals', {
          params: { path: { stopId }, query: { limit: NEARBY_ARRIVALS } },
        }),
      ),
    refetchInterval: 30_000,
  });
}

/** E-20: open disruption and bunching alerts, for the toasts (UC-03); alert events patch it like any alert list. */
export function mapAlertsQuery(routeIds: readonly string[], types: readonly string[]) {
  const routes = normalizeList(routeIds);
  const filters = { state: 'open', type: normalizeList(types), routeId: routes };
  return queryOptions({
    queryKey: keys.alerts.list(filters),
    queryFn: () =>
      read(
        api.GET('/api/v1/alerts', {
          params: {
            query: {
              state: 'open',
              type: filters.type,
              routeId: routes.length > 0 ? routes : undefined,
              limit: DISRUPTION_LIMIT,
            },
          },
        }),
      ),
  });
}
