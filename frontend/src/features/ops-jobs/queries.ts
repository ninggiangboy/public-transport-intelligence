import { infiniteQueryOptions, queryOptions } from '@tanstack/react-query';

import { api, read } from '@/api/client';
import { keys } from '@/api/keys';
import { PIPELINE_SUMMARY } from '@/app/shell/use-nav-counts';
import { isRunning, periodInstants, periodKey, RUN_PAGE, type Period } from '@/features/ops-jobs/model';
import type { Bucket, Kind } from '@/features/ops-jobs/search';

// Queries of Pipeline (DOC-36 screens/ops-console-jobs §5). Keys are shared with the sidebar and the Overview, so the
// same data is fetched once. Times are the machine clock (audit axis): a sliding window is computed at each fetch.

/** E-31 while the screen is visible (DOC-33 §5.7): micro-batches emit no `job.run`. */
export const SUMMARY_REFRESH_MS = 10_000;
/** The stream rows of the list: its first page again (§5). */
export const HEAD_REFRESH_MS = 60_000;
const MINUTE_MS = 60_000;
const HOUR_MS = 60 * MINUTE_MS;

function summary(range: { from: string; to: string }, bucket: Bucket) {
  return read(api.GET('/api/v1/etl/jobs/summary', { params: { query: { ...range, bucket } } }));
}

/** E-31 over the period, for the throughput chart, the By source card and the Failed count. */
export function summaryQuery(period: Period, bucket: Bucket) {
  return queryOptions({
    queryKey: keys.etl.jobs.summary({ ...periodKey(period), bucket }),
    queryFn: () => summary(periodInstants(period, Date.now()), bucket),
    refetchInterval: period.kind === 'window' ? SUMMARY_REFRESH_MS : false,
  });
}

/** E-31 by minute over 15 minutes: the stage diagram (the Overview's pipeline panel reads the same entry). */
export function recentSummaryQuery() {
  return queryOptions({
    queryKey: keys.etl.jobs.summary({ window: '15m', bucket: '1m' }),
    queryFn: () => {
      const to = Date.now();
      return summary({ from: new Date(to - 15 * MINUTE_MS).toISOString(), to: new Date(to).toISOString() }, '1m');
    },
    refetchInterval: SUMMARY_REFRESH_MS,
  });
}

/** E-31 hourly over 24 hours: the Batch jobs card and the header line (the sidebar's Pipeline dot reads it too). */
export function daySummaryQuery() {
  return queryOptions({
    queryKey: keys.etl.jobs.summary(PIPELINE_SUMMARY),
    queryFn: () => {
      const to = Date.now();
      return summary(
        { from: new Date(to - 24 * HOUR_MS).toISOString(), to: new Date(to).toISOString() },
        PIPELINE_SUMMARY.bucket,
      );
    },
    refetchInterval: SUMMARY_REFRESH_MS,
  });
}

export interface RunFilters {
  kind?: Kind;
  status?: string[];
  name?: string[];
}

/**
 * E-30 by keyset pages of 100. A sliding window is keyed by `window`, without `to`, so that `job.run` inserts new runs
 * at the head (realtime/handlers.ts); the stream rows come from {@link HEAD_REFRESH_MS} instead of the default refetch
 * of every loaded page.
 */
export function runsQuery(period: Period, filters: RunFilters) {
  return infiniteQueryOptions({
    queryKey: keys.etl.jobs.list({ ...periodKey(period), ...filters }),
    queryFn: ({ pageParam }) =>
      read(
        api.GET('/api/v1/etl/jobs', {
          params: {
            query: { ...periodInstants(period, Date.now()), ...filters, limit: RUN_PAGE, cursor: pageParam },
          },
        }),
      ),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.data.nextCursor,
    refetchInterval: false,
  });
}

/** The first page of {@link runsQuery} on its own, merged over the cache by the head refresh. */
export function fetchRunsHead(period: Period, filters: RunFilters) {
  return read(
    api.GET('/api/v1/etl/jobs', {
      params: { query: { ...periodInstants(period, Date.now()), ...filters, limit: RUN_PAGE } },
    }),
  );
}

/** E-32; `job.run` of the same run invalidates it (realtime/handlers.ts). */
export function runQuery(runId: string, streamDown: boolean) {
  return queryOptions({
    queryKey: keys.etl.job(runId),
    queryFn: () => read(api.GET('/api/v1/etl/jobs/{runId}', { params: { path: { runId } } })),
    // A running job polls every 5 s while the event stream is down (§5); otherwise the default safety net.
    refetchInterval: (query) => (streamDown && isRunning(query.state.data?.data.status ?? '') ? 5_000 : 60_000),
  });
}

/** E-34: polled every 2 s until the request is picked up or refused (§5). */
export function jobRequestQuery(id: string) {
  return queryOptions({
    queryKey: keys.etl.jobRequest(id),
    queryFn: () => read(api.GET('/api/v1/etl/job-requests/{id}', { params: { path: { id } } })),
    refetchInterval: (query) => {
      const status = query.state.data?.data.status;
      return status === undefined || status === 'PENDING' ? 2_000 : false;
    },
  });
}

/** E-37: no automatic refetch; the page has "Refresh" (§5). */
export function batchQuery(batchId: string) {
  return queryOptions({
    queryKey: keys.etl.batch(batchId),
    queryFn: () => read(api.GET('/api/v1/etl/batches/{batchId}', { params: { path: { batchId } } })),
    refetchInterval: false,
    refetchOnWindowFocus: false,
    staleTime: Number.POSITIVE_INFINITY,
  });
}

export function dlqSummaryQuery() {
  return queryOptions({
    queryKey: keys.etl.dlq.summary(),
    queryFn: () => read(api.GET('/api/v1/etl/dlq/summary')),
  });
}

/** E-55, the same entry as the sidebar's paused marker. */
export function flagsQuery() {
  return queryOptions({
    queryKey: keys.etl.flags(),
    queryFn: () => read(api.GET('/api/v1/etl/flags')),
    refetchInterval: 30_000,
  });
}
