import { queryOptions } from '@tanstack/react-query';

import { api, read, write } from '@/api/client';
import { keys } from '@/api/keys';
import { requestPollMs } from '@/features/controls/model';

// Queries and writes of Controls (DOC-36 screens/ops-console-controls §5). Flags (E-55) and the listener activity
// (E-31, 15 min by minute) share their entries with Pipeline and the sidebar (features/ops-jobs/queries.ts).

/** E-38: the GTFS static feed versions; a GtfsStaticLoadJob that ends refreshes it too. */
export function feedsQuery() {
  return queryOptions({
    queryKey: keys.etl.feeds(),
    queryFn: () => read(api.GET('/api/v1/etl/feeds')),
    refetchInterval: 60_000,
  });
}

/** E-34 for a request of "Recent requests": every 2 s until picked up, then every 10 s until it ends (§5). */
export function requestWatchQuery(id: string) {
  return queryOptions({
    queryKey: keys.etl.jobRequest(id),
    queryFn: () => read(api.GET('/api/v1/etl/job-requests/{id}', { params: { path: { id } } })),
    refetchInterval: (query) => requestPollMs(query.state.data?.data.status),
  });
}

/** E-57: the flag after the change; it applies within 5 seconds. */
export function putFlag(key: string, value: boolean) {
  return write(api.PUT('/api/v1/etl/flags/{key}', { params: { path: { key } }, body: { value } }));
}

/** E-33 with the key generated when the confirm dialog opened, so a retried click is the same request. */
export function requestJobRun(jobName: string, parameters: Record<string, unknown>, idempotencyKey: string) {
  return write(
    api.POST('/api/v1/etl/jobs', {
      params: { header: { 'Idempotency-Key': idempotencyKey } },
      body: { jobName, parameters },
    }),
  );
}
