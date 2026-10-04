import { queryOptions } from '@tanstack/react-query';

import { api, read } from '@/api/client';
import { keys } from '@/api/keys';
import type { DateRange } from '@/features/overview/model';

// Queries of the Overview (DOC-36 screens/overview §5). Keys are shared with the shell and the other screens, so the
// same data is fetched once.

const FIVE_MINUTES = 5 * 60_000;
const MINUTE_MS = 60_000;

export function otpQuery(range: DateRange) {
  return queryOptions({
    queryKey: ['insights', 'otp', { from: range.from, to: range.to }] as const,
    queryFn: () =>
      read(api.GET('/api/v1/insights/otp', { params: { query: { fromDate: range.from, toDate: range.to } } })),
    staleTime: FIVE_MINUTES,
    refetchInterval: FIVE_MINUTES,
  });
}

export function liveVehiclesQuery() {
  return queryOptions({
    queryKey: keys.vehicles.live([]),
    queryFn: () => read(api.GET('/api/v1/vehicles/live')),
  });
}

export function openAlertsQuery() {
  return queryOptions({
    queryKey: keys.alerts.list({ state: 'open' }),
    queryFn: () => read(api.GET('/api/v1/alerts', { params: { query: { state: 'open', limit: 100 } } })),
  });
}

export function dlqSummaryQuery() {
  return queryOptions({
    queryKey: keys.etl.dlq.summary(),
    queryFn: () => read(api.GET('/api/v1/etl/dlq/summary')),
  });
}

/** E-31 by minute over the last 15 minutes: the throughput of each source. */
export function recentThroughputQuery() {
  return queryOptions({
    queryKey: keys.etl.jobs.summary({ window: '15m', bucket: '1m' }),
    queryFn: () => {
      const to = Date.now();
      return read(
        api.GET('/api/v1/etl/jobs/summary', {
          params: {
            query: { from: new Date(to - 15 * MINUTE_MS).toISOString(), to: new Date(to).toISOString(), bucket: '1m' },
          },
        }),
      );
    },
  });
}

/** E-31 by hour over 24 hours: failed batch jobs (the same entry as the sidebar's Pipeline dot). */
export function dayOfJobsQuery() {
  return queryOptions({
    queryKey: keys.etl.jobs.summary({ window: '24h', bucket: '1h' }),
    queryFn: () => {
      const to = Date.now();
      return read(
        api.GET('/api/v1/etl/jobs/summary', {
          params: {
            query: {
              from: new Date(to - 24 * 60 * MINUTE_MS).toISOString(),
              to: new Date(to).toISOString(),
              bucket: '1h',
            },
          },
        }),
      );
    },
  });
}

export function routesQuery() {
  return queryOptions({
    queryKey: keys.routes.list(),
    queryFn: () => read(api.GET('/api/v1/routes')),
    staleTime: 60 * MINUTE_MS,
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
