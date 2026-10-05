import { infiniteQueryOptions, queryOptions } from '@tanstack/react-query';

import { api, read } from '@/api/client';
import { keys } from '@/api/keys';
import type { DayRange } from '@/features/scorecard/model';
import type { Bucket } from '@/features/scorecard/search';

// Queries of the Route scorecard (DOC-36 screens/route-scorecard §5). Keys are shared with the Overview and the alert
// feed, so the same data is fetched once.

/** Scores and delays are computed each night or aggregated over days (§5). */
const FIVE_MINUTES = 5 * 60_000;
const MINUTE_MS = 60_000;
const HOUR_MS = 60 * MINUTE_MS;
export const DISRUPTION_PAGE = 50;
export const DRAWER_DISRUPTIONS = 5;

/**
 * E-14 for every route of the range. The mode filter applies on the client, so that the "Bus" and "Rail" counts come
 * from the same answer and switching mode needs no request.
 */
export function otpQuery(range: DayRange) {
  return queryOptions({
    queryKey: keys.insights.otp({ from: range.from, to: range.to }),
    queryFn: () =>
      read(api.GET('/api/v1/insights/otp', { params: { query: { fromDate: range.from, toDate: range.to } } })),
    staleTime: FIVE_MINUTES,
    refetchInterval: FIVE_MINUTES,
  });
}

/** E-14 for one route: its KPIs and daily on-time. */
export function routeOtpQuery(range: DayRange, routeId: string) {
  return queryOptions({
    queryKey: keys.insights.otp({ from: range.from, to: range.to, routeIds: [routeId] }),
    queryFn: () =>
      read(
        api.GET('/api/v1/insights/otp', {
          params: { query: { fromDate: range.from, toDate: range.to, routeId: [routeId] } },
        }),
      ),
    staleTime: FIVE_MINUTES,
    refetchInterval: FIVE_MINUTES,
  });
}

export function routesQuery() {
  return queryOptions({
    queryKey: keys.routes.list(),
    queryFn: () => read(api.GET('/api/v1/routes')),
    staleTime: HOUR_MS,
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

export interface DelaysParams {
  /** Instants (§2). */
  from: string;
  to: string;
  bucket: Bucket;
  directionId?: number;
}

/** E-03. */
export function delaysQuery(routeId: string, params: DelaysParams) {
  return queryOptions({
    queryKey: keys.routes.delays(routeId, { ...params }),
    queryFn: () => read(api.GET('/api/v1/routes/{routeId}/delays', { params: { path: { routeId }, query: params } })),
    staleTime: FIVE_MINUTES,
    refetchInterval: FIVE_MINUTES,
  });
}

export interface ProfileParams {
  directionId: number;
  dayOfWeek: number;
  hourOfDay: number;
}

/** E-04: the same key as the stop page's reliability card. */
export function delayProfileQuery(routeId: string, params: ProfileParams) {
  return queryOptions({
    queryKey: keys.routes.delayProfile(routeId, { ...params }),
    queryFn: () =>
      read(api.GET('/api/v1/routes/{routeId}/delay-profile', { params: { path: { routeId }, query: params } })),
    staleTime: FIVE_MINUTES,
    refetchInterval: FIVE_MINUTES,
  });
}

/** E-12, newest five of the range, for the summary drawer. Its key carries `limit`, apart from the paged list. */
export function recentDisruptionsQuery(routeId: string, range: { from: string; to: string }) {
  return queryOptions({
    queryKey: keys.insights.disruption({
      routeIds: [routeId],
      from: range.from,
      to: range.to,
      limit: DRAWER_DISRUPTIONS,
    }),
    queryFn: () =>
      read(
        api.GET('/api/v1/insights/disruption', {
          params: { query: { routeId: [routeId], from: range.from, to: range.to, limit: DRAWER_DISRUPTIONS } },
        }),
      ),
  });
}

/** E-12 of one route over the range, by keyset pages; disruption events invalidate it (DOC-26 §9). */
export function disruptionListQuery(routeId: string, range: { from: string; to: string }) {
  return infiniteQueryOptions({
    queryKey: keys.insights.disruption({ routeIds: [routeId], from: range.from, to: range.to }),
    queryFn: ({ pageParam }) =>
      read(
        api.GET('/api/v1/insights/disruption', {
          params: {
            query: { routeId: [routeId], from: range.from, to: range.to, limit: DISRUPTION_PAGE, cursor: pageParam },
          },
        }),
      ),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.data.nextCursor,
  });
}

export function disruptionDetailQuery(id: string) {
  return queryOptions({
    queryKey: keys.insights.disruptionDetail(id),
    queryFn: () => read(api.GET('/api/v1/insights/disruption/{id}', { params: { path: { id } } })),
  });
}
