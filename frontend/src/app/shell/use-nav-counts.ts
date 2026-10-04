import { useQuery } from '@tanstack/react-query';

import { api, read } from '@/api/client';
import { keys } from '@/api/keys';
import { hasRole, type Access } from '@/app/access';

/** Counts and markers next to the menu items (DOC-34 §4.1); `undefined` until known, so nothing shows "0" early. */
export interface NavCounts {
  unacknowledgedAlerts?: number;
  openDeadLetters?: number;
  runningReplays?: number;
  ticketingAnomalies?: number;
  pipelineFailed?: boolean;
  paused?: boolean;
}

const HOUR_MS = 3_600_000;
const FLAGS_POLL_MS = 30_000;

/** E-31 over the last 24 hours, hourly: the "Pipeline" dot. Audit axis, so the wall clock (DOC-32 E-31). */
export const PIPELINE_SUMMARY = { window: '24h', bucket: '1h' } as const;

/** Only the queries the role may run: anonymous never calls the ops endpoints (screens/shell-and-navigation §5). */
export function useNavCounts(access: Access): NavCounts {
  const ready = !access.pending;
  const signedIn = ready && access.signedIn;
  const viewer = ready && hasRole(access, 'viewer');

  const alerts = useQuery({
    queryKey: keys.alerts.badge(),
    queryFn: () => read(api.GET('/api/v1/alerts', { params: { query: { state: 'unacknowledged', limit: 100 } } })),
    enabled: signedIn,
    // Alert events patch this entry in place, so an acknowledged alert can still be in it: count what is open.
    select: ({ data }) => data.items.filter((alert) => !alert.acknowledgedAt && !alert.resolvedAt).length,
  });
  const dlq = useQuery({
    queryKey: keys.etl.dlq.summary(),
    queryFn: () => read(api.GET('/api/v1/etl/dlq/summary')),
    enabled: viewer,
    select: ({ data }) => data.open,
  });
  const flags = useQuery({
    queryKey: keys.etl.flags(),
    queryFn: () => read(api.GET('/api/v1/etl/flags')),
    enabled: viewer,
    refetchInterval: FLAGS_POLL_MS,
    select: ({ data }) => data.items.some((flag) => flag.key.endsWith('.paused') && flag.value === true),
  });
  const pipeline = useQuery({
    queryKey: keys.etl.jobs.summary(PIPELINE_SUMMARY),
    queryFn: () => {
      const to = Date.now();
      return read(
        api.GET('/api/v1/etl/jobs/summary', {
          params: {
            query: {
              from: new Date(to - 24 * HOUR_MS).toISOString(),
              to: new Date(to).toISOString(),
              bucket: PIPELINE_SUMMARY.bucket,
            },
          },
        }),
      );
    },
    enabled: viewer,
    select: ({ data }) => data.batchJobs.failed > 0,
  });
  const replays = useQuery({
    queryKey: keys.etl.replays({ status: ['PENDING', 'RUNNING'], limit: 10 }),
    queryFn: () =>
      read(api.GET('/api/v1/etl/replays', { params: { query: { status: ['RUNNING', 'PENDING'], limit: 10 } } })),
    enabled: viewer,
    select: ({ data }) => data.items.length,
  });
  const ticketing = useQuery({
    queryKey: keys.insights.ticketingBadge(),
    queryFn: () => read(api.GET('/api/v1/insights/ticketing-anomalies', { params: { query: { limit: 100 } } })),
    enabled: viewer,
    select: ({ data }) => data.items.length,
  });

  return {
    unacknowledgedAlerts: signedIn ? alerts.data : undefined,
    openDeadLetters: viewer ? dlq.data : undefined,
    runningReplays: viewer ? replays.data : undefined,
    ticketingAnomalies: viewer ? ticketing.data : undefined,
    pipelineFailed: viewer ? pipeline.data : undefined,
    paused: viewer ? flags.data : undefined,
  };
}
