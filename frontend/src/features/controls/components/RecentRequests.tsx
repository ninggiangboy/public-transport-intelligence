import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect } from 'react';

import { keys } from '@/api/keys';
import { AppLink } from '@/components/AppLink';
import { StatusPill } from '@/components/StatusPill';
import { GTFS_STATIC_LOAD } from '@/features/controls/jobs';
import { isTerminal } from '@/features/controls/model';
import { requestWatchQuery } from '@/features/controls/queries';
import { recentRequests, useRecentRequests, type RecentRequest } from '@/features/controls/requests-store';
import { controlsCopy } from '@/i18n/controls';
import { notify } from '@/lib/notify';

const copy = controlsCopy;

/** The jobs started from this tab (§4): each follows E-34 and links to its run once there is one. */
export function RecentRequests() {
  const requests = useRecentRequests();
  if (requests.length === 0) return null;
  return (
    <section aria-labelledby="controls-recent" className="flex flex-col gap-2">
      <h4 id="controls-recent" className="text-sm font-semibold">
        {copy.job.recent}
      </h4>
      <ul className="flex flex-col divide-y divide-border rounded-md border border-border">
        {requests.map((request) => (
          <RequestRow key={request.id} request={request} />
        ))}
      </ul>
    </section>
  );
}

function RequestRow({ request }: { request: RecentRequest }) {
  const queryClient = useQueryClient();
  const answer = useQuery(requestWatchQuery(request.id));
  const data = answer.data?.data;
  const status = data?.status ?? 'PENDING';
  const failed = status === 'REJECTED' || status === 'FAILED';

  // How the request ended, once per request even across visits (§6): a toast when refused, the feeds when loaded.
  useEffect(() => {
    if (request.settled || !isTerminal(status)) return;
    recentRequests.settle(request.id);
    void queryClient.invalidateQueries({ queryKey: keys.etl.jobs.all() });
    if (failed) notify.error(copy.feedback.rejected(request.jobName, data?.message ?? status));
    if (status === 'DONE' && request.jobName === GTFS_STATIC_LOAD) {
      void queryClient.invalidateQueries({ queryKey: keys.etl.feeds() });
    }
  }, [request, status, failed, data?.message, queryClient]);

  return (
    <li className="flex flex-col gap-0.5 px-3 py-2 text-sm">
      <div className="flex flex-wrap items-center gap-2">
        <span className="font-mono text-xs">{request.jobName}</span>
        <StatusPill domain="jobRequest" status={status} size="sm" />
        {data?.runId ? (
          <AppLink
            href={`/ops/jobs?run=${encodeURIComponent(data.runId)}`}
            className="text-sm text-primary hover:underline"
          >
            {copy.job.runNumber(String(data.jobExecutionId ?? data.runId))}
          </AppLink>
        ) : null}
      </div>
      {failed && data?.message ? <p className="text-xs text-tone-danger-fg">{data.message}</p> : null}
    </li>
  );
}
